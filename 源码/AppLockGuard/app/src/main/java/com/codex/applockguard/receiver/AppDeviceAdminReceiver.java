package com.codex.applockguard.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public final class AppDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "关闭设备管理员后，卸载阻力会明显下降，保护链路也会变弱。";
    }
}
