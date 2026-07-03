package com.codex.applockguard.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.ServiceStateStore;
import com.codex.applockguard.service.AppMonitorService;
import com.codex.applockguard.sync.StudentSyncManager;
import com.codex.applockguard.util.LauncherVisibilityManager;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        ServiceStateStore serviceStateStore = new ServiceStateStore(context);
        serviceStateStore.recordRestartAttempt("收到系统广播：" + intent.getAction());
        LauncherVisibilityManager.repairMainActivity(context);
        AppLockStore store = new AppLockStore(context);
        if (!store.isMonitorEnabled()) {
            serviceStateStore.recordRestartAttempt("跳过广播补拉：保护开关未开启");
            return;
        }

        boolean started = AppMonitorService.start(context);
        StudentSyncManager.syncAllAsync(context, started ? "boot_receiver_started" : "boot_receiver_start_failed");
        serviceStateStore.recordRestartAttempt(started
                ? "系统广播后已请求补拉监控服务"
                : "系统广播后补拉监控服务失败");
    }
}
