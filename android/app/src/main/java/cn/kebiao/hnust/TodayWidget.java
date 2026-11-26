package cn.kebiao.hnust;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 桌面小组件：今日课表（浅色卡片）。按小组件的实际高度决定显示几行，绝不裁切。 */
public class TodayWidget extends AppWidgetProvider {

    private static final int BRAND = Color.rgb(0x0F, 0x5C, 0x4C);
    private static final int TEXT = Color.rgb(0x14, 0x24, 0x1F);
    private static final int ROW_H = 35;      // 每行约 35dp（15sp 字 + 上下 7dp）
    private static final int CHROME = 62;     // 内边距 32 + 标题行约 30
    private static final int FOOTER_H = 30;   // 分隔线 + 明天预告约 30
    // 启动器上报的高度含格子内边距和下方名称标签，实测要扣掉约 44dp 才是卡片真实高度
    private static final int LAUNCHER_RESERVE = 44;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context, heightOf(manager, id)));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        manager.updateAppWidget(appWidgetId,
                build(context, newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)));
    }

    private static int heightOf(AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        return options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, TodayWidget.class));
            for (int id : ids) {
                manager.updateAppWidget(id, build(context, heightOf(manager, id)));
            }
        } catch (Exception ignored) {
        }
    }

    static RemoteViews build(Context context, int reportedHeightDp) {
        int maxHeightDp = reportedHeightDp > 0
                ? Math.max(0, reportedHeightDp - LAUNCHER_RESERVE) : 0;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);
        Store store = new Store(context);
        LocalDate today = LocalDate.now();
        String[] weekNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String dateText = today.getMonthValue() + "月" + today.getDayOfMonth() + "日 · "
                + weekNames[today.getDayOfWeek().getValue() - 1];
        views.setTextViewText(R.id.w_today_date, dateText);

        List<Upcoming.Info> list = store.loggedIn() ? Upcoming.dayCourses(store, today) : new java.util.ArrayList<>();
        views.removeAllViews(R.id.w_today_rows);

        // 底部预告（空间不够时先牺牲它）
        List<Upcoming.Info> tomorrow = store.loggedIn()
                ? Upcoming.dayCourses(store, today.plusDays(1)) : new java.util.ArrayList<>();
        views.setTextViewText(R.id.w_today_footer, tomorrow.isEmpty()
                ? "明天没有课"
                : "明天第一课 " + Upcoming.hhmm(tomorrow.get(0).start) + " " + tomorrow.get(0).name
                        + (tomorrow.get(0).location.isEmpty() ? "" : " · " + tomorrow.get(0).location));

        if (list.isEmpty()) {
            views.setViewVisibility(R.id.w_today_empty, View.VISIBLE);
            views.setTextViewText(R.id.w_today_empty,
                    store.loggedIn() ? "今天没有课" : "点按打开科大课表登录");
            views.setViewVisibility(R.id.w_today_footer, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.w_today_empty, View.GONE);
            int total = list.size();
            boolean showFooter = true;
            int fit = maxHeightDp > 0 ? Math.max(1, (maxHeightDp - CHROME - FOOTER_H) / ROW_H) : total;
            if (total > fit) {
                showFooter = false;
                fit = maxHeightDp > 0 ? Math.max(1, (maxHeightDp - CHROME) / ROW_H) : total;
            }
            views.setViewVisibility(R.id.w_today_footer, showFooter ? View.VISIBLE : View.GONE);

            int courseRows;
            boolean summary = false;
            if (total <= fit) {
                courseRows = total;
            } else if (fit >= 3) {
                courseRows = fit - 1;   // 最后一行留给「还有 N 节课」
                summary = true;
            } else {
                courseRows = fit;       // 太矮就只放得下的课，不放汇总行
            }

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
            for (int i = 0; i < courseRows; i++) {
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
            if (summary) {
                RemoteViews more = new RemoteViews(context.getPackageName(), R.layout.widget_row);
                more.setTextViewText(R.id.w_row_time, "");
                more.setTextViewText(R.id.w_row_name, "还有 " + (total - courseRows) + " 节课，点按查看");
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
