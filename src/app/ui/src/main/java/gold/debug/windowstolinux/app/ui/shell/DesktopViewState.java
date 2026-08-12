package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.ui.ai.AiPageState;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentPageState;
import gold.debug.windowstolinux.app.ui.deployment.MultiComponentPageState;
import gold.debug.windowstolinux.app.ui.managed.ManagedPageState;
import gold.debug.windowstolinux.app.ui.server.ServerPageState;
import gold.debug.windowstolinux.app.ui.settings.SettingsPageState;

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
 * @param ai the {@code ai} value / {@code ai} 值
 * @param settings the {@code settings} value / {@code settings} 值
 */
public record DesktopViewState(
        String page,
        DeploymentPageState deployment,
        MultiComponentPageState multiComponent,
        ServerPageState server,
        ManagedPageState managed,
        AiPageState ai,
        SettingsPageState settings
) implements AutoCloseable {
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
     * @param ai the {@code ai} value / {@code ai} 值
     * @param settings the {@code settings} value / {@code settings} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopViewState {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(deployment, "deployment");
        Objects.requireNonNull(multiComponent, "multiComponent");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(managed, "managed");
        Objects.requireNonNull(ai, "ai");
        Objects.requireNonNull(settings, "settings");
    }

    @Override
    public void close() {
        server.close();
        ai.close();
    }
}
