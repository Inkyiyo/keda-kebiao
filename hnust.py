# -*- coding: utf-8 -*-
"""
湖南科技大学「移动教务」客户端。

链路（与官方 App 一致）：
    学号 + 密码
      -> CAS 统一身份认证（密码 RSA/PKCS#1 v1.5 加密，带 __RSA__ 前缀）
      -> 302 跳转链 loginSso?ticket=... -> 落地页 URL 里的 token
      -> POST /Get_sjkbms        取默认作息模式 kbjcmsid
      -> POST /student/curriculum?week=&kbjcmsid=   取整周课表

仅访问学校服务器，不做任何中转。
"""

from __future__ import annotations

import base64
import secrets
import os
import re
import urllib.parse
from dataclasses import dataclass, field

import requests

import logs
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding

CAS_BASE = "https://cas-i.hnust.edu.cn"
PUBKEY_PATH = "/cas/jwt/publicKey"

CAMPUS = {
    "main": {
        "key": "main",
        "name": "本部",
        "base": "http://kdfw.hnust.edu.cn/bbxyhd",
        "login_sso": "http://kdfw.hnust.edu.cn/bbxyhd/loginSso?toMenu=xs_kcb",
    },
    "xx": {
        "key": "xx",
        "name": "潇湘学院",
        "base": "http://xxfw.hnust.edu.cn/xxxyhd",
        "login_sso": "http://xxfw.hnust.edu.cn/xxxyhd/loginSso?toMenu=xs_kcb",
    },
}

UA = (
    "Mozilla/5.0 (Linux; Android 13; PGT-AN10 Build/HONORPGT-AN10; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 "
    "Mobile Safari/537.36"
)

# 默认作息（接口未返回作息时间时使用）
DEFAULT_SECTIONS = [
    ("08:00", "08:45"), ("08:55", "09:40"), ("10:00", "10:45"), ("10:55", "11:40"),
    ("14:00", "14:45"), ("14:55", "15:40"), ("16:00", "16:45"), ("16:55", "17:40"),
    ("19:00", "19:45"), ("19:55", "20:40"), ("20:50", "21:35"), ("21:45", "22:30"),
]

WEEKDAY_ORDER = ["一", "二", "三", "四", "五", "六", "日"]

# 学校证书链偶尔缺中间证书，certifi 会校验失败而系统证书库能过；
# 优先用系统证书库，Windows 上回退到 requests 默认（certifi）。
CA_BUNDLE = os.environ.get("HNUST_CA_BUNDLE") or next(
    (p for p in ("/etc/ssl/certs/ca-certificates.crt",
                 "/etc/pki/tls/certs/ca-bundle.crt",
                 "/etc/ssl/cert.pem") if os.path.exists(p)),
    True,
)


class HnustError(Exception):
    """带分类的业务异常，方便前端给出可读提示。"""

    def __init__(self, message: str, kind: str = "error", need_captcha: bool = False):
        super().__init__(message)
        self.message = message
        self.kind = kind            # auth / captcha / network / expired / server / error
        self.need_captcha = need_captcha


@dataclass
class LoginResult:
    token: str
    cookies: dict = field(default_factory=dict)
    raw_landing: str = ""


# --------------------------------------------------------------------------- #
# RSA
# --------------------------------------------------------------------------- #
def _load_public_key(session: requests.Session):
    try:
        resp = session.get(CAS_BASE + PUBKEY_PATH, timeout=15)
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise HnustError(f"无法获取统一认证公钥：{exc}", "network") from exc

    pem = resp.text.strip()
    if "BEGIN" not in pem:                       # 纯 base64 DER
        pem = "-----BEGIN PUBLIC KEY-----\n" + pem + "\n-----END PUBLIC KEY-----"
    try:
        return serialization.load_pem_public_key(pem.encode())
    except Exception as exc:                     # noqa: BLE001
        raise HnustError("统一认证公钥解析失败", "server") from exc


def rsa_encrypt_password(public_key, password: str) -> str:
    """服务端会剥掉前 7 个字符（即 __RSA__）再解密，所以必须带前缀。"""
    cipher = public_key.encrypt(password.encode("utf-8"), padding.PKCS1v15())
    return "__RSA__" + base64.b64encode(cipher).decode()


