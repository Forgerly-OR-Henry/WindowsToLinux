package gold.debug.windowstolinux.shared.model.failure;

/**
 * Stable module-owned definition of one failure category. / 模块持有的单个稳定失败类别定义。
 */
public interface FailureDefinition {
    /**
     * Returns the stable {@code domain.operation.reason} code. / 返回稳定的 {@code domain.operation.reason} 错误码。
     *
     * @return the stable {@code domain.operation.reason} code / 稳定的 {@code domain.operation.reason} 错误码
     */
    String code();

    /**
     * Returns the owning domain. / 返回所属领域。
     *
     * @return the owning domain / 所属领域
     */
    String domain();

    /**
     * Returns the operation phase. / 返回操作阶段。
     *
     * @return the operation phase / 操作阶段
     */
    String phase();

    /**
     * Returns the localizable user-message key. / 返回可本地化用户消息键。
     *
     * @return the localizable user-message key / 可本地化用户消息键
     */
    String messageKey();

    /**
     * Returns the default impact level. / 返回默认影响等级。
     *
     * @return the default impact level / 默认影响等级
     */
    FailureSeverityLevel severity();

    /**
     * Returns the conservative default recovery action. / 返回保守的默认恢复动作。
     *
     * @return the conservative default recovery action / 保守的默认恢复动作
     */
    FailureRecoveryAction recoveryAction();
}
