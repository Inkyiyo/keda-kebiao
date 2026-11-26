package cn.kebiao.hnust;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 桌面小组件：最近的一节课（深绿卡片）。 */
public class NextClassWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context, heightOf(manager, id)));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, android.os.Bundle newOptions) {
        manager.updateAppWidget(appWidgetId,
                build(context, newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)));
    }

    private static int heightOf(AppWidgetManager manager, int id) {
        android.os.Bundle options = manager.getAppWidgetOptions(id);
        return options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, NextClassWidget.class));
            for (int id : ids) {
                manager.updateAppWidget(id, build(context, heightOf(manager, id)));
            }
        } catch (Exception ignored) {
        }
    }

    static RemoteViews build(Context context, int maxHeightDp) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_next);
        Store store = new Store(context);
        Upcoming.Info info = store.loggedIn() ? Upcoming.currentOrNext(store) : null;
        if (info == null) {
            views.setTextViewText(R.id.w_next_label, "最近的一节课");
            views.setTextViewText(R.id.w_next_name, store.loggedIn() ? "暂无课程安排" : "还没有课表");
            views.setTextViewText(R.id.w_next_meta, store.loggedIn() ? "打开 App 刷新一下课表" : "点按打开科大课表登录");
            views.setTextViewText(R.id.w_next_sub, "");
            views.setTextViewText(R.id.w_next_footer, "");
        } else {
            LocalDateTime now = LocalDateTime.now();
            String label;
            String sub;
            if (info.ongoing) {
                label = "正在上课";
                long mins = ChronoUnit.MINUTES.between(now, LocalDateTime.of(info.date, info.end));
                sub = "还有 " + humanMins(mins) + "下课";
            } else {
                label = Upcoming.dayLabel(info.date) + "的下一节课";
                long mins = ChronoUnit.MINUTES.between(now, LocalDateTime.of(info.date, info.start));
                sub = "还有 " + humanMins(mins) + "上课";
            }
            if (!info.location.isEmpty()) {
                sub += " · " + info.location;
            }
            views.setTextViewText(R.id.w_next_label, label);
            views.setTextViewText(R.id.w_next_name, info.name);
            views.setTextViewText(R.id.w_next_meta,
                    Upcoming.dayLabel(info.date) + " " + Upcoming.hhmm(info.start) + "–" + Upcoming.hhmm(info.end)
                            + (info.teacher.isEmpty() ? "" : " · " + info.teacher));
            views.setTextViewText(R.id.w_next_sub, sub);
            // 底部一行：今天还剩几节课（或今天的课已上完）
            java.util.List<Upcoming.Info> todayList = Upcoming.dayCourses(store, java.time.LocalDate.now());
            int remaining = 0;
            for (Upcoming.Info item : todayList) {
                if (LocalDateTime.of(item.date, item.end).isAfter(now)) remaining++;
            }
            String footer;
            if (info.date.equals(java.time.LocalDate.now())) {
                footer = remaining > 0 ? "今天还剩 " + remaining + " 节课" : "";
            } else {
                footer = remaining > 0 ? "今天还剩 " + remaining + " 节课" : "今天的课已经上完";
            }
            views.setTextViewText(R.id.w_next_footer, footer);
        }
        // 矮尺寸下藏掉底部一行，避免文字被裁（上报高度含启动器预留，先扣掉）
        int effective = maxHeightDp > 0 ? maxHeightDp - 44 : 0;
        views.setViewVisibility(R.id.w_next_footer,
                effective > 0 && effective < 170 ? android.view.View.GONE : android.view.View.VISIBLE);
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 4201, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_next_root, pi);
        return views;
    }

    private static String humanMins(long mins) {
        if (mins < 60) return mins + " 分钟";
        long h = mins / 60;
        long m = mins % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分钟";
    }
}
