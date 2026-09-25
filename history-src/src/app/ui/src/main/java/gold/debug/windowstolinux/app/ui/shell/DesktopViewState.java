package gold.debug.windowstolinux.app.ui.shell;

import java.util.Objects;

import gold.debug.windowstolinux.app.ui.ai.AiPageState;
import gold.debug.windowstolinux.app.ui.backup.BackupPageState;
import gold.debug.windowstolinux.app.ui.deployment.automatic.DeploymentPageState;
import gold.debug.windowstolinux.app.ui.deployment.multi.MultiComponentPageState;
import gold.debug.windowstolinux.app.ui.managed.ManagedPageState;
import gold.debug.windowstolinux.app.ui.server.ServerPageState;
import gold.debug.windowstolinux.app.ui.setting.SettingPageState;

/**
 * Aggregates page-owned state while the shell is rebuilt for a hot appearance update.
 *
 *  <p>在为外观热更新重建外壳时聚合各页面持有的状态。
 *
 * @param page page / 页面
 * @param deployment deployment / 部署
 * @param multiComponent the multi-component application page state / 多组件应用页面状态
 * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
 * @param managed managed / 受管
 * @param backup the local backup page state / 本地备份页面状态
 * @param ai the supplied ai page state / 所提供的AI页面状态
 * @param settings settings / 设置
 * @param expanded expanded / 已展开
 * @param deploymentSelection deployment selection / 部署选择
 * @param handoffs handoffs / 交接集合
 */
public record DesktopViewState(String page, DeploymentPageState deployment, MultiComponentPageState multiComponent,
        ServerPageState server, ManagedPageState managed, BackupPageState backup, AiPageState ai,
        SettingPageState settings, java.util.Map<String, Boolean> expanded,
        java.util.Map<String, String> deploymentSelection,
        java.util.Map<String, gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff> handoffs)
        implements
            AutoCloseable {
    /**
     * Creates a view state with no expanded inspectors, as on first launch. / 创建所有检查面板均折叠的视图状态，与首次启动一致。
     *
     * @param page page / 页面
     * @param deployment deployment / 部署
     * @param multiComponent the multi-component application page state / 多组件应用页面状态
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param managed managed / 受管
     * @param backup the local backup page state / 本地备份页面状态
     * @param ai the supplied ai page state / 所提供的AI页面状态
     * @param settings settings / 设置
     */
    public DesktopViewState(String page, DeploymentPageState deployment, MultiComponentPageState multiComponent,
            ServerPageState server, ManagedPageState managed, BackupPageState backup, AiPageState ai,
            SettingPageState settings) {
        this(page, deployment, multiComponent, server, managed, backup, ai, settings, java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of());
    }

    /**
     * Validates and binds the inputs required by desktop view state.
     * <p>校验并绑定Desktop视图状态所需输入。
     *
     * @param page page / 页面
     * @param deployment deployment / 部署
     * @param multiComponent the multi-component application page state / 多组件应用页面状态
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param managed managed / 受管
     * @param backup the local backup page state / 本地备份页面状态
     * @param ai the supplied ai page state / 所提供的AI页面状态
     * @param settings settings / 设置
     * @param expanded expanded / 已展开
     * @param deploymentSelection deployment selection / 部署选择
     * @param handoffs handoffs / 交接集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public void close() {
        server.close();
        ai.close();
        backup.close();
    }
}
