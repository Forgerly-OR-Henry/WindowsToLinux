package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ManagedRuntimeProtocolExecutor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Reads the root-owned current-release markers through the fixed helper protocol. / 通过固定 helper 协议读取 root 所有的当前发布标记。
 */
public final class ManagedRuntimeKindProbe {
    /**
     * Bound managed runtime protocol executor collaborator for runtimes.
     * <p>处理运行时集合的受管运行时协议执行器协作对象。
     */
    private final ManagedRuntimeProtocolExecutor runtimes;

    /**
     * Creates a managed runtime kind probe. / 创建受管运行时类型探测器。
     *
     * @param runtimes runtimes / 运行时集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRuntimeKindProbe(ManagedRuntimeProtocolExecutor runtimes) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    /**
     * Identifies one current release only after helper ownership verification. / 仅在 helper 验证归属后识别当前发布。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return constructed or resolved managed runtime identity / 构造或解析得到的受管运行时身份
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    ManagedRuntimeIdentity inspect(ManagedApplication application) throws LinuxOperationException {
        Map<String, String> values = runtimes.inspect(application);
        var mode = "ON_DEMAND".equals(values.get("MODE"))
                ? gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.ON_DEMAND
                : gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.DAEMON;
        return switch (values.getOrDefault("KIND", "")) {
            case "ordinary" -> new ManagedRuntimeIdentity(ManagedRuntimeIdentity.Kind.ORDINARY, Optional.empty());
            case "deployment" ->
                new ManagedRuntimeIdentity(ManagedRuntimeIdentity.Kind.DEPLOYMENT, Optional.empty(), mode);
            case "container" -> new ManagedRuntimeIdentity(ManagedRuntimeIdentity.Kind.CONTAINER,
                    Optional.of(parseEngine(values.get("ENGINE"))), mode);
            default -> throw LinuxOperationException.create(LinuxOperationFailureType.RUNTIME_OBSERVATION_FAILED,
                    "Controlled helper returned an unsupported managed runtime kind");
        };
    }

    /**
     * Parses engine.
     * <p>解析引擎。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return engine / 引擎
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static DeploymentRuntimeSpecification.ContainerEngineType parseEngine(String value)
            throws LinuxOperationException {
        try {
            return DeploymentRuntimeSpecification.ContainerEngineType
                    .valueOf(Objects.requireNonNull(value, "container engine").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.RUNTIME_OBSERVATION_FAILED,
                    "Controlled helper returned an unsupported managed container engine", exception);
        }
    }
}
