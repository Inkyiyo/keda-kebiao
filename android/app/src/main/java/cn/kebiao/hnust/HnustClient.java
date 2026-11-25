package cn.kebiao.hnust;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 湖南科技大学「移动教务」客户端（Android 原生实现）。
 *
 * 链路与官方 App 一致：
 *   学号 + 密码 → CAS 统一认证（密码 RSA/PKCS#1 v1.5，带 __RSA__ 前缀）
 *   → 302 跳转链里取 token → POST /Get_sjkbms 取作息模式
 *   → POST /student/curriculum?week=&kbjcmsid= 取整周课表
 *
 * 本类只用 java.* 与 org.json，不依赖 Android SDK，便于在普通 JVM 上直接验证。
 */
public class HnustClient {

    public static String CAS = "https://cas-i.hnust.edu.cn";

    /** 只在联调/自测时指向本地 mock，正常运行时不要改。 */
    public static void setCasBase(String base) {
        if (base != null && !base.isEmpty()) CAS = base;
    }
    static final String UA = "Mozilla/5.0 (Linux; Android 13; PGT-AN10) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36";

    public static final String[][] CAMPUS = {
            {"main", "本部", "http://kdfw.hnust.edu.cn/bbxyhd", "http://kdfw.hnust.edu.cn/bbxyhd/loginSso?toMenu=xs_kcb"},
            {"xx", "潇湘学院", "http://xxfw.hnust.edu.cn/xxxyhd", "http://xxfw.hnust.edu.cn/xxxyhd/loginSso?toMenu=xs_kcb"},
    };

    public static final String[][] DEFAULT_SECTIONS = {
            {"08:00", "08:45"}, {"08:55", "09:40"}, {"10:00", "10:45"}, {"10:55", "11:40"},
            {"14:00", "14:45"}, {"14:55", "15:40"}, {"16:00", "16:45"}, {"16:55", "17:40"},
            {"19:00", "19:45"}, {"19:55", "20:40"}, {"20:50", "21:35"}, {"21:45", "22:30"},
    };

