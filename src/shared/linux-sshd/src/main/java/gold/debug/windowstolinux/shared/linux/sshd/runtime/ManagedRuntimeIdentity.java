package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;
import java.util.Optional;

/**
 * Identifies the sealed runtime kind without persisting a duplicate runtime specification. / 在不持久化重复运行时规格的情况下识别已封存运行时类型。
 *
 * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
 * @param containerEngine container engine / 容器引擎
 * @param mode selected operating or storage mode / 所选运行或存储模式
 */
public record ManagedRuntimeIdentity(Kind kind, Optional<DeploymentRuntimeSpecification.ContainerEngineType> containerEngine,
                                     gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode mode) {
    /**
     * Initializes managed runtime identity through its shared constructor contract.
     * <p>通过共享构造契约初始化受管运行时身份。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param engine engine / 引擎
     */
    public ManagedRuntimeIdentity(Kind kind, Optional<DeploymentRuntimeSpecification.ContainerEngineType> engine) {
        this(kind, engine, gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.DAEMON);
    }
    /**
     * Creates an instance of this type. / 创建此类型的实例。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param containerEngine container engine / 容器引擎
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRuntimeIdentity {
        kind = Objects.requireNonNull(kind, "kind");
        containerEngine = Objects.requireNonNull(containerEngine, "containerEngine");
        if ((kind == Kind.CONTAINER) != containerEngine.isPresent()) {
            throw new IllegalArgumentException("only a container runtime may declare an engine");
        }
    }

    /**
     * Selects the managed process or container identity strategy.
     * <p>选择受管进程或容器的身份策略。
     */
    enum Kind { /**
     * ORDINARY classification within kind.
     * <p>种类中的常规分类。
     */
    ORDINARY, /**
     * DEPLOYMENT classification within kind.
     * <p>种类中的部署分类。
     */
    DEPLOYMENT, /**
     * CONTAINER classification within kind.
     * <p>种类中的容器分类。
     */
    CONTAINER }
}