# --------------------------------------------------------------------------- #
# 解析小工具
# --------------------------------------------------------------------------- #
def _find_execution(html: str) -> str | None:
    m = re.search(r'name="execution"\s+value="([^"]+)"', html)
    if not m:
        m = re.search(r'value="([^"]+)"\s+name="execution"', html)
    return m.group(1) if m else None


def _login_alert(html: str) -> str | None:
    """失败页把提示塞在 id=loginError1 的 el-alert 里。"""
    block = html
    m = re.search(r'id="loginError1"(.*?)</el-alert>', html, re.S)
    if m:
        block = m.group(1)
    m = re.search(r'<el-alert[^>]*title="([^"]+)"', block)
    if m:
        return m.group(1).strip().rstrip("。")
    return None


def _captcha_needed(html: str, message: str | None) -> bool:
    if message and "验证码" in message:
        return True
    # 服务端注入的验证码要求：错误块里出现验证码输入
    return 'id="loginError1"' in html and "captcha" in html.split('id="loginError1"')[-1][:2000] \
        and "captcha.jpg" in html.split('id="loginError1"')[-1][:2000]


def _extract_token(url: str) -> str | None:
    try:
        parsed = urllib.parse.urlparse(url)
        qs = urllib.parse.parse_qs(parsed.query)
        if parsed.fragment:
            qs.update(urllib.parse.parse_qs(parsed.fragment))
    except ValueError:
        return None
    for key in ("token", "Token"):
        if qs.get(key):
            value = qs[key][0].strip()
            if len(value) > 16:
                return value
    return None


# 落地页不一定把 token 放在地址栏：正方/强智系常见「200 + 一小段 HTML/JSON，
# 里面用 script 拼出带 token 的地址」。所以除了 URL，还要从响应体里找。
_TOKEN_IN_TEXT = (
    re.compile(r'[?&]token=([^&\s"\'<>]{16,})', re.I),
    re.compile(r'["\']token["\']\s*[=:]\s*["\']([^"\']{16,})["\']', re.I),
    re.compile(r'\btoken\s*[=:]\s*([A-Za-z0-9\-_.%+/=]{16,})', re.I),
)

_SOFT_REDIRECT = (
    re.compile(r'location\s*\.\s*(?:href\s*=|replace\s*\(\s*)\s*["\']([^"\']+)["\']', re.I),
    re.compile(r'window\s*\.\s*location\s*=\s*["\']([^"\']+)["\']', re.I),
    re.compile(r'http-equiv=["\']refresh["\'][^>]*?url=([^"\';]+)', re.I),
)

_LONG_RUN = re.compile(r"[A-Za-z0-9_\-+/=%]{32,}")


def _token_from_text(text: str | None) -> str | None:
    if not text:
        return None
    for pattern in _TOKEN_IN_TEXT:
        m = pattern.search(text)
        if m:
            value = urllib.parse.unquote(m.group(1)).strip()
            if len(value) > 16:
                return value
    return None


def _looks_like_cas_login(html: str | None) -> bool:
    """登录页本身也有 token 字样和 location 赋值，别把它当成落地页来解析。"""
    return bool(html) and 'name="execution"' in html


def _soft_redirect(html: str | None) -> str | None:
    """页面里的 JS / meta 跳转目标，用于跟随「200 的中间页」。"""
    if not html or len(html) > 200000:
        return None
    for pattern in _SOFT_REDIRECT:
        m = pattern.search(html)
        if m:
            target = m.group(1).strip()
            if target and not target.lower().startswith(("javascript:", "#")):
                return target
    return None


def _snippet(text: str | None, limit: int = 260) -> str:
    """把落地页压成一行写进日志；顺手把任何长串（疑似票据）打成 ***。"""
    if not text:
        return "(空)"
    flat = _LONG_RUN.sub("***", re.sub(r"\s+", " ", text)).strip()
    if len(flat) > limit:
        flat = flat[:limit] + "…"
    return flat


ERROR_HINTS = [
    "用户名或密码错误", "密码错误", "用户名不存在", "用户不存在", "账号不存在",
    "账户被锁定", "账号已被锁定", "密码已过期", "验证码错误", "验证码不正确",
    "登录失败", "用户名不能为空", "密码不能为空",
]