    public static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};

    /** 业务异常：kind 决定前端提示方式（auth / captcha / network / expired / server）。 */
    public static class ApiError extends Exception {
        public final String kind;
        public final boolean needCaptcha;
        public final String message;

        public ApiError(String message, String kind) {
            this(message, kind, false);
        }

        public ApiError(String message, String kind, boolean needCaptcha) {
            super(message);
            this.message = message == null ? "" : message;
            this.kind = kind;
            this.needCaptcha = needCaptcha;
        }
    }

    private final String campus;
    private final String base;
    private final String loginSso;
    public String token;
    /**
     * Cookie 按域名分罐存。cas-i 与教务站都用 JSESSIONID 这个名字，
     * v1.0.3 及以前共用一张全局表：两边的会话互相覆盖，每个请求还会
     * 把所有 cookie 不分域名全量发出——真账号登录被拒的重要嫌疑，
     * v1.0.4 改为按 host 隔离（与 Python 版 requests.Session 的行为一致）。
     */
    public final Map<String, Map<String, String>> cookies = new LinkedHashMap<>();

    /** 取某个域名自己的 cookie 罐（没有就建一个空的）。 */
    private Map<String, String> jarFor(String host) {
        Map<String, String> jar = cookies.get(host);
        if (jar == null) {
            jar = new LinkedHashMap<>();
            cookies.put(host, jar);
        }
        return jar;
    }

    public HnustClient(String campus) {
        String key = "main";
        for (String[] row : CAMPUS) {
            if (row[0].equals(campus)) key = row[0];
        }
        this.campus = key;
        String foundBase = CAMPUS[0][2], foundSso = CAMPUS[0][3], foundName = CAMPUS[0][1];
        for (String[] row : CAMPUS) {
            if (row[0].equals(key)) {
                foundName = row[1];
                foundBase = row[2];
                foundSso = row[3];
            }
        }
        this.campusName = foundName;
        this.base = foundBase;
        this.loginSso = foundSso;
    }

    public final String campusName;

    public String campusKey() {
        return campus;
    }

    public void restoreSession(String token, JSONObject storedCookies) {
        this.token = token;
        if (storedCookies != null) {
            for (java.util.Iterator<String> it = storedCookies.keys(); it.hasNext(); ) {
                String host = it.next();
                JSONObject jar = storedCookies.optJSONObject(host);
                // v1.0.3 及以前存的是 {名字: 值} 的平铺格式（不分域名），
                // 无法判断每条 cookie 属于哪个站，直接丢弃，重新登录一次即可。
                if (jar == null) continue;
                Map<String, String> mine = jarFor(host);
                for (java.util.Iterator<String> jt = jar.keys(); jt.hasNext(); ) {
                    String name = jt.next();
                    mine.put(name, jar.optString(name, ""));
                }
            }
        }
    }

    public JSONObject cookiesJson() {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Map<String, String>> e : cookies.entrySet()) {
            try {
                JSONObject jar = new JSONObject();
                for (Map.Entry<String, String> c : e.getValue().entrySet()) {
                    jar.put(c.getKey(), c.getValue());
                }
                obj.put(e.getKey(), jar);
            } catch (Exception ignored) {
            }
        }
        return obj;
    }

    // ------------------------------------------------------------------ //
    // HTTP
    // ------------------------------------------------------------------ //
    public static class Resp {
        public int code;
        public String body = "";
        public byte[] raw = new byte[0];
        public String location = "";
        public String url = "";
        public final Map<String, String> setCookies = new LinkedHashMap<>();

        boolean isRedirect() {
            return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
        }
    }

    /**
     * 学校证书链偶发缺中间证书，系统校验会直接失败。这里先走系统校验，
     * 只在系统校验不通过时放行（主机名校验仍由 HttpsURLConnection 完成）。
     */
    private static SSLContext relaxedContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                private final X509TrustManager def = defaultTrustManager();

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    def.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        def.checkServerTrusted(chain, authType);
                    } catch (CertificateException ignored) {
                        // 缺中间证书：系统信任库不认，但主机名仍会被验证
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return def.getAcceptedIssuers();
                }
            }}, null);
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }

    private static X509TrustManager defaultTrustManager() {
        try {
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
            }
        } catch (Exception ignored) {
        }
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static SSLContext sharedContext;

    private HttpURLConnection open(String method, String url, Map<String, String> headers, byte[] body,
                                   boolean follow) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(follow);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (conn instanceof HttpsURLConnection) {
            if (sharedContext == null) sharedContext = relaxedContext();
            if (sharedContext != null) ((HttpsURLConnection) conn).setSSLSocketFactory(sharedContext.getSocketFactory());
        }
        // 只带这个域名自己的 cookie，绝不把别的站的会话发过来
        Map<String, String> jar = cookies.get(new URL(url).getHost());
        if (jar != null && !jar.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : jar.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            conn.setRequestProperty("Cookie", sb.toString());
        }
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();
        }
        return conn;
    }

    private Resp execute(String method, String url, Map<String, String> headers, byte[] body,
                         boolean follow) throws ApiError {
        HttpURLConnection conn = null;
        long started = System.currentTimeMillis();
        try {
            conn = open(method, url, headers, body, follow);
            Resp resp = new Resp();
            resp.code = conn.getResponseCode();
            resp.url = conn.getURL().toString();
            resp.location = conn.getHeaderField("Location");
            if (resp.location == null) resp.location = "";
            Map<String, List<String>> fields = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String raw : e.getValue()) {
                    int eq = raw.indexOf('=');
                    int semi = raw.indexOf(';');
                    if (eq > 0) {
                        String name = raw.substring(0, eq).trim();
                        String value = raw.substring(eq + 1, semi > eq ? semi : raw.length()).trim();
                        resp.setCookies.put(name, value);
                        jarFor(new URL(url).getHost()).put(name, value);
                    }
                }
            }
            InputStream is = resp.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            resp.raw = is == null ? new byte[0] : readAll(is);
            resp.body = new String(resp.raw, StandardCharsets.UTF_8);
            Diag.http(method, url, resp.code, resp.raw.length, System.currentTimeMillis() - started);
            return resp;
        } catch (IOException e) {
            Diag.fail(method, url, e);
            throw new ApiError("网络请求失败：" + e.getMessage(), "network");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ //
    // 登录
    // ------------------------------------------------------------------ //
    private static String form(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String findExecution(String html) {
        Matcher m = Pattern.compile("name=\"execution\"\\s+value=\"([^\"]+)\"").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("value=\"([^\"]+)\"\\s+name=\"execution\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String loginAlert(String html) {
        String block = html;
        Matcher m = Pattern.compile("id=\"loginError1\"(.*?)</el-alert>", Pattern.DOTALL).matcher(html);
        if (m.find()) block = m.group(1);
        m = Pattern.compile("<el-alert[^>]*title=\"([^\"]+)\"").matcher(block);
        if (!m.find()) return null;
        String title = m.group(1).trim();
        while (title.endsWith("。") || title.endsWith(".")) title = title.substring(0, title.length() - 1);
        return title;
    }

    private static String errorText(String html) {
        String[] hints = {"用户名或密码错误", "密码错误", "用户名不存在", "用户不存在", "账号不存在",
                "账户被锁定", "账号已被锁定", "密码已过期", "验证码错误", "验证码不正确",
                "登录失败", "用户名不能为空", "密码不能为空"};
        for (String hint : hints) {
            if (html.contains(hint)) return hint;
        }
        Matcher m = Pattern.compile("(?:错误|异常|失败)[^<>{}\"]{0,40}").matcher(html);
        return m.find() ? m.group().trim() : null;
    }

    private static boolean captchaNeeded(String html, String message) {
        if (message != null && message.contains("验证码")) return true;
        int idx = html.lastIndexOf("id=\"loginError1\"");
        if (idx < 0) return false;
        String tail = html.substring(idx, Math.min(html.length(), idx + 2000));
        return tail.contains("captcha.jpg");
    }

    /** 从 URL 的 query 或 fragment 里取 token。 */
    static String extractToken(String url) {
        if (url == null || url.isEmpty()) return null;
        String qs = "";
        int hash = url.indexOf('#');
        if (hash >= 0) qs += "&" + url.substring(hash + 1);
        int q = url.indexOf('?');
        if (q >= 0) {
            int end = hash > q ? hash : url.length();
            qs += "&" + url.substring(q + 1, end);
        }
        for (String part : qs.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq);
            if (!key.equals("token") && !key.equals("Token")) continue;
            String value = urlDecode(part.substring(eq + 1)).trim();
            if (value.length() > 16) return value;
        }
        return null;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // 落地页不一定把 token 放在地址栏：正方/强智系常见「200 + 一小段 HTML/JSON，
    // 里面用 script 拼出带 token 的地址」。所以除了 URL，还要从响应体里找。
    private static final Pattern[] TOKEN_IN_TEXT = {
            Pattern.compile("[?&]token=([^&\\s\"'<>]{16,})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[\"']token[\"']\\s*[=:]\\s*[\"']([^\"']{16,})[\"']",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btoken\\s*[=:]\\s*([A-Za-z0-9\\-_.%+/=]{16,})",
                    Pattern.CASE_INSENSITIVE),
    };

    private static final Pattern[] SOFT_REDIRECT = {
            Pattern.compile("location\\s*\\.\\s*(?:href\\s*=|replace\\s*\\(\\s*)[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("window\\s*\\.\\s*location\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("http-equiv=[\"']refresh[\"'][^>]*?url=([^\"';]+)",
                    Pattern.CASE_INSENSITIVE),
    };

    private static final Pattern LONG_RUN = Pattern.compile("[A-Za-z0-9_\\-+/=%]{32,}");

    /** 从页面文本里捞 token（HTML/JSON/JS 都可能）。 */
    static String tokenFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        for (Pattern p : TOKEN_IN_TEXT) {
            Matcher m = p.matcher(text);
            if (!m.find()) continue;
            String v = urlDecode(m.group(1)).trim();
            if (v.length() > 16) return v;
        }
        return null;
    }

    /** 登录页本身也有 token 字样和 location 赋值，别把它当成落地页来解析。 */
    static boolean looksLikeCasLogin(String html) {
        return html != null && html.contains("name=\"execution\"");
    }

    /** 页面里的 JS / meta 跳转目标，用于跟随「200 的中间页」。 */
    static String softRedirect(String html) {
        if (html == null || html.isEmpty() || html.length() > 200000) return null;
        for (Pattern p : SOFT_REDIRECT) {
            Matcher m = p.matcher(html);
            if (!m.find()) continue;
            String t = m.group(1).trim();
            if (!t.isEmpty() && !t.toLowerCase().startsWith("javascript:") && !t.startsWith("#")) {
                return t;
            }
        }
        return null;
    }

    /** 把落地页压成一行写进日志；顺手把任何长串（疑似票据）打成 ***。 */
    static String snippet(String text) {
        if (text == null || text.trim().isEmpty()) return "(空)";
        String flat = LONG_RUN.matcher(text.replaceAll("\\s+", " ")).replaceAll("***").trim();
        return flat.length() > 260 ? flat.substring(0, 260) + "…" : flat;
    }

    /** token 及其来源（地址栏 / 跳转地址 / Cookie / 响应体）。 */
    private static final class Hit {
        final String token;
        final String source;

        Hit(String token, String source) {
            this.token = token;
            this.source = source;
        }
    }

    private Hit pickToken(Resp r) {
        String t = extractToken(r.url);
        if (t != null) return new Hit(t, "地址栏");
        t = extractToken(r.location);
        if (t != null) return new Hit(t, "跳转地址");
        for (Map.Entry<String, String> e : r.setCookies.entrySet()) {
            if (e.getKey().toLowerCase().contains("token") && e.getValue().length() > 16) {
                return new Hit(e.getValue().trim(), "Cookie:" + e.getKey());
            }
        }
        String body = r.body == null ? "" : r.body;
        if (body.length() <= 100000 && !looksLikeCasLogin(body)) {
            t = tokenFromText(body);
            if (t != null) return new Hit(t, "响应体");
        }
        return null;
    }

    /** 固定一个 32 位十六进制设备指纹，对应登录页里 FingerprintJS 生成的 visitorId。 */
    private String visitorId;

    private String visitorId() {
        if (visitorId == null || visitorId.length() != 32) {
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", rnd.nextInt(256)));
            visitorId = sb.toString();
        }
        return visitorId;
    }

    public void setVisitorId(String id) {
        if (id != null && id.length() == 32) visitorId = id;
    }

    /**
     * 单独用门户的 passwordLogin 复核账号密码。
     * 返回 TRUE=密码正确，FALSE=密码错误，null=判断不了。
     * 它与 CAS 表单共用同一把公钥、同一套加密，但返回结构化结果
     * （401 Bad credentials / 200 + idToken），能把「密码真的错」和
     * 「密码对但流程或风控挡住」彻底分开。
     */
    private Boolean verifyCredentials(String username, String encrypted) {
        try {
            String url = CAS + "/token/password/passwordLogin?username="
                    + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(encrypted, StandardCharsets.UTF_8);
            Resp r = execute("POST", url, null, new byte[0], true);
            if (r.code == 401) return Boolean.FALSE;
            if (r.code == 200) {
                String body = r.body == null ? "" : r.body;
                return body.contains("idToken") ? Boolean.TRUE : null;
            }
        } catch (ApiError ignored) {
        }
        return null;
    }

    public String login(String username, String password, String captcha) throws ApiError {
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;   // 不去空格：首尾空格也可能是密码的一部分
        if (username.isEmpty() || password.isEmpty()) throw new ApiError("请填写学号和密码", "auth");

        String service = URLEncoder.encode(loginSso, StandardCharsets.UTF_8);
        Resp page = execute("GET", CAS + "/cas/login?service=" + service, null, null, true);
        Diag.step("登录开始 campus=" + campus + " 学号=" + username);
        Diag.step("CAS 登录页 " + (page.body.contains("execution") ? "已加载" : "结构异常"));

        String existing = extractToken(page.url);
        if (existing != null) {
            token = existing;
            return token;
        }
        String execution = findExecution(page.body);
        if (execution == null) {
            Diag.error("登录页里找不到 execution 字段");
            throw new ApiError("统一认证页面结构异常，请稍后重试", "server");
        }
        Diag.step("拿到 execution（" + execution.length() + " 字符）");

        Resp keyResp = execute("GET", CAS + "/cas/jwt/publicKey", null, null, true);
        String pem = keyResp.body.trim();
        String base64Key = pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        String encrypted;
        try {
            byte[] der = Base64Codec.decode(base64Key);
            java.security.PublicKey pub = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pub);
            encrypted = "__RSA__" + Base64Codec.encode(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ApiError("统一认证公钥解析失败", "server");
        }

        Diag.step("密码已加密，密文 " + encrypted.length() + " 字符");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("password", encrypted);
        form.put("execution", execution);
        form.put("_eventId", "submit");
        form.put("currentMenu", "1");
        // 页面里的隐藏域初始是 -1，脚本加载后改成 localStorage 失败次数（首次为 0）。
        // 一直发 -1 等于声明「当前处于验证码强制状态」，是之前登录不稳的一个来源。
        form.put("failN", "0");
        form.put("mfaState", "");
        form.put("geolocation", "");
        form.put("fpVisitorId", visitorId());
        form.put("trustAgent", "");
        form.put("submit1", "Login1");
        form.put("captcha", captcha == null ? "" : captcha);

        // service 必须同时出现在 POST 的 URL 上：密码错的时候走不到签发票据那步，
        // 所以缺了它用错账号永远测不出来，只有密码正确时才会暴露出「未授权」。
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", CAS);
        headers.put("Referer", CAS + "/cas/login?service=" + service);
        Resp resp = execute("POST", CAS + "/cas/login?service=" + service, headers,
                form(form).getBytes(StandardCharsets.UTF_8), false);
        Diag.step("CAS 提交返回 " + resp.code
                + (resp.location.isEmpty() ? "（无跳转）" : "，开始跟随跳转"));

        // SSO 落地页有两种形态：地址栏带 token，或者 200 的中间页把 token 写在 HTML/JSON 里。
        // 旧实现只认前者，遇到后者就会把「已经登录成功」误判成「密码错误」。
        Hit hit = pickToken(resp);
        int hops = 0;
        Resp last = resp;
        while (hit == null && hops < 12) {
            String next = null;
            if (last.isRedirect()) {
                try {
                    next = last.location.startsWith("http") ? last.location
                            : new URL(new URL(last.url), last.location).toString();
                } catch (Exception bad) {
                    throw new ApiError("登录跳转地址异常", "network");
                }
            } else {
                if (looksLikeCasLogin(last.body)) break;
                String soft = softRedirect(last.body);
                if (soft != null) {
                    try {
                        next = new URL(new URL(last.url), soft).toString();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (next == null) break;
            hops++;
            Diag.info("SSO 第 " + hops + " 跳 " + next);
            last = execute("GET", next, null, null, false);
            hit = pickToken(last);
        }
        String found = hit == null ? null : hit.token;
        Diag.step(hit != null
                ? "取到教务 token（" + found.length() + " 字符），来源 " + hit.source
                        + "，跳转 " + hops + " 跳"
                : "未取到 token，跳转 " + hops + " 跳");
        if (found == null) {
            Diag.info("落地页 " + last.raw.length + "B 片段：" + snippet(last.body));
        }

        if (found == null) {
            String body = last.body.isEmpty() ? resp.body : last.body;
            String hint = loginAlert(body);
            if (hint == null) hint = errorText(body);
            Diag.error("登录被拒，页面提示：" + (hint == null ? "（未识别）" : hint)
                    + "，需验证码=" + captchaNeeded(body, hint));
            if (captchaNeeded(body, hint)) {
                throw new ApiError(hint == null ? "需要图形验证码" : hint, "captcha", true);
            }
            // CAS 返回 302 说明票据已经签发，也就是学号密码是对的。
            // 这时再报「密码错误」会把排查方向带偏，所以单独给一种说法。
            if (resp.code == 301 || resp.code == 302 || resp.code == 303 || resp.code == 307) {
                throw new ApiError("统一认证已通过（已签发票据），但教务系统没交出 token：落地页是 "
                        + last.raw.length + "B 的空页面。请把日志发给我，我按落地页结构适配", "sso");
            }
            if (Boolean.TRUE.equals(verifyCredentials(username, encrypted))) {
                // 用 blocked 而不是 auth：密码是对的，不该计入「失败 3 次弹验证码」的计数
                throw new ApiError("账号密码正确，但教务系统拒绝登录：可能已被连续失败触发验证码或临时锁定，"
                        + "请等 10 分钟再试", "blocked");
            }
            throw new ApiError(hint == null ? "登录失败，请检查学号与密码" : hint, "auth");
        }

        token = found;
        Diag.step("登录成功，token 已保存");
        // 落地一次，拿全教务站点的 Cookie（JSESSIONID）
        try {
            String land = last.url;
            if (last.isRedirect()) {
                land = last.location.startsWith("http") ? last.location
                        : new URL(new URL(last.url), last.location).toString();
            } else {
                String soft = softRedirect(last.body);
                if (soft != null) {
                    try {
                        land = new URL(new URL(last.url), soft).toString();
                    } catch (Exception ignored) {
                    }
                } else if (!last.url.contains("token=")) {
                    land = base + "/";
                }
            }
            execute("GET", land, null, null, true);
        } catch (ApiError | java.net.MalformedURLException ignored) {
        }
        return token;
    }

    /** 连续失败后会要求图形验证码，图片直接从 CAS 取。 */
    public byte[] fetchCaptcha() {
        try {
            Resp resp = execute("GET", CAS + "/cas/captcha.jpg", null, null, true);
            if (resp.code == 200 && resp.raw.length > 0) return resp.raw;
        } catch (ApiError ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    // 课表
    // ------------------------------------------------------------------ //
    private JSONObject api(String path, String query) throws ApiError {
        if (token == null || token.isEmpty()) throw new ApiError("尚未登录", "expired");
        String url = base + path + (query == null || query.isEmpty() ? "" : "?" + query);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("token", token);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Referer", base + "/");
        headers.put("X-Requested-With", "XMLHttpRequest");
        Resp resp = execute("POST", url, headers, new byte[0], true);

        if (resp.code == 401) throw new ApiError("登录状态已过期", "expired");
        String text = resp.body.trim();
        JSONObject payload;
        try {
            payload = new JSONObject(text);
        } catch (Exception e) {
            if (text.contains("非法访问") || text.startsWith("401")) {
                throw new ApiError("登录状态已过期", "expired");
            }
            throw new ApiError("教务系统返回了非预期内容", "server");
        }
        String code = payload.optString("code", "").trim();
        if (code.equals("401") || code.equals("-1") || code.equals("500")) {
            String msg = clean(payload.optString("Msg", payload.optString("msg", "")));
            if (code.equals("401") || msg.contains("未登录") || msg.contains("非法")) {
                throw new ApiError("登录状态已过期", "expired");
            }
            throw new ApiError(msg.isEmpty() ? "教务系统返回错误" : msg, "server");
        }
        return payload;
    }

    public String getKbjcmsid() throws ApiError {
        JSONObject payload = api("/Get_sjkbms", "");
        JSONArray modes = payload.optJSONArray("data");
        if (modes == null) {
            JSONObject single = payload.optJSONObject("data");
            modes = new JSONArray();
            if (single != null) modes.put(single);
        }
        String fallback = null;
        for (int i = 0; i < modes.length(); i++) {
            JSONObject mode = modes.optJSONObject(i);
            if (mode == null) continue;
            String id = mode.optString("kbjcmsid", mode.optString("kbjcmsId", ""));
            if (id.isEmpty()) continue;
            if (fallback == null) fallback = id;
            String mrms = mode.optString("mrms", "").trim();
            if (mrms.equals("1") || mrms.equalsIgnoreCase("true")) return id;
        }
        if (fallback != null) return fallback;
        throw new ApiError("未取到作息模式（kbjcmsid）", "server");
    }

    public JSONObject getCurriculum(Integer week, String kbjcmsid) throws ApiError {
        String q = "week=" + (week == null || week <= 0 ? "" : String.valueOf(week))
                + "&kbjcmsid=" + URLEncoder.encode(kbjcmsid == null ? "" : kbjcmsid, StandardCharsets.UTF_8);
        return api("/student/curriculum", q);
    }

    /** 尝试从 Get_sjkbms 里读节次时间，读不到用默认作息。 */
    public List<String[]> getSectionsHint() {
        List<String[]> result = new ArrayList<>();
        for (String[] row : DEFAULT_SECTIONS) result.add(new String[]{row[0], row[1]});
        try {
            JSONObject payload = api("/Get_sjkbms", "");
            Map<Integer, String[]> found = new LinkedHashMap<>();
            walkForSections(payload, found, 0);
            if (found.size() >= 8) {
                List<String[]> rows = new ArrayList<>();
                for (int i = 1; i <= 12; i++) {
                    rows.add(found.containsKey(i) ? found.get(i) : DEFAULT_SECTIONS[i - 1]);
                }
                return rows;
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void walkForSections(Object node, Map<Integer, String[]> found, int depth) {
        if (depth > 6 || found.size() >= 12) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String idx = obj.optString("jc", obj.optString("jcmc", obj.optString("section", "")));
            String start = obj.optString("kssj", obj.optString("startTime", obj.optString("sksj", "")));
            String end = obj.optString("jssj", obj.optString("endTime", ""));
            try {
                int n = Integer.parseInt(idx.trim());
                if (n > 0 && !start.isEmpty() && !end.isEmpty()
                        && start.matches("^\\d{1,2}:\\d{2}.*") && !found.containsKey(n)) {
                    found.put(n, new String[]{start.substring(0, 5), end.substring(0, 5)});
                }
            } catch (Exception ignored) {
            }
            for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
                walkForSections(obj.opt(it.next()), found, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) walkForSections(arr.opt(i), found, depth + 1);
        }
    }

    // ------------------------------------------------------------------ //
    // 结构化
    // ------------------------------------------------------------------ //
    static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /** classTime 是紧凑数字串，两两切片可拿到起止节次。 */
    static int[] parseSections(String value) {
        if (value == null || value.isEmpty()) return null;
        String digits = value.replaceAll("\\D", "");
        List<Integer> nums = new ArrayList<>();
        for (int i = 0; i + 1 < digits.length(); i += 2) {
            try {
                int n = Integer.parseInt(digits.substring(i, i + 2));
                if (n >= 1 && n <= 20) nums.add(n);
            } catch (NumberFormatException ignored) {
            }
        }
        if (!nums.isEmpty()) {
            int min = 99, max = 0;
            for (int n : nums) {
                min = Math.min(min, n);
                max = Math.max(max, n);
            }
            return new int[]{min, max};
        }
        Matcher range = Pattern.compile("(\\d+)\\s*[-~至]\\s*(\\d+)").matcher(value);
        if (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            if (a >= 1 && a <= 20 && b >= 1 && b <= 20) return new int[]{Math.min(a, b), Math.max(a, b)};
        }
        Matcher single = Pattern.compile("(\\d+)").matcher(value);
        if (single.find()) {
            int n = Integer.parseInt(single.group(1));
            if (n >= 1 && n <= 20) return new int[]{n, n};
        }
        return null;
    }

    /** 把教务返回的原始课表整理成前端要的结构。 */
    public static JSONObject normalize(JSONObject payload, Integer wantedWeek) throws ApiError {
        try {
            return normalizeInner(payload, wantedWeek);
        } catch (org.json.JSONException e) {
            throw new ApiError("课表数据解析失败", "server");
        }
    }

    private static JSONObject normalizeInner(JSONObject payload, Integer wantedWeek)
            throws ApiError, org.json.JSONException {
        Object data = payload.opt("data");
        JSONArray blocks = null;
        if (data instanceof JSONArray) blocks = (JSONArray) data;
        else if (data instanceof JSONObject) {
            blocks = new JSONArray();
            blocks.put(data);
        }
        if (blocks == null || blocks.length() == 0) {
            throw new ApiError("教务系统没有返回课表数据", "server");
        }
        JSONObject block = blocks.optJSONObject(0);
        if (block == null) block = new JSONObject();

        Integer week = wantedWeek;
        String rawWeek = block.optString("week", "");
        try {
            if (!rawWeek.isEmpty()) week = (int) Double.parseDouble(rawWeek.replace(".0", ""));
        } catch (Exception ignored) {
        }
        if (week == null) week = 1;

        JSONArray dates = block.optJSONArray("date");
        JSONArray items = block.optJSONArray("item");
        int dayCount = dates != null ? dates.length() : 7;
        if (dayCount < 7) dayCount = 7;

        JSONArray days = new JSONArray();
        for (int i = 0; i < dayCount; i++) {
            JSONObject dateInfo = dates != null && i < dates.length() ? dates.optJSONObject(i) : null;
            if (dateInfo == null) dateInfo = new JSONObject();
            JSONArray rawCourses = items != null && i < items.length() ? items.optJSONArray(i) : null;
            List<JSONObject> parsed = new ArrayList<>();
            if (rawCourses != null) {
                for (int j = 0; j < rawCourses.length(); j++) {
                    JSONObject raw = rawCourses.optJSONObject(j);
                    if (raw == null) continue;
                    String name = clean(raw.optString("courseName", raw.optString("course_name", "")));
                    if (name.isEmpty()) continue;
                    int[] rng = parseSections(raw.optString("classTime", ""));
                    if (rng == null) continue;
                    JSONObject course = new JSONObject();
                    course.put("name", name);
                    course.put("teacher", clean(raw.optString("teacherName", raw.optString("teacher", ""))));
                    course.put("location", clean(raw.optString("location", raw.optString("classRoom", ""))));
                    course.put("start", rng[0]);
                    course.put("end", rng[1]);
                    course.put("weeks", clean(raw.optString("classWeek", raw.optString("weeks", ""))));
                    parsed.add(course);
                }
            }
            java.util.Collections.sort(parsed, (a, b) -> {
                int sa = a.optInt("start"), sb = b.optInt("start");
                if (sa != sb) return Integer.compare(sa, sb);
                return a.optString("name").compareTo(b.optString("name"));
            });
            String weekday = clean(dateInfo.optString("xqmc", ""));
            if (weekday.isEmpty()) weekday = i < 7 ? WEEKDAYS[i] : "";

            JSONObject day = new JSONObject();
            day.put("date", clean(dateInfo.optString("mxrq", "")));
            day.put("weekday", weekday);
            day.put("courses", mergeAdjacent(parsed));
            days.put(day);
        }

        JSONObject result = new JSONObject();
        result.put("week", week);
        result.put("days", days);
        return result;
    }

    /** 同名同地点、节次相连的课合并成一整块。 */
    static JSONArray mergeAdjacent(List<JSONObject> courses) throws org.json.JSONException {
        List<JSONObject> merged = new ArrayList<>();
        for (JSONObject course : courses) {
            if (!merged.isEmpty()) {
                JSONObject last = merged.get(merged.size() - 1);
                boolean same = last.optString("name").equals(course.optString("name"))
                        && last.optString("location").equals(course.optString("location"))
                        && course.optInt("start") <= last.optInt("end") + 1;
                if (same) {
                    last.put("end", Math.max(last.optInt("end"), course.optInt("end")));
                    String teacher = course.optString("teacher");
                    if (!teacher.isEmpty() && !last.optString("teacher").contains(teacher)) {
                        String joined = (last.optString("teacher") + " / " + teacher).replaceAll("^ / | / $", "");
                        last.put("teacher", joined);
                    }
                    continue;
                }
            }
            try {
                merged.add(new JSONObject(course.toString()));
            } catch (Exception ignored) {
            }
        }
        JSONArray arr = new JSONArray();
        for (JSONObject obj : merged) arr.put(obj);
        return arr;
    }
}
