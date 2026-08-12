package gold.debug.windowstolinux.app.service.lifecycle;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;

import java.util.Objects;
import java.util.Optional;

/**
 * Persisted ownership, successful deployment contract and release identity; not a remote runtime claim.
 *
 * <p>已持久化的资源归属、成功部署契约和发布身份；并非远端运行时状态声明。
 *
 * @param application the {@code application} value / {@code application} 值
 * @param currentReleaseSha256 the current release identity digest / 当前发布身份摘要
 * @param runtimeConfiguration the {@code runtimeConfiguration} value / {@code runtimeConfiguration} 值
 */
public record ManagedApplicationSummary(
        ManagedApplication application,
        Optional<String> currentReleaseSha256,
        Optional<ManagedApplicationRuntimeConfiguration> runtimeConfiguration
) {
    /**
     * Creates a {@code ManagedApplicationSummary} instance.
     *
     * <p>创建 {@code ManagedApplicationSummary} 实例。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param currentReleaseSha256 the current release identity digest / 当前发布身份摘要
     * @param runtimeConfiguration the {@code runtimeConfiguration} value / {@code runtimeConfiguration} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ManagedApplicationSummary {
        application = Objects.requireNonNull(application, "application");
        currentReleaseSha256 = Objects.requireNonNull(currentReleaseSha256, "currentReleaseSha256");
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
    }
}
