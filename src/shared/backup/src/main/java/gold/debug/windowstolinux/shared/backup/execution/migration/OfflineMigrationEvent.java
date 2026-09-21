package gold.debug.windowstolinux.shared.backup.execution.migration;

import java.util.Objects;

/**
 * One bounded non-secret migration evidence event. / 单个有界无秘密迁移证据事件。
 *
 * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
 * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record OfflineMigrationEvent(OfflineMigrationState state, boolean succeeded, String evidence) {
    /**
     * Validates bounded event evidence. / 校验有界事件证据。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public OfflineMigrationEvent {
        state = Objects.requireNonNull(state, "state");
        evidence = Objects.requireNonNull(evidence, "evidence").trim();
        if (evidence.isEmpty() || evidence.length() > 1024 || evidence.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("migration event evidence is invalid");
        }
    }
}
