package cn.kebiao.hnust;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 小组件的「实时」刷新点：系统给小组件的定时更新（30 分钟）在 ColorOS 上会被
 * 省电策略拖很久，所以自己按课表算下一个状态变化时刻（上课、下课、跨天），
 * 到点精确唤醒刷新一次，再链式排下一个。有精确闹钟权限时用 setExactAndAllowWhileIdle，
 * 没有时退回 setAlarmClock（效果一样，只是状态栏会多个闹钟图标）。
 */
class WidgetClock {

    static final String ACTION_TICK = "cn.kebiao.hnust.WIDGET_TICK";
    static final int REQ_TICK = 4103;

    static void scheduleNext(Context ctx) {
        try {
            Store store = new Store(ctx);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent old = tickIntent(ctx);
            am.cancel(old);
            old.cancel();
            if (!store.loggedIn()) return;

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = null;
            // 逐天扫：某天的所有边界都早于下一天，找到第一天有候选即可停
            for (int offset = 0; offset < 14 && next == null; offset++) {
                LocalDate date = LocalDate.now().plusDays(offset);
                LocalDateTime midnight = date.atStartOfDay();
                if (midnight.isAfter(now)) next = midnight;
                for (Upcoming.Info info : Upcoming.dayCourses(store, date)) {
                    LocalDateTime s = LocalDateTime.of(date, info.start);
                    LocalDateTime e = LocalDateTime.of(date, info.end);
                    if (s.isAfter(now) && (next == null || s.isBefore(next))) next = s;
                    if (e.isAfter(now) && (next == null || e.isBefore(next))) next = e;
                }
            }
            if (next == null) return;
            // 状态在边界整点翻转，晚 5 秒触发确保读到新状态
            long ms = next.plusSeconds(5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            PendingIntent fire = tickIntent(ctx);
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                Intent open = new Intent(ctx, MainActivity.class);
                PendingIntent show = PendingIntent.getActivity(ctx, REQ_TICK, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(ms, show), fire);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, fire);
            }
            Diag.step("小组件刷新点已定 " + next);
        } catch (Exception e) {
            Diag.error("排小组件刷新点失败：" + e.getMessage());
        }
    }

    private static PendingIntent tickIntent(Context ctx) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(ctx, REQ_TICK, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean exactAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }
}
