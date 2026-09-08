package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.ui.ai.AiPageState;
import gold.debug.windowstolinux.app.ui.backup.BackupPageState;
import gold.debug.windowstolinux.app.ui.deployment.single.DeploymentPageState;
import gold.debug.windowstolinux.app.ui.deployment.multi.MultiComponentPageState;
import gold.debug.windowstolinux.app.ui.managed.ManagedPageState;
import gold.debug.windowstolinux.app.ui.server.ServerPageState;
import gold.debug.windowstolinux.app.ui.setting.SettingPageState;

import java.util.Objects;

/**
 * Aggregates page-owned state while the shell is rebuilt for a hot appearance update.
 *
 * <p>在为外观热更新重建外壳时聚合各页面持有的状态。
 *
 * @param page the {@code page} value / {@code page} 值
 * @param deployment the {@code deployment} value / {@code deployment} 值
 * @param multiComponent the multi-component application page state / 多组件应用页面状态
 * @param server the {@code server} value / {@code server} 值
 * @param managed the {@code managed} value / {@code managed} 值
 * @param backup the local backup page state / 本地备份页面状态
 * @param ai the {@code ai} value / {@code ai} 值
 * @param settings the {@code settings} value / {@code settings} 值
 */
public record DesktopViewState(
        String page,
        DeploymentPageState deployment,
        MultiComponentPageState multiComponent,
        ServerPageState server,
        ManagedPageState managed,
        BackupPageState backup,
        AiPageState ai,
        SettingPageState settings,
        java.util.Map<String, Boolean> expanded,
        java.util.Map<String, String> deploymentSelection,
        java.util.Map<String, gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff> handoffs
) implements AutoCloseable {
    /** Creates a view state with no expanded inspectors, as on first launch. */
    public DesktopViewState(String page, DeploymentPageState deployment, MultiComponentPageState multiComponent,
                            ServerPageState server, ManagedPageState managed, BackupPageState backup,
                            AiPageState ai, SettingPageState settings) {
        this(page, deployment, multiComponent, server, managed, backup, ai, settings, java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
    }
    /**
     * Creates a {@code DesktopViewState} instance.
     *
     * <p>创建 {@code DesktopViewState} 实例。
     *
     * @param page the {@code page} value / {@code page} 值
     * @param deployment the {@code deployment} value / {@code deployment} 值
     * @param multiComponent the multi-component application page state / 多组件应用页面状态
     * @param server the {@code server} value / {@code server} 值
     * @param managed the {@code managed} value / {@code managed} 值
     * @param backup the local backup page state / 本地备份页面状态
     * @param ai the {@code ai} value / {@code ai} 值
     * @param settings the {@code settings} value / {@code settings} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopViewState {
        expanded = java.util.Map.copyOf(expanded);
        handoffs = java.util.Map.copyOf(handoffs);
        deploymentSelection = java.util.Map.copyOf(deploymentSelection);
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(deployment, "deployment");
        Objects.requireNonNull(multiComponent, "multiComponent");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(managed, "managed");
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(ai, "ai");
        Objects.requireNonNull(settings, "settings");
    }

    /** Closes this resource. / 关闭此资源。 */
    @Override
    public void close() {
        server.close();
        ai.close();
        backup.close();
    }
}
