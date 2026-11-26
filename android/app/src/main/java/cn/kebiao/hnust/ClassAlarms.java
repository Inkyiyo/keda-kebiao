package cn.kebiao.hnust;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.AlarmClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 按课表自动定的闹钟（数据来自本地缓存的整周课表）：
 * 早上第一节课（12:00 之前的第一门）提前 55 分钟叫醒——如 10:00 上课，09:05 响；
 * 下午第一节课提前 30 分钟发提醒通知。
 * 用 setAlarmClock 定时（免精确闹钟权限、关机重启后由 BootReceiver 重新排），
 * 每次触发后由 AlarmReceiver 链式排下一次；打开 App / 刷新课表时也会重排。
 */
class ClassAlarms {

    static final String ACTION_FIRE = "cn.kebiao.hnust.CLASS_ALARM_FIRE";
    static final int REQ_WAKE = 4101;
    static final int REQ_REMIND = 4102;
    static final String CH_WAKE = "wake_alarm";
    static final String CH_REMIND = "class_remind";
    static final int WAKE_LEAD_MIN = 55;
    static final int REMIND_LEAD_MIN = 30;

    /** MainActivity 是否在前台：在系统时钟里设闹钟要发 Activity 意图，只在前台同步才稳。 */
    static volatile boolean appForeground = false;

    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static class Next {
        LocalDateTime trigger;
        LocalDateTime classAt;
        String courseName = "";
        String location = "";
    }