def _extract_error_text(html: str) -> str | None:
    for hint in ERROR_HINTS:
        if hint in html:
            return hint
    m = re.search(r'(?:错误|异常|失败)[^<>{}"]{0,40}', html)
    if m:
        return m.group(0).strip()
    return None


def _clean(text) -> str:
    return re.sub(r"\s+", " ", str(text or "")).strip()


def parse_sections_from_class_time(value) -> tuple[int, int] | None:
    """
    classTime 是紧凑数字串，格式为「星期(1 位) + 起始节次(2 位) + 结束节次(2 位)，
    如周三 3–4 节 = "30304"；也有无星期前缀的 "0304"。
    旧版从首位开始两两切片，"30304" 切出 30、30 全被判无效、整门课被丢弃
    （课表全空的真因）。这里与真机验证过的解析一致：奇数长度 ≥5 先去星期前缀。
    """
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        if re.fullmatch(r"\d+", text):
            d = text
            if len(d) % 2 == 1 and len(d) >= 5:
                d = d[1:]
            if len(d) == 3:
                a = int(d[0])
                b = int(d[1:])
                return (a, b) if 1 <= a <= b <= 30 else None
            if len(d) % 2 == 0 and len(d) >= 4:
                a = int(d[:2])
                b = int(d[-2:])
                return (a, b) if 1 <= a <= b <= 30 else None
            if len(d) <= 2:
                a = int(d)
                return (a, a) if 1 <= a <= 30 else None
            return None
        cleaned = re.sub(r"[^0-9\-~]", "", text)
        parts = cleaned.split("-") if "-" in cleaned else cleaned.split("~")
        parts = [p for p in parts if p != ""]
        if len(parts) >= 2:
            a, b = int(parts[0]), int(parts[1])
            if 1 <= a <= 14 and b >= a:
                return (a, b)
        elif len(parts) == 1:
            a = int(parts[0])
            if 1 <= a <= 14:
                return (a, a + 1)
    except (TypeError, ValueError):
        return None
    return None


def _first(raw: dict, *keys: str):
    for k in keys:
        v = raw.get(k)
        if v is not None and str(v) != "":
            return v
    return None


def _to_course(raw: dict) -> dict | None:
    """原始课程 → 统一结构；认不出课程名才放弃，节次解析不出给默认 1–2 节也不丢课。"""
    name = _first(raw, "courseName", "kcmc", "name", "course_name", "KCMC", "course")
    if not name:
        return None
    rng = None
    time_text = _first(raw, "classTime", "courseTime", "jcs", "jc", "kcsj", "section", "sksj", "jieshu")
    if time_text is not None:
        rng = parse_sections_from_class_time(time_text)
    if rng is None:
        s = _int_field(raw, "ksjc", "kssj", "startSection", "start", "startPeriod", "ksj")
        e = _int_field(raw, "jsjc", "jssj", "endSection", "end", "endPeriod", "jsj")
        if s > 0:
            rng = (s, e if e > 0 else s + 1)
    if rng is None:
        rng = (1, 2)
    teacher = _first(raw, "teacherName", "jsxm", "teacher", "js", "JSXM")
    location = _first(raw, "location", "jsmc", "classroom", "classRoom", "room", "place", "JSMC")
    weeks = _first(raw, "classWeek", "zc", "weeks", "skzc", "weekOfSemester")
    return {
        "name": _clean(name),
        "teacher": _clean(teacher),
        "location": _clean(location),
        "start": rng[0],
        "end": rng[1],
        "weeks": _clean(weeks),
    }


def _int_field(raw: dict, *keys: str) -> int:
    for k in keys:
        if k in raw:
            try:
                return int(re.sub(r"[^0-9]", "", str(raw[k])) or 0)
            except ValueError:
                pass
    return 0


def _weekday_number(xqmc) -> int:
    if xqmc is None:
        return -1
    s = str(xqmc).strip()
    for name, num in (("一", 1), ("二", 2), ("三", 3), ("四", 4), ("五", 5), ("六", 6)):
        if name in s:
            return num
    if "日" in s or "天" in s:
        return 7
    digits = re.sub(r"[^0-9]", "", s)
    if digits:
        v = int(digits)
        if 1 <= v <= 7:
            return v
    return -1


