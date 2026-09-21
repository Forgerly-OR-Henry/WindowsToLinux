package gold.debug.windowstolinux.shared.model.lifecycle;

import java.util.Objects;

/**
 * Bounded scan evidence; a managed marker never grants ownership. / 有界扫描证据，受管标记本身不授予归属。
 *
 * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
 * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 * @param canStart can start / 能够启动
 * @param canStop can stop / 能够停止
 * @param managed managed / 受管
 */
public record DiscoveredApplication(ExternalApplicationTarget target, String name, RuntimeState state,
                                    boolean canStart, boolean canStop, boolean managed) {
    /**
     * Bounds display metadata without retaining process environments. / 限制显示元数据，不保留进程环境。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param canStart can start / 能够启动
     * @param canStop can stop / 能够停止
     * @param managed managed / 受管
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DiscoveredApplication {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(state, "state");
        if (Objects.requireNonNull(name, "name").isBlank() || name.length() > 240 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid application display name");
    }
}