    // ------------------------------------------------------------------ //
    // 找下一节课
    // ------------------------------------------------------------------ //
    static Next findNext(Store store, boolean morning) {
        String termText = store.termStart();
        if (termText == null || termText.isEmpty()) return null;
        LocalDate term;
        try {
            term = LocalDate.parse(termText);
        } catch (Exception e) {
            return null;
        }
        List<String[]> sections = store.sections();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (int offset = 0; offset < 14; offset++) {
            LocalDate date = today.plusDays(offset);
            long days = ChronoUnit.DAYS.between(term, date);
            int week = (int) (days / 7) + 1;
            if (week < 1 || week > store.totalWeeks()) continue;
            String json = store.weekCache(week);
            if (json == null || json.isEmpty()) continue;
            try {
                JSONObject shaped = new JSONObject(json);
                JSONArray dayArr = shaped.optJSONArray("days");
                if (dayArr == null) continue;
                for (int i = 0; i < dayArr.length(); i++) {
                    JSONObject day = dayArr.optJSONObject(i);
                    if (day == null || !date.toString().equals(day.optString("date"))) continue;
                    JSONArray courses = day.optJSONArray("courses");
                    if (courses == null) break;
                    Next best = null;
                    for (int j = 0; j < courses.length(); j++) {
                        JSONObject c = courses.optJSONObject(j);
                        if (c == null) continue;
                        int startSec = c.optInt("start", 0);
                        if (startSec < 1 || startSec > sections.size()) continue;
                        LocalTime startTime;
                        try {
                            startTime = LocalTime.parse(sections.get(startSec - 1)[0]);
                        } catch (Exception e) {
                            continue;
                        }
                        boolean isMorning = startTime.isBefore(LocalTime.NOON);
                        if (isMorning != morning) continue;
                        if (best != null && !startTime.isBefore(best.classAt.toLocalTime())) continue;
                        Next n = new Next();
                        n.classAt = LocalDateTime.of(date, startTime);
                        n.trigger = n.classAt.minusMinutes(morning ? WAKE_LEAD_MIN : REMIND_LEAD_MIN);
                        n.courseName = c.optString("name");
                        n.location = c.optString("location");
                        best = n;
                    }
                    // 当天的第一门找到了但触发点已过 → 看下一天
                    if (best != null && best.trigger.isAfter(now)) return best;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 「明天 09:05 · 思想道德与法治（10:00 上课）」这样的说明文字，给「我的」页展示。 */
    static String describe(Next next) {
        if (next == null) return "";
        LocalDate today = LocalDate.now();
        LocalDate date = next.classAt.toLocalDate();
        String dayLabel = date.equals(today) ? "今天"
                : date.equals(today.plusDays(1)) ? "明天"
                : date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        return dayLabel + " " + hhmm(next.trigger) + " · " + next.courseName
                + "（" + hhmm(next.classAt) + " 上课）";
    }

    private static String hhmm(LocalDateTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    // ------------------------------------------------------------------ //
    // 排闹钟
    // ------------------------------------------------------------------ //
    static void reschedule(Context ctx) {
        try {
            Store store = new Store(ctx);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            ensureChannels(ctx);
            arm(ctx, am, store, true);
            arm(ctx, am, store, false);
        } catch (Exception e) {
            Diag.error("排上课闹钟失败：" + e.getMessage());
        }
        // 在前台时顺手把叫醒闹钟同步进系统时钟（后台发 Activity 意图不可靠）
        if (appForeground) syncSystemWake(ctx);
        // 小组件的下一个刷新点跟着一起排
        WidgetClock.scheduleNext(ctx);
    }

    private static void arm(Context ctx, AlarmManager am, Store store, boolean morning) {
        int req = morning ? REQ_WAKE : REQ_REMIND;
        PendingIntent old = fireIntent(ctx, req, morning, null);
        am.cancel(old);
        old.cancel();
        boolean enabled = morning ? store.wakeAlarm() : store.remindAlarm();
        if (!enabled || !store.loggedIn()) return;
        Next next = findNext(store, morning);
        if (next == null) return;
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent show = PendingIntent.getActivity(ctx, req, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long ms = next.trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(ms, show), fireIntent(ctx, req, morning, next));
        Diag.step((morning ? "叫醒闹钟" : "下午提醒") + " 已定：" + describe(next));
    }

    private static PendingIntent fireIntent(Context ctx, int req, boolean morning, Next next) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction(ACTION_FIRE);
        intent.putExtra("morning", morning);
        if (next != null) {
            intent.putExtra("course", next.courseName);
            intent.putExtra("location", next.location);
            intent.putExtra("classTime", hhmm(next.classAt));
            if (morning) intent.putExtra("stamp", stampOf(next));
        }
        return PendingIntent.getBroadcast(ctx, req, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ //
    // 系统时钟里的叫醒闹钟
    // ------------------------------------------------------------------ //
    static String stampOf(Next next) {
        return next.trigger.format(STAMP_FMT);
    }

    /**
     * 把下一次叫醒同步进系统时钟 App（ACTION_SET_ALARM 静默设置），时钟的原生
     * 响铃比通知可靠。已设的触发点记在 Store.sysAlarmStamp 里防重复设置；
     * 时间变了先尽力用 ACTION_DISMISS_ALARM 撤旧的（OEM 时钟不一定支持，
     * 撤不掉的靠「我的」页提示用户手动删）。AlarmManager 的叫醒仍照常排着
     * 做兜底：若系统闹钟已覆盖同一个触发点，它的通知会被 AlarmReceiver 跳过。
     */
    static void syncSystemWake(Context ctx) {
        // 只在前台同步：后台 startActivity 可能被系统静默丢弃，若据此记下
        // 「已设」会误伤兜底通知（该响的不响）。后台调用直接跳过，
        // 下次打开 App 时会补上；这期间 AlarmManager 的叫醒照常兜底。
        if (!appForeground) return;
        try {
            Store store = new Store(ctx);
            String stored = store.sysAlarmStamp();
            Next next = store.wakeAlarm() && store.loggedIn() ? findNext(store, true) : null;
            String target = next == null ? "" : stampOf(next);
            if (target.equals(stored)) return;
            // 目标变了：旧的若还没响，尽力撤掉
            if (!stored.isEmpty()) {
                LocalDateTime old = LocalDateTime.parse(stored, STAMP_FMT);
                if (old.isAfter(LocalDateTime.now())) dismissSystemAlarm(ctx, old);
                store.setSysAlarmStamp("");
            }
            if (next == null) return;
            if (setSystemAlarm(ctx, next)) {
                store.setSysAlarmStamp(target);
                Diag.step("系统叫醒闹钟已设：" + describe(next));
            }
        } catch (Exception e) {
            Diag.error("同步系统叫醒闹钟失败：" + e.getMessage());
        }
    }

    private static boolean setSystemAlarm(Context ctx, Next next) {
        try {
            Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
            intent.putExtra(AlarmClock.EXTRA_HOUR, next.trigger.getHour());
            intent.putExtra(AlarmClock.EXTRA_MINUTES, next.trigger.getMinute());
            intent.putExtra(AlarmClock.EXTRA_MESSAGE,
                    "起床 · " + (next.courseName.isEmpty() ? "上课" : next.courseName));
            intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            intent.putExtra(AlarmClock.EXTRA_VIBRATE, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) {
            Diag.error("设置系统闹钟失败：" + e.getMessage());
            return false;
        }
    }

    private static void dismissSystemAlarm(Context ctx, LocalDateTime when) {
        try {
            Intent intent = new Intent(AlarmClock.ACTION_DISMISS_ALARM);
            intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME);
            intent.putExtra(AlarmClock.EXTRA_HOUR, when.getHour());
            intent.putExtra(AlarmClock.EXTRA_MINUTES, when.getMinute());
            intent.putExtra(AlarmClock.EXTRA_IS_PM, when.getHour() >= 12);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            Diag.step("已尝试撤销旧系统闹钟 " + when.format(STAMP_FMT));
        } catch (Exception e) {
            Diag.error("撤销旧系统闹钟失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    // 到点发通知
    // ------------------------------------------------------------------ //
    static void fire(Context ctx, boolean morning, String course, String location, String classTime) {
        ensureChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(ctx, morning ? REQ_WAKE : REQ_REMIND, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String safeCourse = course == null || course.isEmpty() ? "有课" : course;
        String safeTime = classTime == null ? "" : classTime;
        String title = morning ? "该起床了" : "下午有课";
        String text = safeTime + " " + safeCourse
                + (location == null || location.isEmpty() ? "" : " · " + location)
                + (morning ? "，该起床了" : "，还有 30 分钟上课");
        Notification.Builder builder = new Notification.Builder(ctx, morning ? CH_WAKE : CH_REMIND)
                .setSmallIcon(ctx.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(morning ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (morning) builder.setFullScreenIntent(content, true);
        nm.notify(morning ? 5101 : 5102, builder.build());
        Diag.step((morning ? "叫醒闹钟" : "下午提醒") + " 已触发：" + text);
    }

    static void ensureChannels(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel wake = new NotificationChannel(CH_WAKE, "上课闹钟", NotificationManager.IMPORTANCE_HIGH);
        wake.setDescription("早上第一节课前 55 分钟叫醒");
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound != null) {
            wake.setSound(alarmSound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
        wake.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
        wake.enableVibration(true);
        nm.createNotificationChannel(wake);

        NotificationChannel remind = new NotificationChannel(CH_REMIND, "上课提醒", NotificationManager.IMPORTANCE_HIGH);
        remind.setDescription("下午第一节课前 30 分钟提醒");
        remind.setVibrationPattern(new long[]{0, 250, 150, 250});
        remind.enableVibration(true);
        nm.createNotificationChannel(remind);
    }
}
