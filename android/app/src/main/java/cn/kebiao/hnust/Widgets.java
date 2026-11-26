package cn.kebiao.hnust;

import android.content.Context;

/** 桌面小组件的统一刷新入口：课表数据变化时调用。 */
class Widgets {

    static void refreshAll(Context ctx) {
        NextClassWidget.updateAll(ctx);
        TodayWidget.updateAll(ctx);
    }
}