def _course_weekday(raw: dict) -> int:
    ct = _first(raw, "classTime", "courseTime")
    if ct is not None:
        d = re.sub(r"\D", "", str(ct))
        if len(d) >= 5 and 1 <= int(d[0]) <= 7:
            return int(d[0])
    wd = _first(raw, "xqj", "weekDay", "day", "weekday", "dayOfWeek", "xqmc")
    if wd is not None:
        v = _weekday_number(wd)
        if v > 0:
            return v
        digits = re.sub(r"[^0-9]", "", str(wd))
        if digits:
            n = int(digits)
            if n == 0:
                return 7
            if 1 <= n <= 7:
                return n
    return -1


# --------------------------------------------------------------------------- #
# 客户端
# --------------------------------------------------------------------------- #
class HnustClient:
    def __init__(self, campus: str = "main", token: str | None = None,
                 cookies: dict | None = None, timeout: int = 20):
        self.campus = campus if campus in CAMPUS else "main"
        self.cfg = CAMPUS[self.campus]
        self.timeout = timeout
        self.session = requests.Session()
        self._attach_log_hook()
        self.session.headers.update({
            "User-Agent": UA,
            "Accept-Language": "zh-CN,zh;q=0.9",
        })
        self.session.verify = CA_BUNDLE
        self.token = token
        if cookies:
            for name, value in cookies.items():
                self.session.cookies.set(name, value)

    def _attach_log_hook(self) -> None:
        """把每一次 HTTP 往返记进诊断日志，URL 由 logs.mask 统一打码。"""
        def on_response(resp, *args, **kwargs):
            try:
                logs.record("H", f"{resp.request.method} {resp.url} -> "
                                 f"{resp.status_code} ({len(resp.content)}B)")
            except Exception:
                pass
        self.session.hooks.setdefault("response", []).append(on_response)

    # ---------------- 登录 ---------------- #
    def _login_page(self, keep: requests.Session | None = None):
        """打开 CAS 登录页，拿 execution。"""
        session = keep or self.session
        try:
            resp = session.get(
                CAS_BASE + "/cas/login",
                params={"service": self.cfg["login_sso"]},
                timeout=self.timeout,
                allow_redirects=True,
            )
        except requests.RequestException as exc:
            raise HnustError(f"无法连接统一认证服务器：{exc}", "network") from exc

        # 已有会话，直接跳到了业务系统
        token = _extract_token(resp.url)
        if token:
            return None, token, resp.text

        execution = _find_execution(resp.text)
        if not execution:
            raise HnustError("统一认证页面结构异常，请稍后重试", "server")
        return execution, None, resp.text

    def login(self, username: str, password: str, captcha: str | None = None,
              stage_session: requests.Session | None = None) -> LoginResult:
        username = username.strip()
        password = password or ""
        if not username or not password:
            raise HnustError("请填写学号和密码", "auth")

        session = stage_session or self.session
        execution, existing, html = self._login_page(keep=session)
        if existing:
            self.token = existing
            return LoginResult(existing, self._cookies(), html)

        public_key = _load_public_key(session)
        encrypted = rsa_encrypt_password(public_key, password)
        payload = {
            "username": username,
            "password": encrypted,
            "execution": execution,
            "_eventId": "submit",
            "currentMenu": "1",
            # 浏览器里这个隐藏域初始是 -1，页面脚本加载后改成 localStorage 的失败次数（首次为 0）。
            # 一直发 -1 等于告诉服务端「当前处于验证码强制状态」，这是之前登录不稳的一个来源。
            "failN": "0",
            "mfaState": "",
            "geolocation": "",
            "fpVisitorId": self._visitor_id(),
            "trustAgent": "",
            "submit1": "Login1",
            "captcha": captcha or "",
        }
        try:
            resp = session.post(
                CAS_BASE + "/cas/login",
                params={"service": self.cfg["login_sso"]},
                data=payload,
                headers={
                    "Origin": CAS_BASE,
                    "Referer": f"{CAS_BASE}/cas/login?service={urllib.parse.quote(self.cfg['login_sso'], safe='')}",
                },
                timeout=self.timeout, allow_redirects=False,
            )
        except requests.RequestException as exc:
            raise HnustError(f"统一认证请求失败：{exc}", "network") from exc

        token, final = self._resolve_token(resp)
        if not token:
            page = (final.text if final is not None else "") or resp.text
            hint = _login_alert(page) or _extract_error_text(page)
            if _captcha_needed(page, hint):
                raise HnustError(hint or "需要图形验证码", "captcha", need_captcha=True)
            # CAS 返回 302 说明票据已经签发，也就是学号密码是对的。
            # 这时候再报「密码错误」会把排查方向带偏，所以单独给一种说法。
            if resp.status_code in (301, 302, 303, 307):
                raise HnustError(
                    "统一认证已通过（已签发票据），但教务系统没交出 token："
                    "落地页是 " + str(len(final.content) if final is not None else 0) + "B 的空页面。"
                    "请把日志发给我，我按落地页结构适配", "sso")
            if self._verify_credentials(username, encrypted) is True:
                raise HnustError(
                    "账号密码正确，但教务系统拒绝登录：可能已被连续失败触发验证码或临时锁定，"
                    "请等 10 分钟再试", "blocked")
            raise HnustError(hint or "登录失败，请检查学号与密码", "auth")

        self.token = token
        self._land(final, token)
        return LoginResult(token, self._cookies(), final.text if final is not None else "")

    def _visitor_id(self) -> str:
        """固定一个 32 位十六进制设备指纹，对应登录页里 FingerprintJS 的 visitorId。"""
        if not getattr(self, "_fp", ""):
            self._fp = secrets.token_hex(16)
        return self._fp

    def _verify_credentials(self, username: str, encrypted: str) -> bool | None:
        """
        单独用门户的 passwordLogin 校验账号密码。
        它跟 CAS 表单用同一把公钥、同一套加密，但返回结构化结果：
          200 + idToken  -> 密码正确
          401            -> 密码错误
          500 解密异常    -> 加密方式不对
        用来把「密码真的错」和「密码对但后续被挡」分开，避免一句笼统的「密码错误」掩盖真问题。
        """
        try:
            resp = self.session.post(
                CAS_BASE + "/token/password/passwordLogin",
                params={"username": username, "password": encrypted},
                timeout=self.timeout,
            )
        except requests.RequestException:
            return None
        if resp.status_code == 200:
            try:
                return bool((resp.json().get("data") or {}).get("idToken"))
            except ValueError:
                return None
        if resp.status_code == 401:
            return False
        return None

    @staticmethod
    def _pick_token(resp: requests.Response):
        """按 URL → Location → Set-Cookie → 响应体 的顺序找 token，并说明来源。"""
        token = _extract_token(resp.url)
        if token:
            return token, "地址栏"
        token = _extract_token(resp.headers.get("Location", ""))
        if token:
            return token, "跳转地址"
        for cookie in resp.cookies:
            if "token" in cookie.name.lower() and len(cookie.value) > 16:
                return cookie.value.strip(), f"Cookie:{cookie.name}"
        text = resp.text or ""
        if len(text) <= 100000 and not _looks_like_cas_login(text):
            token = _token_from_text(text)
            if token:
                return token, "响应体"
        return None, ""

    def _resolve_token(self, resp: requests.Response):
        """
        从 CAS 的 302 起，一路跟到能拿到 token 的那一页。

        教务 SSO 落地页有两种形态：
          1) 302 链最后落在 .../index?token=xxx —— 地址栏里就有；
          2) 落在 .../loginSso;jsessionid=xxx 且返回 200，token 写在 HTML/JSON 里，
             或者只是一段用 script/meta 拼出下一步地址的中间页 —— 地址栏永远看不到。
        旧实现只认第 1 种，遇到第 2 种就会把「已经登录成功」误判成「密码错误」。
        """
        token, source = self._pick_token(resp)
        hops = 0
        while not token and hops < 12:
            if resp.is_redirect:
                target = urllib.parse.urljoin(resp.url, resp.headers.get("Location", ""))
            else:
                if _looks_like_cas_login(resp.text):
                    break
                target = _soft_redirect(resp.text)
                if not target:
                    break
                target = urllib.parse.urljoin(resp.url, target)
            hops += 1
            logs.record("I", f"SSO 第 {hops} 跳 {target}")
            try:
                resp = self.session.get(target, timeout=self.timeout, allow_redirects=False)
            except requests.RequestException as exc:
                raise HnustError(f"跟随登录跳转失败：{exc}", "network") from exc
            token, source = self._pick_token(resp)

        if token:
            logs.record("I", f"token 来源：{source}，跳转 {hops} 跳")
        else:
            logs.record("I", f"落地页 {len(resp.content)}B 无 token，跳转 {hops} 跳，"
                             f"片段：{_snippet(resp.text)}")
        return token, resp

    def _land(self, resp: requests.Response, token: str | None) -> None:
        """落地一次，把教务站点的 Cookie（JSESSIONID）收全。"""
        try:
            if resp.is_redirect:
                target = urllib.parse.urljoin(resp.url, resp.headers.get("Location", ""))
            else:
                soft = _soft_redirect(resp.text)
                if soft:
                    target = urllib.parse.urljoin(resp.url, soft)
                elif "token=" in resp.url:
                    target = resp.url
                else:
                    target = self.cfg["base"].rstrip("/") + "/"
            self.session.get(target, timeout=self.timeout, allow_redirects=True)
        except requests.RequestException:
            pass

    def _cookies(self) -> dict:
        return {c.name: c.value for c in self.session.cookies}

    # ---------------- 课表 ---------------- #
    def _api(self, path: str, params: dict | None = None) -> dict:
        if not self.token:
            raise HnustError("尚未登录", "expired")
        url = self.cfg["base"].rstrip("/") + path
        headers = {
            "token": self.token,
            "Accept": "application/json, text/plain, */*",
            "Referer": self.cfg["base"] + "/",
            "X-Requested-With": "XMLHttpRequest",
        }
        try:
            resp = self.session.post(url, params=params, headers=headers,
                                     data={}, timeout=self.timeout)
        except requests.RequestException as exc:
            raise HnustError(f"无法连接教务系统：{exc}", "network") from exc

        if resp.status_code == 401:
            raise HnustError("登录状态已过期", "expired")
        text = resp.text.strip()
        try:
            payload = resp.json()
        except ValueError:
            if "非法访问" in text or "401" in text[:200]:
                raise HnustError("登录状态已过期", "expired")
            raise HnustError("教务系统返回了非预期内容", "server")

        code = str(payload.get("code", "")).strip()
        if code in ("401", "-1", "500"):
            msg = _clean(payload.get("Msg") or payload.get("msg"))
            if code == "401" or "未登录" in msg or "非法" in msg:
                raise HnustError("登录状态已过期", "expired")
            raise HnustError(msg or "教务系统返回错误", "server")
        return payload

    def get_kbjcmsid(self, cached: str | None = None) -> str:
        if cached:
            return cached
        payload = self._api("/Get_sjkbms")
        modes = payload.get("data") or []
        if isinstance(modes, dict):
            modes = [modes]
        for mode in modes:
            if str(mode.get("mrms", "")).strip() in ("1", "true", "True"):
                value = mode.get("kbjcmsid") or mode.get("kbjcmsId")
                if value:
                    return str(value)
        for mode in modes:
            value = mode.get("kbjcmsid") or mode.get("kbjcmsId")
            if value:
                return str(value)
        raise HnustError("未取到作息模式（kbjcmsid）", "server")

    def get_curriculum(self, week: int | str | None, kbjcmsid: str) -> dict:
        params = {"week": "" if week in (None, "", 0) else str(week), "kbjcmsid": kbjcmsid}
        return self._api("/student/curriculum", params)

    def get_sections_hint(self) -> list[tuple[str, str]]:
        """尝试从 Get_sjkbms 里读到节次起止时间，读不到就用默认作息。"""
        try:
            payload = self._api("/Get_sjkbms")
        except HnustError:
            return list(DEFAULT_SECTIONS)
        found: dict[int, tuple[str, str]] = {}

        def walk(node, depth=0):
            if depth > 6 or len(found) >= 12:
                return
            if isinstance(node, dict):
                idx = node.get("jc") or node.get("jcmc") or node.get("section")
                start = node.get("kssj") or node.get("startTime") or node.get("sksj")
                end = node.get("jssj") or node.get("endTime")
                try:
                    n = int(str(idx).strip())
                except (TypeError, ValueError):
                    n = 0
                if n and start and end and re.match(r"^\d{1,2}:\d{2}", str(start)):
                    found.setdefault(n, (str(start)[:5], str(end)[:5]))
                for value in node.values():
                    walk(value, depth + 1)
            elif isinstance(node, list):
                for value in node:
                    walk(value, depth + 1)

        walk(payload)
        if len(found) >= 8:
            return [found.get(i, DEFAULT_SECTIONS[i - 1]) for i in range(1, 13)]
        return list(DEFAULT_SECTIONS)


