# -*- coding: utf-8 -*-
"""
科大课表 —— 本地服务。

FastAPI 提供：登录（走学校统一认证）、课表接口、演示数据、静态前端。
数据只保存在本机 data/ 目录，不经过任何第三方服务器。
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import json
import os
import secrets
import time
from pathlib import Path

import requests
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles

import demo
import logs
from hnust import (
    CAMPUS, DEFAULT_SECTIONS, HnustClient, HnustError, WEEKDAY_ORDER,
    normalize_curriculum,
)

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
STATE_FILE = DATA / "state.json"
KEY_FILE = DATA / ".key"

app = FastAPI(title="科大课表", docs_url=None, redoc_url=None)


@app.middleware("http")
async def log_requests(request: Request, call_next):
    """每条请求记一行：方法、路径、状态码、耗时。密码和 token 在 query 里也会被打码。"""
    started = time.time()
    response = await call_next(request)
    took = (time.time() - started) * 1000
    if request.url.path.startswith("/api/"):
        logs.record("R", f"{request.method} {request.url.path} "
                         f"{dict(request.query_params)} -> {response.status_code} ({took:.0f}ms)")
    return response


# --------------------------------------------------------------------------- #
# 本地状态
# --------------------------------------------------------------------------- #
def _local_key() -> bytes:
    if not KEY_FILE.exists():
        KEY_FILE.write_bytes(secrets.token_bytes(32))
        try:
            os.chmod(KEY_FILE, 0o600)
        except OSError:
            pass
    return KEY_FILE.read_bytes()


def _seal(text: str) -> str:
    if not text:
        return ""
    salt = secrets.token_bytes(8)
    stream = hashlib.sha256(_local_key() + salt).digest()
    raw = bytes(b ^ stream[i % len(stream)] for i, b in enumerate(text.encode("utf-8")))
    return base64.b64encode(salt + raw).decode()


def _unseal(token: str) -> str:
    if not token:
        return ""
    try:
        blob = base64.b64decode(token)
        salt, raw = blob[:8], blob[8:]
        stream = hashlib.sha256(_local_key() + salt).digest()
        return bytes(b ^ stream[i % len(stream)] for i, b in enumerate(raw)).decode("utf-8")
    except Exception:  # noqa: BLE001
        return ""


DEFAULT_STATE = {
    "campus": "main",
    "username": "",
    "token": "",
    "cookies": {},
    "kbjcmsid": "",
    "sections": [],
    "term_start": "",
    "total_weeks": 20,
    "demo": False,
    "remember": False,
    "password_sealed": "",
}

state: dict = dict(DEFAULT_STATE)
state.update(json.loads(STATE_FILE.read_text("utf-8")) if STATE_FILE.exists() else {})

_wcache: dict[int, tuple[float, dict]] = {}
_pending: dict[str, requests.Session] = {}
_login_fails = 0
CACHE_TTL = 240
_started_at = time.time()


def save_state() -> None:
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), "utf-8")
    os.replace(tmp, STATE_FILE)
    try:
        os.chmod(STATE_FILE, 0o600)
    except OSError:
        pass


def _term_start() -> dt.date | None:
    try:
        return dt.date.fromisoformat(state["term_start"]) if state.get("term_start") else None
    except ValueError:
        return None


def _week_dates(week: int) -> list[str]:
    start = _term_start()
    if not start:
        return [""] * 7
    monday = start + dt.timedelta(days=7 * (week - 1))
    return [(monday + dt.timedelta(days=i)).isoformat() for i in range(7)]


def _sections() -> list[dict]:
    rows = state.get("sections") or DEFAULT_SECTIONS
    return [{"index": i + 1, "start": s, "end": e} for i, (s, e) in enumerate(rows[:12])]


def _today_week() -> int:
    start = _term_start()
    if not start:
        return 1
    delta = (dt.date.today() - start).days
    return max(1, min(state["total_weeks"], delta // 7 + 1))


# --------------------------------------------------------------------------- #
# 取课表（含过期自动重登）
# --------------------------------------------------------------------------- #
def _client() -> HnustClient:
    return HnustClient(state["campus"], token=state.get("token"),
                       cookies=state.get("cookies") or {})


def _relogin() -> bool:
    password = _unseal(state.get("password_sealed", ""))
    if not state.get("username") or not password:
        return False
    try:
        client = HnustClient(state["campus"])
        result = client.login(state["username"], password)
    except HnustError:
        return False
    state.update({"token": result.token, "cookies": result.cookies, "demo": False})
    save_state()
    return True


def _fetch_week(week: int | None, force: bool = False) -> dict:
    key = int(week or 0)
    cached = _wcache.get(key)
    if cached and not force and time.time() - cached[0] < CACHE_TTL:
        return cached[1]

    if state.get("demo"):
        result = _demo_payload(week)
        _wcache[key] = (time.time(), result)
        return result

    for attempt in (1, 2):
        client = _client()
        try:
            if not state.get("kbjcmsid"):
                state["kbjcmsid"] = client.get_kbjcmsid()
                state["sections"] = client.get_sections_hint()
                save_state()
            payload = client.get_curriculum(week, state["kbjcmsid"])
            data = normalize_curriculum(payload, week)
            break
        except HnustError as exc:
            if exc.kind in ("expired", "network") and attempt == 1 and _relogin():
                continue
            if exc.kind == "expired":
                state.update({"token": "", "cookies": {}, "kbjcmsid": ""})
                save_state()
            raise

    weeks = data.get("week")
    if not week and weeks:
        dates = data["days"][0]["date"] if data["days"] else ""
        if dates:
            try:
                monday = dt.date.fromisoformat(dates)
                state["term_start"] = (monday - dt.timedelta(days=7 * (weeks - 1))).isoformat()
                save_state()
            except ValueError:
                pass

    result = _shape(data, weeks)
    stamp = time.time()
    _wcache[key] = (stamp, result)
    if weeks and weeks != key:
        _wcache[weeks] = (stamp, result)      # 让「当前周」也能被课程汇总统计到
    return result


def _shape(data: dict, week: int | None) -> dict:
    week = int(week or data.get("week") or _today_week())
    dates = _week_dates(week)
    today = dt.date.today().isoformat()
    days = []
    for index, day in enumerate(data["days"]):
        date = day.get("date") or dates[index]
        weekday = day.get("weekday") or WEEKDAY_ORDER[index]
        days.append({
            "date": date,
            "weekday": weekday,
            "today": date == today,
            "courses": day["courses"],
        })
    return {
        "week": week,
        "currentWeek": _today_week(),
        "termStart": state.get("term_start", ""),
        "totalWeeks": state["total_weeks"],
        "sections": _sections(),
        "days": days,
        "empty": all(not d["courses"] for d in days),
    }


def _demo_payload(week: int | None) -> dict:
    start = _term_start() or demo.term_start()
    if state.get("term_start") != start.isoformat():
        state["term_start"] = start.isoformat()
        save_state()
    real_week = _today_week()
    target = int(week or real_week)
    days = demo.demo_week(target, start)
    data = {"week": target, "days": [{"date": d["date"],
                                      "weekday": WEEKDAY_ORDER[i],
                                      "courses": d["courses"]}
                                     for i, d in enumerate(days)]}
    return _shape(data, target)


# --------------------------------------------------------------------------- #
# 接口
# --------------------------------------------------------------------------- #
def ok(payload: dict | None = None, **extra):
    body = {"ok": True}
    body.update(payload or {})
    body.update(extra)
    return JSONResponse(body)


def fail(exc: HnustError | str, kind: str = "error", **extra):
    if isinstance(exc, HnustError):
        kind, message = exc.kind, exc.message
        extra.setdefault("needCaptcha", exc.need_captcha)
    else:
        message = exc
    body = {"ok": False, "error": message, "kind": kind}
    body.update(extra)
    return JSONResponse(body)


@app.get("/api/status")
def api_status():
    return ok({
        "loggedIn": bool(state.get("token")) and not state.get("demo"),
        "demo": bool(state.get("demo")),
        "username": state.get("username", ""),
        "campus": state.get("campus", "main"),
        "campusName": CAMPUS[state.get("campus", "main")]["name"],
        "remember": bool(state.get("remember")),
        "hasPassword": bool(state.get("password_sealed")),
        "totalWeeks": state["total_weeks"],
        "currentWeek": _today_week(),
        "uptime": int(time.time() - _started_at),
    })


@app.post("/api/login")
async def api_login(request: Request):
    global _login_fails
    body = await request.json()
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    campus = body.get("campus") or "main"
    captcha = body.get("captcha")
    cid = body.get("cid")

    session = _pending.get(cid) if cid else None
    client = HnustClient(campus)
    if session is not None:
        client.session = session
    else:
        _pending.clear()

    logs.record("S", f"登录开始 campus={campus} 学号={username}")
    try:
        result = client.login(username, password, captcha=captcha, stage_session=session)
        _login_fails = 0
        logs.record("S", f"登录成功，token 长度 {len(result.token)}")
    except HnustError as exc:
        logs.record("E", f"登录失败 [{exc.kind}] {exc.message}")
        if exc.kind == "auth":
            _login_fails += 1
        # 连续失败三次后主动要验证码（与学校登录页的 captchaSkipN=3 一致）
        if exc.kind == "auth" and _login_fails >= 3 and not captcha:
            exc = HnustError("登录失败 3 次，请输入图形验证码后重试", "captcha", True)
        if exc.need_captcha:
            new_cid = secrets.token_hex(8)
            _pending.clear()
            _pending[new_cid] = client.session
            image = ""
            try:
                resp = client.session.get("https://cas-i.hnust.edu.cn/cas/captcha.jpg",
                                          timeout=15)
                if resp.ok:
                    image = "data:image/jpeg;base64," + base64.b64encode(resp.content).decode()
            except requests.RequestException:
                pass
            return fail(exc, cid=new_cid, captchaImage=image,
                        hint="登录失败次数较多，请输入图形验证码")
        return fail(exc)

    _pending.clear()
    state.update({
        "campus": campus if campus in CAMPUS else "main",
        "username": username,
        "token": result.token,
        "cookies": result.cookies,
        "kbjcmsid": "",
        "demo": False,
        "remember": bool(body.get("remember")),
        "password_sealed": _seal(password) if body.get("remember") else "",
    })
    _wcache.clear()
    try:
        client2 = _client()
        state["kbjcmsid"] = client2.get_kbjcmsid()
        state["sections"] = client2.get_sections_hint()
    except HnustError:
        pass
    save_state()

    # 顺手拉一次当前周，确认接口真的能出数据
    try:
        week = _fetch_week(None)
    except HnustError as exc:
        return ok({"warning": f"登录成功，但取课表失败：{exc.message}",
                   "campusName": CAMPUS[state["campus"]]["name"]})
    return ok({"week": week["week"], "currentWeek": week["currentWeek"],
               "campusName": CAMPUS[state["campus"]]["name"]})


@app.post("/api/logout")
async def api_logout():
    state.update({"token": "", "cookies": {}, "kbjcmsid": "", "username": "",
                  "password_sealed": "", "remember": False, "demo": False})
    state["term_start"] = ""
    _wcache.clear()
    save_state()
    return ok()


@app.post("/api/demo")
async def api_demo():
    state.update({"demo": True, "token": "", "cookies": {}})
    state["term_start"] = demo.term_start().isoformat()
    _wcache.clear()
    save_state()
    return ok({"week": _today_week()})


@app.get("/api/curriculum")
def api_curriculum(week: int | None = None, refresh: int = 0):
    try:
        data = _fetch_week(week, force=bool(refresh))
    except HnustError as exc:
        return fail(exc)
    return ok(data)


@app.get("/api/courses")
def api_courses(start: int = 1, end: int = 0, limit: int = 4):
    """
    分批汇总全学期课程。每次最多抓 limit 周，前端循环调用并展示进度，
    既不会一次打爆教务，也不用干等十几秒。
    """
    total = state["total_weeks"]
    end = max(1, min(total + 2, end or total))
    start = max(1, min(start, end))
    limit = max(1, min(10, limit))

    cur = start
    done = 0
    while cur <= end and done < limit:
        try:
            _fetch_week(cur)
        except HnustError as exc:
            return fail(exc, **_aggregate(end), scannedThrough=cur - 1,
                        next=cur, done=False, total=end)
        cur += 1
        done += 1

    return ok({**_aggregate(end), "scannedThrough": cur - 1,
               "next": cur, "done": cur > end, "total": end})


def _aggregate(end: int) -> dict:
    aggregated: dict[tuple, dict] = {}
    for week in range(1, end + 1):
        cached = _wcache.get(week)
        if not cached:
            continue
        for day in cached[1]["days"]:
            for course in day["courses"]:
                key = (course["name"], course["teacher"], course["location"])
                entry = aggregated.setdefault(key, {
                    "name": course["name"],
                    "teacher": course["teacher"],
                    "location": course["location"],
                    "slots": {},
                    "weeks": set(),
                })
                entry["slots"][(day["weekday"], course["start"], course["end"])] = True
                entry["weeks"].add(week)

    items = []
    for entry in aggregated.values():
        slots = [{"weekday": k[0], "start": k[1], "end": k[2]} for k in
                 sorted(entry["slots"],
                        key=lambda x: (WEEKDAY_ORDER.index(x[0])
                                       if x[0] in WEEKDAY_ORDER else 9, x[1]))]
        items.append({
            "name": entry["name"],
            "teacher": entry["teacher"],
            "location": entry["location"],
            "slots": slots,
            "weeks": sorted(entry["weeks"]),
            "count": len(entry["weeks"]),
        })
    items.sort(key=lambda x: (-x["count"], x["name"]))
    return {"courses": items}


@app.get("/api/logs")
def api_logs():
    """导出诊断日志：纯文本，已在写入时脱敏。"""
    return PlainTextResponse(logs.dump(), media_type="text/plain; charset=utf-8")


@app.get("/api/settings")
def api_settings():
    return ok({"totalWeeks": state["total_weeks"], "campus": state["campus"]})


@app.post("/api/settings")
async def api_settings_update(request: Request):
    body = await request.json()
    if "totalWeeks" in body:
        try:
            state["total_weeks"] = max(1, min(30, int(body["totalWeeks"])))
        except (TypeError, ValueError):
            return fail("总周次必须是数字")
    save_state()
    _wcache.clear()
    return ok({"totalWeeks": state["total_weeks"]})


# --------------------------------------------------------------------------- #
# 静态资源
# --------------------------------------------------------------------------- #
@app.get("/")
def index():
    return FileResponse(ROOT / "static" / "index.html")


@app.get("/manifest.webmanifest")
def manifest():
    return FileResponse(ROOT / "static" / "manifest.webmanifest",
                        media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker():
    return FileResponse(ROOT / "static" / "sw.js", media_type="application/javascript")


app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")


if __name__ == "__main__":
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8765"))
    uvicorn.run(app, host=host, port=port, log_level="info")
