package cn.kebiao.hnust;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 上课闹钟的接收器：到点发通知，然后链式排下一次；开机后重新排。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Widgets.refreshAll(context);
            ClassAlarms.reschedule(context);
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // 覆盖安装新版后、还没打开过 App 时：先把小组件刷成新状态，
            // 再把闹钟和边界刷新点全部重新排上（安装本身不会触发这些）
            Widgets.refreshAll(context);
            ClassAlarms.reschedule(context);
            return;
        }
        if (ClassAlarms.ACTION_FIRE.equals(action)) {
            boolean morning = intent.getBooleanExtra("morning", true);
            ClassAlarms.fire(context, morning,
                    intent.getStringExtra("course"),
                    intent.getStringExtra("location"),
                    intent.getStringExtra("classTime"));
            ClassAlarms.reschedule(context);
            return;
        }
        if (WidgetClock.ACTION_TICK.equals(action)) {
            // 到达上课/下课/跨天的边界：刷新小组件，reschedule 会链式排下一个刷新点
            Widgets.refreshAll(context);
            ClassAlarms.reschedule(context);
        }
    }
}
