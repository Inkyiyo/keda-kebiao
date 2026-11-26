package cn.kebiao.hnust;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把网页版的 /api/* 接口在本机重做一遍。
 * 前端不知道自己在跟谁说话——它发的还是 fetch("/api/xxx")，由注入的 shim 转到 native。
 */
class Api {

    private static final long TTL_MS = 240_000L;

    private static Api instance;

    static synchronized Api get(Context ctx) {
        if (instance == null) instance = new Api(ctx.getApplicationContext());
        return instance;
    }

    private final Store store;
    private final Context appCtx;
    private final Map<Integer, long[]> stamps = new HashMap<>();
    private final Map<Integer, JSONObject> weeks = new HashMap<>();
    private final Map<String, HnustClient> pending = new HashMap<>();
    private int loginFails = 0;
    private final long startedAt = System.currentTimeMillis();

    private Api(Context ctx) {
        appCtx = ctx;
        store = new Store(ctx);
    }

    // ------------------------------------------------------------------ //
    // 入口
    // ------------------------------------------------------------------ //
    String handle(String url, String bodyText) {
        String route = url;
        String query = "";
        int q = url.indexOf('?');
        if (q >= 0) {
            route = url.substring(0, q);
            query = url.substring(q + 1);
        }
        Map<String, String> params = parseQuery(query);
        JSONObject body;
        try {
            body = bodyText == null || bodyText.trim().isEmpty() ? new JSONObject() : new JSONObject(bodyText);
        } catch (Exception e) {
            body = new JSONObject();
        }

        try {
            switch (route) {
                case "/api/status":
                    return status().toString();
                case "/api/login":
                    return login(body);
                case "/api/logout":
                    return logout().toString();
                case "/api/demo":
                    return demo().toString();
                case "/api/curriculum":
                    return curriculum(params).toString();
                case "/api/courses":
                    return courses(params).toString();
                case "/api/settings":
                    return settings(body).toString();
                case "/api/open-exact-alarms":
                    return openExactAlarmSettings().toString();
                default:
                    return fail("接口不存在：" + route, "error").toString();
            }
        } catch (HnustClient.ApiError e) {
            Diag.error("接口 " + route + " 失败 [" + e.kind + "] " + e.message);
            return fail(e.message, e.kind).toString();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Diag.fail("api " + route, route, e);
            return fail("出了点问题：" + msg, "error").toString();
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq),
                        java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static JSONObject ok() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", true);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private static JSONObject fail(String message, String kind) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", false);
            obj.put("error", message);
            obj.put("kind", kind);
        } catch (Exception ignored) {
        }
        return obj;
    }

    // ------------------------------------------------------------------ //
    // 状态 / 登录
    // ------------------------------------------------------------------ //
    private JSONObject status() {
        JSONObject obj = ok();
        try {
            obj.put("loggedIn", store.loggedIn());
            obj.put("demo", store.demo());
            obj.put("username", store.username());
            obj.put("campus", store.campus());
            obj.put("campusName", campusName(store.campus()));
            obj.put("remember", store.remember());
            obj.put("hasPassword", store.hasPassword());
            obj.put("totalWeeks", store.totalWeeks());
            obj.put("currentWeek", todayWeek());
            obj.put("uptime", (System.currentTimeMillis() - startedAt) / 1000);
            putAlarmInfo(obj);
        } catch (Exception ignored) {
        }
        return obj;
    }

    /** 闹钟开关状态 + 下一次触发说明，供「我的」页展示。 */
    private void putAlarmInfo(JSONObject obj) {
        try {
            obj.put("wakeAlarm", store.wakeAlarm());
            obj.put("remindAlarm", store.remindAlarm());
            String nextWake = "";
            String nextRemind = "";
            if (store.loggedIn()) {
                if (store.wakeAlarm()) {
                    nextWake = ClassAlarms.describe(ClassAlarms.findNext(store, true));
                }
                if (store.remindAlarm()) {
                    nextRemind = ClassAlarms.describe(ClassAlarms.findNext(store, false));
                }
            }
            obj.put("nextWake", nextWake);
            obj.put("nextRemind", nextRemind);
            obj.put("exactAlarm", WidgetClock.exactAllowed(appCtx));
        } catch (Exception ignored) {
        }
    }

    /** 打开系统的「闹钟和提醒」授权页（精确闹钟权限），小组件实时刷新要用。 */
    private JSONObject openExactAlarmSettings() {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:" + appCtx.getPackageName()));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(intent);
        } catch (Exception e) {
            try {
                android.content.Intent fallback = new android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + appCtx.getPackageName()));
                fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                appCtx.startActivity(fallback);
            } catch (Exception ignored) {
            }
        }
        return ok();
    }

    static String campusName(String key) {
        for (String[] row : HnustClient.CAMPUS) {
            if (row[0].equals(key)) return row[1];
        }
        return HnustClient.CAMPUS[0][1];
    }

    private String login(JSONObject body) {
        String username = body.optString("username", "").trim();
        String password = body.optString("password", "");
        String campus = body.optString("campus", "main");
        boolean remember = body.optBoolean("remember", false);
        String captcha = body.optString("captcha", "");
        if (captcha.isEmpty() || captcha.equals("null")) captcha = null;

        String cid = body.optString("cid", "");
        HnustClient client = null;
        if (!cid.isEmpty()) client = pending.get(cid);
        if (client == null) {
            pending.clear();
            client = new HnustClient(campus);
            client.setVisitorId(store.visitorId());
        }

        try {
            client.login(username, password, captcha);
            loginFails = 0;
        } catch (HnustClient.ApiError e) {
            if ("auth".equals(e.kind)) loginFails += 1;
            HnustClient.ApiError error = e;
            if ("auth".equals(e.kind) && loginFails >= 3 && captcha == null) {
                error = new HnustClient.ApiError("登录失败 3 次，请输入图形验证码后重试", "captcha", true);
            }
            if (error.needCaptcha) {
                String newCid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                pending.clear();
                pending.put(newCid, client);
                String image = "";
                byte[] raw = client.fetchCaptcha();
                if (raw != null && raw.length > 0) {
                    image = "data:image/jpeg;base64," + Base64.encodeToString(raw, Base64.NO_WRAP);
                }
                JSONObject out = fail(error.message, error.kind);
                try {
                    out.put("needCaptcha", true);
                    out.put("cid", newCid);
                    out.put("captchaImage", image);
                    out.put("hint", "登录失败次数较多，请输入图形验证码");
                } catch (Exception ignored) {
                }
                return out.toString();
            }
            return fail(error.message, error.kind).toString();
        }

        pending.clear();
        store.setCampus(campus);
        store.setPassword(password, remember);
        store.saveSession(username, client.token, client.cookiesJson(), "", null);
        cacheClear();

        String kbjcmsid = "";
        List<String[]> sections = null;
        try {
            kbjcmsid = client.getKbjcmsid();
            sections = client.getSectionsHint();
        } catch (HnustClient.ApiError ignored) {
        }
        store.saveSession(username, client.token, client.cookiesJson(), kbjcmsid, sections);

        JSONObject obj = ok();
        try {
            obj.put("campusName", campusName(campus));
            JSONObject week = fetchWeek(null, false);
            obj.put("week", week.optInt("week"));
            obj.put("currentWeek", week.optInt("currentWeek"));
        } catch (HnustClient.ApiError e) {
            try {
                obj.put("warning", "登录成功，但取课表失败：" + e.message);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        return obj.toString();
    }

    private JSONObject logout() {
        store.clearSession(false);
        cacheClear();
        return ok();
    }

    private JSONObject demo() {
        JSONObject obj = ok();
        try {
            store.setDemo(true);
            store.setToken("");
            store.setTermStart(demoStart().toString());
            cacheClear();
            obj.put("week", todayWeek());
        } catch (Exception ignored) {
        }
        return obj;
    }

    // ------------------------------------------------------------------ //
    // 课表
    // ------------------------------------------------------------------ //
    private boolean relogin() {
        String password = store.password();
        if (store.username().isEmpty() || password.isEmpty()) return false;
        try {
            HnustClient client = new HnustClient(store.campus());
            client.login(store.username(), password, null);
            store.saveToken(client.token, client.cookiesJson());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject fetchWeek(Integer week, boolean force) throws HnustClient.ApiError {
        int key = week == null ? 0 : week;
        Long stamp = stamps.containsKey(key) ? stamps.get(key)[0] : null;
        if (!force && stamp != null && System.currentTimeMillis() - stamp < TTL_MS && weeks.containsKey(key)) {
            return weeks.get(key);
        }

        if (store.demo()) {
            JSONObject data = demoPayload(week);
            cachePut(key, data, 0);
            return data;
        }

        // 本地缓存先行：内存里有过期副本、或磁盘上有上次的整周数据，
        // 先原样返回并标 stale，前端渲染后会静默发一次强制刷新校对。
        if (!force) {
            if (weeks.containsKey(key)) {
                try {
                    JSONObject copy = new JSONObject(weeks.get(key).toString());
                    copy.put("stale", true);
                    return copy;
                } catch (Exception ignored) {
                }
            }
            int pkey = key != 0 ? key : store.lastCachedWeek();
            if (pkey > 0) {
                String json = store.weekCache(pkey);
                if (!json.isEmpty()) {
                    try {
                        JSONObject cached = new JSONObject(json);
                        // 课程页的聚合统计读的是内存表：缓存命中也要入表，
                        // 否则统计只剩本次联网取过的那一周（v1.0.8 的 bug）。
                        // stamp=0 保持「已过期」，不影响前端的静默校对。
                        cachePut(pkey, new JSONObject(json), 0);
                        cached.put("stale", true);
                        Diag.step("课表 week=" + pkey + " 命中本地缓存，先显示后校对");
                        return cached;
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        JSONObject data = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            HnustClient client = new HnustClient(store.campus());
            client.restoreSession(store.token(), store.cookies());
            try {
                String kbjcmsid = store.kbjcmsid();
                if (kbjcmsid.isEmpty()) {
                    kbjcmsid = client.getKbjcmsid();
                    List<String[]> sections = client.getSectionsHint();
                    store.saveSession(store.username(), store.token(), client.cookiesJson(), kbjcmsid, sections);
                }
                data = HnustClient.normalize(client.getCurriculum(week, kbjcmsid), week);
                break;
            } catch (HnustClient.ApiError e) {
                if (("expired".equals(e.kind) || "network".equals(e.kind)) && attempt == 1 && relogin()) {
                    continue;
                }
                if ("expired".equals(e.kind)) store.clearSession(true);
                throw e;
            }
        }
        if (data == null) throw new HnustClient.ApiError("取课表失败", "server");

        // 没指定周时，用返回的日期反推学期第一周，用于显示每列日期
        if (week == null) {
            int realWeek = data.optInt("week", 1);
            JSONArray days = data.optJSONArray("days");
            String first = "";
            if (days != null && days.length() > 0 && days.optJSONObject(0) != null) {
                first = days.optJSONObject(0).optString("date", "");
            }
            if (!first.isEmpty() && realWeek > 0) {
                try {
                    LocalDate monday = LocalDate.parse(first).minusDays(7L * (realWeek - 1));
                    store.setTermStart(monday.toString());
                } catch (Exception ignored) {
                }
            }
        }

        JSONObject shaped = shape(data, data.optInt("week", week == null ? todayWeek() : week));
        long now = System.currentTimeMillis();
        cachePut(key, shaped, now);
        int real = data.optInt("week", 0);
        if (real > 0 && real != key) cachePut(real, shaped, now);
        // 落盘：下次打开先显示这份缓存，再静默校对
        int persistKey = real > 0 ? real : key;
        if (persistKey > 0) {
            store.putWeekCache(persistKey, shaped.toString());
            store.setLastCachedWeek(persistKey);
        }
        return shaped;
    }

    private void cachePut(int key, JSONObject data, long stamp) {
        weeks.put(key, data);
        stamps.put(key, new long[]{stamp});
    }

    private void cacheClear() {
        weeks.clear();
        stamps.clear();
        store.clearWeekCaches();
    }

    private JSONObject shape(JSONObject data, int week) {
        JSONObject obj = ok();
        try {
            JSONArray daysIn = data.optJSONArray("days");
            JSONArray days = new JSONArray();
            List<String> dates = weekDates(week);
            String today = LocalDate.now().toString();
            boolean anyCourse = false;
            int count = daysIn == null ? 0 : daysIn.length();
            for (int i = 0; i < Math.max(count, 7); i++) {
                JSONObject src = count > i ? daysIn.optJSONObject(i) : null;
                if (src == null) src = new JSONObject();
                String date = src.optString("date", "");
                if (date.isEmpty() && i < dates.size()) date = dates.get(i);
                String weekday = src.optString("weekday", "");
                if (weekday.isEmpty()) weekday = i < 7 ? HnustClient.WEEKDAYS[i] : "";

                JSONObject day = new JSONObject();
                day.put("date", date);
                day.put("weekday", weekday);
                day.put("today", date.equals(today));
                JSONArray courses = src.optJSONArray("courses");
                if (courses == null) courses = new JSONArray();
                if (courses.length() > 0) anyCourse = true;
                day.put("courses", courses);
                days.put(day);
            }

            List<String[]> rows = store.sections();
            JSONArray sections = new JSONArray();
            for (int i = 0; i < Math.min(12, rows.size()); i++) {
                JSONObject item = new JSONObject();
                item.put("index", i + 1);
                item.put("start", rows.get(i)[0]);
                item.put("end", rows.get(i)[1]);
                sections.put(item);
            }

            obj.put("week", week);
            obj.put("currentWeek", todayWeek());
            obj.put("termStart", store.termStart());
            obj.put("totalWeeks", store.totalWeeks());
            obj.put("sections", sections);
            obj.put("days", days);
            obj.put("empty", !anyCourse);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private JSONObject curriculum(Map<String, String> params) throws HnustClient.ApiError {
        Integer week = null;
        String raw = params.get("week");
        if (raw != null && !raw.isEmpty()) {
            try {
                int n = Integer.parseInt(raw);
                if (n > 0) week = n;
            } catch (NumberFormatException ignored) {
            }
        }
        boolean force = "1".equals(params.get("refresh"));
        JSONObject result = fetchWeek(week, force);
        // 每次拿到课表都顺手重排一次上课闹钟、刷新桌面小组件（本地计算，很便宜）
        ClassAlarms.reschedule(appCtx);
        Widgets.refreshAll(appCtx);
        // 后台把整学期课表全部预缓存，之后翻任何一周都不用等网络
        warmAllWeeks();
        return result;
    }

    private final java.util.concurrent.atomic.AtomicBoolean warming =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 后台把第 1 周到最后一周的课表全部拉下来落盘（已缓存的跳过），只跑一遍。 */
    private void warmAllWeeks() {
        if (!store.loggedIn() || store.demo()) return;
        if (!warming.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                int total = store.totalWeeks();
                int fetched = 0;
                for (int w = 1; w <= total; w++) {
                    if (!store.weekCache(w).isEmpty()) continue;
                    try {
                        fetchWeek(w, false);
                        fetched++;
                        Thread.sleep(120);
                    } catch (HnustClient.ApiError e) {
                        if ("expired".equals(e.kind)) break;
                    } catch (Exception ignored) {
                    }
                }
                if (fetched > 0) {
                    Diag.step("整学期课表预缓存完成，新拉 " + fetched + " 周");
                    ClassAlarms.reschedule(appCtx);
                    Widgets.refreshAll(appCtx);
                }
            } finally {
                warming.set(false);
            }
        }, "warm-weeks");
        thread.setDaemon(true);
        thread.start();
    }

    private JSONObject courses(Map<String, String> params) {
        int total = store.totalWeeks();
        int start = parseInt(params.get("start"), 1);
        int limit = Math.max(1, Math.min(10, parseInt(params.get("limit"), 4)));
        int end = parseInt(params.get("end"), 0);
        if (end <= 0) end = total;
        end = Math.max(1, Math.min(total + 2, end));
        start = Math.max(1, Math.min(start, end));

        int cur = start;
        int done = 0;
        while (cur <= end && done < limit) {
            try {
                fetchWeek(cur, false);
            } catch (HnustClient.ApiError e) {
                JSONObject out = fail(e.message, e.kind);
                mergeAggregate(out, end);
                try {
                    out.put("scannedThrough", cur - 1);
                    out.put("next", cur);
                    out.put("done", false);
                    out.put("total", end);
                } catch (Exception ignored) {
                }
                return out;
            }
            cur += 1;
            done += 1;
        }

        JSONObject out = ok();
        mergeAggregate(out, end);
        try {
            out.put("scannedThrough", cur - 1);
            out.put("next", cur);
            out.put("done", cur > end);
            out.put("total", end);
        } catch (Exception ignored) {
        }
        return out;
    }

    private void mergeAggregate(JSONObject out, int end) {
        // 按课程名聚合：同名课哪怕老师、教室不同也合成一张卡，
        // 每种上课安排（星期+节次+老师+教室）记成一个 slot，各自带出现周数；
        // 同时统计总节数与已上节数（按日期 + 末节下课时间判断）。
        Map<String, JSONObject> byName = new LinkedHashMap<>();
        Map<String, Map<String, JSONObject>> slotMaps = new HashMap<>();
        Map<String, List<String>> teacherLists = new HashMap<>();
        Map<String, List<Integer>> weekList = new HashMap<>();
        Map<String, int[]> totals = new HashMap<>();
        List<String[]> sectionRows = store.sections();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        for (int week = 1; week <= end; week++) {
            JSONObject data = weeks.get(week);
            if (data == null) continue;
            JSONArray days = data.optJSONArray("days");
            if (days == null) continue;
            for (int i = 0; i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                if (day == null) continue;
                JSONArray list = day.optJSONArray("courses");
                if (list == null) continue;
                String date = day.optString("date", "");
                for (int j = 0; j < list.length(); j++) {
                    JSONObject course = list.optJSONObject(j);
                    if (course == null) continue;
                    String name = course.optString("name");
                    if (name.isEmpty()) continue;
                    String teacher = course.optString("teacher");
                    String location = course.optString("location");
                    int startSec = course.optInt("start");
                    int endSec = course.optInt("end");
                    int span = Math.max(1, endSec - startSec + 1);

                    JSONObject item = byName.get(name);
                    if (item == null) {
                        item = new JSONObject();
                        try {
                            item.put("name", name);
                        } catch (Exception ignored) {
                        }
                        byName.put(name, item);
                        slotMaps.put(name, new LinkedHashMap<>());
                        teacherLists.put(name, new ArrayList<>());
                        weekList.put(name, new ArrayList<>());
                        totals.put(name, new int[]{0, 0});
                    }
                    List<String> teachers = teacherLists.get(name);
                    if (!teacher.isEmpty() && !teachers.contains(teacher)) teachers.add(teacher);
                    List<Integer> ws = weekList.get(name);
                    if (!ws.contains(week)) ws.add(week);

                    String slotKey = day.optString("weekday") + "|" + startSec + "|" + endSec
                            + "|" + teacher + "|" + location;
                    Map<String, JSONObject> slotMap = slotMaps.get(name);
                    JSONObject slot = slotMap.get(slotKey);
                    if (slot == null) {
                        slot = new JSONObject();
                        try {
                            slot.put("weekday", day.optString("weekday"));
                            slot.put("start", startSec);
                            slot.put("end", endSec);
                            slot.put("teacher", teacher);
                            slot.put("location", location);
                            slot.put("weeks", 0);
                        } catch (Exception ignored) {
                        }
                        slotMap.put(slotKey, slot);
                    }
                    try {
                        slot.put("weeks", slot.optInt("weeks") + 1);
                    } catch (Exception ignored) {
                    }

                    int[] tot = totals.get(name);
                    tot[0] += span;
                    if (isDone(date, endSec, sectionRows, now)) tot[1] += span;
                }
            }
        }

        List<JSONObject> items = new ArrayList<>(byName.values());
        for (JSONObject item : items) {
            String name = item.optString("name");
            try {
                JSONArray tArr = new JSONArray();
                for (String t : teacherLists.get(name)) tArr.put(t);
                item.put("teachers", tArr);
                JSONArray sArr = new JSONArray();
                for (JSONObject s : slotMaps.get(name).values()) sArr.put(s);
                item.put("slots", sArr);
                List<Integer> ws = weekList.get(name);
                java.util.Collections.sort(ws);
                JSONArray wArr = new JSONArray();
                for (Integer w : ws) wArr.put(w);
                item.put("weeks", wArr);
                item.put("count", ws.size());
                int[] tot = totals.get(name);
                item.put("totalSections", tot[0]);
                item.put("doneSections", tot[1]);
            } catch (Exception ignored) {
            }
        }
        java.util.Collections.sort(items, (a, b) -> {
            int ca = a.optInt("count"), cb = b.optInt("count");
            if (ca != cb) return Integer.compare(cb, ca);
            return a.optString("name").compareTo(b.optString("name"));
        });
        JSONArray arr = new JSONArray();
        for (JSONObject item : items) arr.put(item);
        try {
            out.put("courses", arr);
        } catch (Exception ignored) {
        }
    }

    /** 这次课上完了没有：日期已过，或当天且末节下课时间已过。 */
    private boolean isDone(String date, int endSection, List<String[]> sectionRows,
                           java.time.LocalDateTime now) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(date);
            if (d.isBefore(now.toLocalDate())) return true;
            if (d.isAfter(now.toLocalDate())) return false;
            if (endSection >= 1 && endSection <= sectionRows.size()) {
                java.time.LocalTime t = java.time.LocalTime.parse(sectionRows.get(endSection - 1)[1]);
                return t.isBefore(now.toLocalTime());
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject settings(JSONObject body) {
        if (body.has("totalWeeks")) {
            try {
                store.setTotalWeeks(body.getInt("totalWeeks"));
                cacheClear();
            } catch (Exception e) {
                return fail("总周次必须是数字", "error");
            }
        }
        if (body.has("wakeAlarm")) {
            store.setWakeAlarm(body.optBoolean("wakeAlarm", true));
            ClassAlarms.reschedule(appCtx);
        }
        if (body.has("remindAlarm")) {
            store.setRemindAlarm(body.optBoolean("remindAlarm", true));
            ClassAlarms.reschedule(appCtx);
        }
        JSONObject obj = ok();
        try {
            obj.put("totalWeeks", store.totalWeeks());
            obj.put("campus", store.campus());
            putAlarmInfo(obj);
        } catch (Exception ignored) {
        }
        return obj;
    }

    // ------------------------------------------------------------------ //
    // 时间
    // ------------------------------------------------------------------ //
    private int todayWeek() {
        LocalDate start = termStart();
        if (start == null) return 1;
        long days = ChronoUnit.DAYS.between(start, LocalDate.now());
        int week = (int) (days / 7) + 1;
        return Math.max(1, Math.min(store.totalWeeks(), week));
    }

    private LocalDate termStart() {
        String value = store.termStart();
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> weekDates(int week) {
        List<String> out = new ArrayList<>();
        LocalDate start = termStart();
        if (start == null) {
            for (int i = 0; i < 7; i++) out.add("");
            return out;
        }
        LocalDate monday = start.plusWeeks(week - 1L);
        for (int i = 0; i < 7; i++) out.add(monday.plusDays(i).toString());
        return out;
    }

    // ------------------------------------------------------------------ //
    // 演示数据
    // ------------------------------------------------------------------ //
    private static final Object[][] DEMO = {
            {"高等数学 A(2)", "刘建平", "十教 205", 1, 3, 4, "1-16"},
            {"数据结构", "陈 立", "八教 302", 1, 5, 6, "1-16"},
            {"形势与政策(四)", "何 嘉", "七教 102", 1, 9, 10, "1-8"},
            {"大学物理 B(1)", "王慧敏", "九教 101", 2, 1, 2, "1-16"},
            {"离散数学", "赵明月", "十教 401", 2, 3, 4, "1-16"},
            {"计算机网络", "罗 毅", "八教 405", 2, 7, 8, "1-16"},
            {"高等数学 A(2)", "刘建平", "十教 205", 3, 1, 2, "1-16"},
            {"大学英语(三)", "李 静", "外语楼 208", 3, 5, 6, "1-16"},
            {"大学生心理健康教育", "邓 琳", "十教 306", 3, 9, 10, "2-14|双"},
            {"数字逻辑", "孙 浩", "九教 207", 4, 1, 2, "1-16"},
            {"数据结构", "陈 立", "八教 302", 4, 3, 4, "1-16"},
            {"大学物理 B(1)", "王慧敏", "九教 101", 4, 5, 6, "1-16"},
            {"军事理论(线上)", "张 岩", "", 4, 9, 10, "1-8"},
            {"中国近现代史纲要", "何 嘉", "七教 101", 5, 1, 2, "1-12"},
            {"体育(三)", "周 强", "第二田径场", 5, 3, 4, "1-16"},
            {"大学英语(三)", "李 静", "外语楼 208", 5, 5, 6, "1-16"},
            {"数据结构实验", "陈 立", "计算中心 B302", 5, 7, 8, "3-15|单"},
            {"程序设计实践", "陈 立", "计算中心 A201", 6, 1, 4, "5-18"},
            {"认识实习", "罗 毅", "八教 报告厅", 7, 5, 6, "17-18"},
    };

    /** 演示学期固定让「今天」落在第 12 周，随时打开都有满屏课程。 */
    private LocalDate demoStart() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        return monday.minusWeeks(11);
    }

    private static boolean inWeeks(String rule, int week) {
        String rng = rule;
        String flag = "";
        int bar = rule.indexOf('|');
        if (bar >= 0) {
            rng = rule.substring(0, bar);
            flag = rule.substring(bar + 1);
        }
        int lo, hi;
        try {
            int dash = rng.indexOf('-');
            lo = Integer.parseInt(dash > 0 ? rng.substring(0, dash) : rng);
            hi = dash > 0 ? Integer.parseInt(rng.substring(dash + 1)) : lo;
        } catch (Exception e) {
            return true;
        }
        if (week < lo || week > hi) return false;
        if ("单".equals(flag)) return week % 2 == 1;
        if ("双".equals(flag)) return week % 2 == 0;
        return true;
    }

    private JSONObject demoPayload(Integer week) {
        LocalDate start = demoStart();
        int target = week == null ? todayWeek() : week;
        JSONObject data = new JSONObject();
        JSONArray days = new JSONArray();
        LocalDate monday = start.plusWeeks(target - 1L);
        for (int offset = 0; offset < 7; offset++) {
            List<JSONObject> list = new ArrayList<>();
            for (Object[] row : DEMO) {
                int weekday = (Integer) row[3];
                if (weekday != offset + 1) continue;
                if (!inWeeks((String) row[6], target)) continue;
                try {
                    JSONObject course = new JSONObject();
                    course.put("name", row[0]);
                    course.put("teacher", row[1]);
                    course.put("location", row[2]);
                    course.put("start", (Integer) row[4]);
                    course.put("end", (Integer) row[5]);
                    String weeks = ((String) row[6]).split("\\|")[0];
                    course.put("weeks", weeks);
                    list.add(course);
                } catch (Exception ignored) {
                }
            }
            java.util.Collections.sort(list, (a, b) -> Integer.compare(a.optInt("start"), b.optInt("start")));
            JSONArray courses = new JSONArray();
            for (JSONObject course : list) courses.put(course);
            JSONObject day = new JSONObject();
            try {
                day.put("date", monday.plusDays(offset).toString());
                day.put("weekday", HnustClient.WEEKDAYS[offset]);
                day.put("courses", courses);
                days.put(day);
            } catch (Exception ignored) {
            }
        }
        try {
            data.put("week", target);
            data.put("days", days);
        } catch (Exception ignored) {
        }
        if (store.termStart().isEmpty()) store.setTermStart(start.toString());
        return shape(data, target);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
