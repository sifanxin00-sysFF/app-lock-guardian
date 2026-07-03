package com.codex.applockguard.ui;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codex.applockguard.data.AppLockStore;
import com.codex.applockguard.data.DeviceIdentityStore;
import com.codex.applockguard.data.DiagnosticStore;
import com.codex.applockguard.data.ServiceStateStore;
import com.codex.applockguard.net.AppConfig;
import com.codex.applockguard.net.ApprovalApi;
import com.codex.applockguard.service.AppMonitorService;
import com.codex.applockguard.util.ForegroundAppReader;
import com.codex.applockguard.util.LauncherVisibilityManager;
import com.codex.applockguard.util.PermissionUtils;
import com.codex.applockguard.util.ProtectionStatusResolver;
import com.codex.applockguard.util.ProtectionStatusSnapshot;
import com.codex.applockguard.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DiagnosticsActivity extends android.app.Activity {
    private static final long HEARTBEAT_FREEZE_WARNING_MS = 5_000L;
    private final ApprovalApi api = new ApprovalApi();
    private TextView resultView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render("还没有运行自检。");
    }

    private void render(String selfTestText) {
        AppLockStore store = new AppLockStore(this);
        DeviceIdentityStore identityStore = new DeviceIdentityStore(this);
        DiagnosticStore diagnosticStore = new DiagnosticStore(this);
        ServiceStateStore serviceStateStore = new ServiceStateStore(this);
        ProtectionStatusSnapshot status = ProtectionStatusResolver.resolve(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = Ui.root(this);
        root.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scrollView.addView(root);

        root.addView(Ui.title(this, "诊断与保护自检"));
        root.addView(Ui.body(this,
                "后端地址：" + AppConfig.BASE_URL
                        + "\n设备 ID：" + identityStore.getDeviceId()
                        + "\n当前保护状态：" + status.title
                        + "\n状态说明：" + status.detail
                        + "\n后台运行判断：" + runtimeStatusLabel(serviceStateStore, store.isMonitorEnabled())
                        + "\n心跳停滞：" + heartbeatLagSummary(serviceStateStore)
                        + "\n使用情况权限：" + yesNo(PermissionUtils.hasUsageAccess(this))
                        + "\n无障碍监测：" + yesNo(PermissionUtils.isAccessibilityEnabled(this))
                        + "\n无障碍心跳：" + yesNo(serviceStateStore.isAccessibilityAlive())
                        + "\n无障碍心跳时间：" + formatTime(serviceStateStore.getAccessibilityHeartbeatAt())
                        + "\n最近一次无障碍事件：" + empty(serviceStateStore.getLastAccessibilityEvent())
                        + "\n无障碍事件时间：" + formatTime(serviceStateStore.getLastAccessibilityEventAt())
                        + "\n最近一次无障碍循环异常：" + empty(serviceStateStore.getLastAccessibilityLoopError())
                        + "\n无障碍循环异常时间：" + formatTime(serviceStateStore.getLastAccessibilityLoopErrorAt())
                        + "\n后台保护心跳：" + yesNo(serviceStateStore.isMonitorAlive())
                        + "\n后台保护心跳时间：" + formatTime(serviceStateStore.getMonitorHeartbeatAt())
                        + "\n最近一次后台事件：" + empty(serviceStateStore.getLastMonitorEvent())
                        + "\n后台事件时间：" + formatTime(serviceStateStore.getLastMonitorEventAt())
                        + "\n最近一次后台循环异常：" + empty(serviceStateStore.getLastMonitorLoopError())
                        + "\n后台循环异常时间：" + formatTime(serviceStateStore.getLastMonitorLoopErrorAt())
                        + "\n最近一次补拉记录：" + empty(serviceStateStore.getLastRestartAttempt())
                        + "\n补拉记录时间：" + formatTime(serviceStateStore.getLastRestartAttemptAt())
                        + "\nWakeLock 状态：" + (serviceStateStore.getLastWakeLockHeld() ? "持有中" : "未持有")
                        + "\nWakeLock 最近事件：" + empty(serviceStateStore.getLastWakeLockEvent())
                        + "\nWakeLock 最近时间：" + formatTime(serviceStateStore.getLastWakeLockEventAt())
                        + "\n设备管理员：" + yesNo(PermissionUtils.isDeviceAdminActive(this))
                        + "\n保护开关：" + yesNo(store.isMonitorEnabled())
                        + "\n守门模式：" + store.getGuardModeLabel()
                        + "\n守门类型：" + store.getGuardModeType()
                        + "\n守门剩余：" + formatRemaining(store.getGuardModeRemainingMs())
                        + "\n守门结束时间：" + formatTime(store.getGuardModeUntil())
                        + "\n守门白名单：" + store.getGuardWhitelistPackages().size() + " 个"
                        + "\n最近守门判断包名：" + empty(diagnosticStore.getLastGuardDecisionPackage())
                        + "\n最近守门判断模式：" + empty(diagnosticStore.getLastGuardDecisionMode())
                        + "\n最近守门判断结果：" + empty(diagnosticStore.getLastGuardDecisionResult())
                        + "\n最近守门判断原因：" + empty(diagnosticStore.getLastGuardDecisionReason())
                        + "\n最近守门判断剩余：" + formatRemaining(diagnosticStore.getLastGuardDecisionRemainingMs())
                        + "\n最近守门判断时间：" + formatTime(diagnosticStore.getLastGuardDecisionAt())
                        + "\n桌面图标：" + yesNo(LauncherVisibilityManager.isLauncherVisible(this))
                        + "\n桌面入口状态：" + LauncherVisibilityManager.getLauncherStateSummary(this)
                        + "\n限制应用：" + store.getLockedPackages().size() + " 个"
                        + "\n最近一次前台识别：" + empty(diagnosticStore.getLastForegroundPackage())
                        + "\n前台识别时间：" + formatTime(diagnosticStore.getLastForegroundAt())
                        + "\n最近一次后台拦截候选：" + empty(diagnosticStore.getLastMonitorBlockCandidatePackage())
                        + "\n后台拦截候选时间：" + formatTime(diagnosticStore.getLastMonitorBlockCandidateAt())
                        + "\n最近一次锁窗尝试目标：" + empty(diagnosticStore.getLastLockAttemptTarget())
                        + "\n锁窗尝试来源：" + empty(diagnosticStore.getLastLockAttemptSource())
                        + "\n锁窗尝试时间：" + formatTime(diagnosticStore.getLastLockAttemptAt())
                        + "\n最终前台校验：" + empty(diagnosticStore.getLastLockAttemptFinalForeground())
                        + "\n锁窗取消原因：" + empty(diagnosticStore.getLastLockAttemptCancelReason())
                        + "\n锁窗取消时间：" + formatTime(diagnosticStore.getLastLockAttemptCancelAt())
                        + "\n最近一次锁层目标：" + empty(diagnosticStore.getLastLockTargetPackage())
                        + "\n最近一次锁层来源：" + empty(diagnosticStore.getLastLockSource())
                        + "\n最近一次锁层显示：" + formatTime(diagnosticStore.getLastLockShowAt())
                        + "\n显示时前台：" + empty(diagnosticStore.getLastLockShowForeground())
                        + "\n最近一次锁层隐藏：" + formatTime(diagnosticStore.getLastLockHideAt())
                        + "\n隐藏原因：" + empty(diagnosticStore.getLastLockHideReason())
                        + "\n隐藏时前台：" + empty(diagnosticStore.getLastLockHideForeground())
                        + "\n最近一次未显示锁层：" + formatTime(diagnosticStore.getLastLockSkipAt())
                        + "\n未显示原因：" + empty(diagnosticStore.getLastLockSkipReason())
                        + "\n未显示时前台：" + empty(diagnosticStore.getLastLockSkipForeground())
                        + "\n未显示目标：" + empty(diagnosticStore.getLastLockSkipTarget())
                        + "\n最近一次兜底锁层包名：" + empty(diagnosticStore.getLastFallbackLockPackage())
                        + "\n兜底锁层动作：" + empty(diagnosticStore.getLastFallbackLockAction())
                        + "\n兜底锁层结果：" + empty(diagnosticStore.getLastFallbackLockResult())
                        + "\n兜底锁层时间：" + formatTime(diagnosticStore.getLastFallbackLockAt())
                        + "\n最近一次图标动作：" + empty(diagnosticStore.getLastLauncherAction())
                        + "\n图标动作时间：" + formatTime(diagnosticStore.getLastLauncherActionAt())
                        + "\n图标动作结果：" + empty(diagnosticStore.getLastLauncherState())
                        + "\n图标期望显示：" + yesNo(diagnosticStore.getLastLauncherDesiredVisible())
                        + "\n图标实际显示：" + yesNo(diagnosticStore.getLastLauncherVisibleAfter())
                        + "\n图标状态切换：" + launcherResult(diagnosticStore)
                        + "\n最近一次拉取命令：" + formatTime(diagnosticStore.getLastCommandPollAt())
                        + "\n最近一次命令结果：" + empty(diagnosticStore.getLastCommandResult())
                        + "\n最近一次网络错误：" + empty(diagnosticStore.getLastApiError())
        ));

        resultView = Ui.body(this, selfTestText);
        root.addView(resultView);

        Button selfTest = Ui.button(this, "运行保护自检");
        selfTest.setOnClickListener(v -> runSelfTest());
        root.addView(selfTest);

        Button startService = Ui.button(this, "重新启动后台保护");
        startService.setOnClickListener(v -> {
            AppMonitorService.start(this);
            resultView.setText("已经请求重新启动后台保护。几秒后返回本页刷新，可以看心跳是否恢复。");
        });
        root.addView(startService);

        Button showIcon = Ui.button(this, "强制显示桌面图标");
        showIcon.setOnClickListener(v -> {
            LauncherVisibilityManager.setLauncherVisible(this, true);
            render("已经请求显示桌面图标。");
        });
        root.addView(showIcon);

        setContentView(scrollView);
    }

    private void runSelfTest() {
        resultView.setText("正在自检...");
        new Thread(() -> {
            ProtectionStatusSnapshot status = ProtectionStatusResolver.resolve(this);
            ServiceStateStore serviceStateStore = new ServiceStateStore(this);
            DiagnosticStore diagnosticStore = new DiagnosticStore(this);
            AppLockStore store = new AppLockStore(this);
            String foreground = ForegroundAppReader.getForegroundPackage(this);

            StringBuilder out = new StringBuilder();
            out.append("当前保护状态：").append(status.title).append('\n');
            out.append("状态说明：").append(status.detail).append('\n');
            out.append("后台运行判断：").append(runtimeStatusLabel(serviceStateStore, store.isMonitorEnabled())).append('\n');
            out.append("心跳停滞：").append(heartbeatLagSummary(serviceStateStore)).append('\n');
            out.append("使用情况权限：").append(yesNo(PermissionUtils.hasUsageAccess(this))).append('\n');
            out.append("无障碍监测：").append(yesNo(PermissionUtils.isAccessibilityEnabled(this))).append('\n');
            out.append("无障碍心跳：").append(yesNo(serviceStateStore.isAccessibilityAlive())).append('\n');
            out.append("后台保护心跳：").append(yesNo(serviceStateStore.isMonitorAlive())).append('\n');
            out.append("WakeLock 状态：").append(serviceStateStore.getLastWakeLockHeld() ? "持有中" : "未持有").append('\n');
            out.append("普通设备管理员：").append(yesNo(PermissionUtils.isDeviceAdminActive(this))).append('\n');
            out.append("守门模式：").append(store.getGuardModeLabel()).append('\n');
            out.append("守门剩余：").append(formatRemaining(store.getGuardModeRemainingMs())).append('\n');
            out.append("当前前台识别：").append(empty(foreground)).append('\n');
            out.append("最近守门判断包名：").append(empty(diagnosticStore.getLastGuardDecisionPackage())).append('\n');
            out.append("最近守门判断结果：").append(empty(diagnosticStore.getLastGuardDecisionResult())).append('\n');
            out.append("最近守门判断原因：").append(empty(diagnosticStore.getLastGuardDecisionReason())).append('\n');
            out.append("最近一次无障碍事件：").append(empty(serviceStateStore.getLastAccessibilityEvent())).append('\n');
            out.append("最近一次后台事件：").append(empty(serviceStateStore.getLastMonitorEvent())).append('\n');
            out.append("最近一次无障碍循环异常：").append(empty(serviceStateStore.getLastAccessibilityLoopError())).append('\n');
            out.append("最近一次后台循环异常：").append(empty(serviceStateStore.getLastMonitorLoopError())).append('\n');
            out.append("最近一次后台拦截候选：").append(empty(diagnosticStore.getLastMonitorBlockCandidatePackage())).append('\n');
            out.append("最近一次锁窗尝试目标：").append(empty(diagnosticStore.getLastLockAttemptTarget())).append('\n');
            out.append("最终前台校验：").append(empty(diagnosticStore.getLastLockAttemptFinalForeground())).append('\n');
            out.append("锁窗取消原因：").append(empty(diagnosticStore.getLastLockAttemptCancelReason())).append('\n');
            out.append("最近一次锁层目标：").append(empty(diagnosticStore.getLastLockTargetPackage())).append('\n');
            out.append("最近一次锁层来源：").append(empty(diagnosticStore.getLastLockSource())).append('\n');
            out.append("最近一次未显示锁层原因：").append(empty(diagnosticStore.getLastLockSkipReason())).append('\n');
            out.append("最近一次兜底锁层结果：").append(empty(diagnosticStore.getLastFallbackLockResult())).append('\n');
            out.append("图标状态切换：").append(launcherResult(diagnosticStore)).append('\n');
            out.append("后端连通：");
            try {
                out.append(api.health() ? "正常" : "异常");
            } catch (Exception e) {
                out.append("失败：").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
                diagnosticStore.recordApiError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            runOnUiThread(() -> resultView.setText(out.toString()));
        }).start();
    }

    private static String yesNo(boolean value) {
        return value ? "已开启" : "未开启";
    }

    private static String okBad(boolean value) {
        return value ? "正常" : "异常";
    }

    private static String launcherResult(DiagnosticStore diagnosticStore) {
        if (diagnosticStore.getLastLauncherAction().trim().isEmpty()) {
            return "暂无";
        }
        return okBad(diagnosticStore.getLastLauncherSuccess());
    }

    private static String runtimeStatusLabel(ServiceStateStore store, boolean monitorEnabled) {
        if (!monitorEnabled) {
            return "保护未开启";
        }
        boolean monitorStale = isHeartbeatStale(store.getMonitorHeartbeatAt());
        boolean accessibilityStale = isHeartbeatStale(store.getAccessibilityHeartbeatAt());
        if (monitorStale && accessibilityStale) {
            return "后台保护疑似被系统冻结";
        }
        if (monitorStale) {
            return "后台保护疑似被系统冻结";
        }
        if (accessibilityStale) {
            return "无障碍疑似停止响应";
        }
        return "后台保护正常";
    }

    private static String heartbeatLagSummary(ServiceStateStore store) {
        return "后台 " + heartbeatLagText(store.getMonitorHeartbeatAt())
                + " / 无障碍 " + heartbeatLagText(store.getAccessibilityHeartbeatAt());
    }

    private static boolean isHeartbeatStale(long timestamp) {
        return timestamp <= 0L || System.currentTimeMillis() - timestamp > HEARTBEAT_FREEZE_WARNING_MS;
    }

    private static String heartbeatLagText(long timestamp) {
        if (timestamp <= 0L) {
            return "暂无心跳";
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        return seconds + " 秒";
    }

    private static String empty(String value) {
        return value == null || value.trim().isEmpty() ? "暂无" : value;
    }

    private static String formatTime(long timestamp) {
        if (timestamp <= 0L) {
            return "暂无";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(timestamp));
    }

    private static String formatRemaining(long ms) {
        if (ms <= 0L) {
            return "未开启";
        }
        long totalMinutes = Math.max(1L, (ms + 59_999L) / 60_000L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) {
            return hours + "小时" + (minutes > 0L ? minutes + "分钟" : "");
        }
        return minutes + "分钟";
    }
}
