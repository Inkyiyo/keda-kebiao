package cn.kebiao.hnust;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 桌面小组件：今日课表（浅色卡片，列出今天全部课）。 */
public class TodayWidget extends AppWidgetProvider {

    private static final int BRAND = Color.rgb(0x0F, 0x5C, 0x4C);
    private static final int TEXT = Color.rgb(0x14, 0x24, 0x1F);

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context));
        }
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, TodayWidget.class));
            for (int id : ids) {
                manager.updateAppWidget(id, build(context));
            }
        } catch (Exception ignored) {
        }
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);
        Store store = new Store(context);
        LocalDate today = LocalDate.now();
        String[] weekNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String dateText = today.getMonthValue() + "月" + today.getDayOfMonth() + "日 · "
                + weekNames[today.getDayOfWeek().getValue() - 1];
        views.setTextViewText(R.id.w_today_date, dateText);

        List<Upcoming.Info> list = store.loggedIn() ? Upcoming.dayCourses(store, today) : new java.util.ArrayList<>();
        views.removeAllViews(R.id.w_today_rows);
        if (list.isEmpty()) {
            views.setViewVisibility(R.id.w_today_empty, View.VISIBLE);
            views.setTextViewText(R.id.w_today_empty,
                    store.loggedIn() ? "今天没有课" : "点按打开科大课表登录");
        } else {
            views.setViewVisibility(R.id.w_today_empty, View.GONE);
            // 高亮：正在上的那节，其次是下一节
            LocalDateTime now = LocalDateTime.now();
            int highlight = -1;
            for (int i = 0; i < list.size(); i++) {
                Upcoming.Info info = list.get(i);
                if (LocalDateTime.of(info.date, info.end).isAfter(now)) {
                    highlight = i;
                    break;
                }
            }
            int max = Math.min(list.size(), 8);
            for (int i = 0; i < max; i++) {
                Upcoming.Info info = list.get(i);
                RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
                row.setTextViewText(R.id.w_row_time, Upcoming.hhmm(info.start) + "–" + Upcoming.hhmm(info.end));
                row.setTextViewText(R.id.w_row_name, info.name);
                row.setTextViewText(R.id.w_row_loc, info.location);
                if (i == highlight) {
                    row.setTextColor(R.id.w_row_name, BRAND);
                    row.setTextColor(R.id.w_row_time, BRAND);
                } else {
                    row.setTextColor(R.id.w_row_name, TEXT);
                }
                views.addView(R.id.w_today_rows, row);
            }
            if (list.size() > max) {
                RemoteViews more = new RemoteViews(context.getPackageName(), R.layout.widget_row);
                more.setTextViewText(R.id.w_row_time, "");
                more.setTextViewText(R.id.w_row_name, "还有 " + (list.size() - max) + " 节课，点按查看");
                more.setTextViewText(R.id.w_row_loc, "");
                views.addView(R.id.w_today_rows, more);
            }
        }
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 4202, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_today_root, pi);
        return views;
    }
}
