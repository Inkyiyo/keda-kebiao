package cn.kebiao.hnust;

import android.content.Context;

/** 桌面小组件的统一刷新入口：课表数据变化时调用。 */
class Widgets {

    static void refreshAll(Context ctx) {
        NextClassWidget.updateAll(ctx);
        TodayWidget.updateAll(ctx);
        // 每次刷新顺手把下一个边界刷新点重新排上：
        // 这样即使一直没打开 App，靠系统的周期更新也能把刷新链续上
        WidgetClock.scheduleNext(ctx);
    }
}
