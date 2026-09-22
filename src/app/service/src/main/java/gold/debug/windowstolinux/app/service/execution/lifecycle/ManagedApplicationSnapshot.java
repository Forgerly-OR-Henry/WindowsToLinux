package gold.debug.windowstolinux.app.service.execution.lifecycle;

import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;

/**
 * Persisted ownership, successful deployment contract and release identity; not a remote runtime claim.
 *
 *  <p>已持久化的资源归属、成功部署契约和发布身份；并非远端运行时状态声明。
 *
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param currentReleaseSha256 the current release identity digest / 当前发布身份摘要
 * @param runtimeConfiguration runtime configuration / 运行时配置
 */
public record ManagedApplicationSnapshot(ManagedApplication application, Optional<String> currentReleaseSha256,
        Optional<ManagedApplicationRuntimeConfiguration> runtimeConfiguration) {
    /**
     * Validates and binds the inputs required by managed application snapshot.
     * <p>校验并绑定受管应用快照所需输入。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param currentReleaseSha256 the current release identity digest / 当前发布身份摘要
     * @param runtimeConfiguration runtime configuration / 运行时配置
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedApplicationSnapshot {
        application = Objects.requireNonNull(application, "application");
        currentReleaseSha256 = Objects.requireNonNull(currentReleaseSha256, "currentReleaseSha256");
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
    }
}