# --------------------------------------------------------------------------- #
# 课表结构化
# --------------------------------------------------------------------------- #
def normalize_curriculum(payload: dict, wanted_week: int | None = None) -> dict:
    data = payload.get("data")
    if isinstance(data, dict):
        data = [data]
    if not data:
        raise HnustError("教务系统没有返回课表数据", "server")

    block = data[0] or {}
    # 显式请求了某一周时以请求为准：返回体里的 week 是「当前周」，
    # 采用它会让翻周后周次标称永远停在当前周（与 Java 版同一个 bug）。
    week = wanted_week if wanted_week is not None else block.get("week")
    try:
        week = int(week)
    except (TypeError, ValueError):
        week = wanted_week

    dates = block.get("date") or []
    items = block.get("item") or []
    if isinstance(items, dict):  # {"0": [...], "1": [...]} 形式
        items = [items.get(str(k)) for k in sorted(int(k) for k in items if str(k).isdigit())]

    day_total = max(len(dates), 7)
    per_day: list[list[dict]] = [[] for _ in range(day_total)]
    parsed_total = 0
    for index in range(day_total):
        raw_courses = items[index] if index < len(items) else []
        if isinstance(raw_courses, dict):
            nested = None
            for k in ("courses", "list", "courseList"):
                if isinstance(raw_courses.get(k), list):
                    nested = raw_courses[k]
                    break
            raw_courses = nested if nested is not None else [raw_courses]
        if not isinstance(raw_courses, list):
            continue
        for raw in raw_courses:
            if not isinstance(raw, dict):
                continue
            course = _to_course(raw)
            if course is not None:
                per_day[index].append(course)
                parsed_total += 1

    # 兜底：item 里没课时，试平铺的 courses 数组（课程自带星期），按星期分到各天
    if parsed_total == 0 and isinstance(block.get("courses"), list) and block["courses"]:
        by_weekday = {}
        for index in range(day_total):
            info = dates[index] if index < len(dates) else {}
            wd = _weekday_number(info.get("xqmc")) if isinstance(info, dict) else -1
            if wd > 0:
                by_weekday[wd] = index
        for raw in block["courses"]:
            if not isinstance(raw, dict):
                continue
            course = _to_course(raw)
            if course is None:
                continue
            idx = by_weekday.get(_course_weekday(raw), 0)
            per_day[idx].append(course)
            parsed_total += 1

    days = []
    for index in range(day_total):
        date_info = dates[index] if index < len(dates) else {}
        courses = sorted(per_day[index], key=lambda c: (c["start"], c["name"]))
        days.append({
            "date": _clean(date_info.get("mxrq")),
            "weekday": _clean(date_info.get("xqmc")) or (
                WEEKDAY_ORDER[index] if index < 7 else ""),
            "courses": _merge_adjacent(courses),
        })
    return {"week": week, "days": days}


def _merge_adjacent(courses: list[dict]) -> list[dict]:
    """同名同地点、节次相连的课合并成一整块（如 3-4 节 + 5-6 节）。"""
    merged: list[dict] = []
    for course in courses:
        last = merged[-1] if merged else None
        if (last and last["name"] == course["name"]
                and last["location"] == course["location"]
                and course["start"] <= last["end"] + 1):
            last["end"] = max(last["end"], course["end"])
            if course["teacher"] and course["teacher"] not in last["teacher"]:
                last["teacher"] = (last["teacher"] + " / " + course["teacher"]).strip(" /")
            continue
        merged.append(dict(course))
    return merged
