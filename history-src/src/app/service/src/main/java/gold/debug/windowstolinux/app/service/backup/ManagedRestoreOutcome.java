package gold.debug.windowstolinux.app.service.backup;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreResult;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;

/**
 * Product restore result including whether the desktop adopted the verified remote graph. / 包含桌面是否接管已验证远端图的产品恢复结果。
 *
 * @param targetServerId target server id / 目标服务器标识
 * @param restore restore / 恢复
 * @param controlState control state / 控件状态
 * @param localFailure local failure / 本地失败
 * @param warnings warnings / 警告集合
 */
public record ManagedRestoreOutcome(String targetServerId, BackupRestoreResult restore,
        ManagedRestoreControlState controlState, Optional<FailureDescriptor> localFailure, List<String> warnings) {
    /**
     * Keeps a successful remote result distinct from a later local persistence failure. / 区分远端成功与随后本地持久化失败。
     *
     * @param targetServerId target server id / 目标服务器标识
     * @param restore restore / 恢复
     * @param controlState control state / 控件状态
     * @param localFailure local failure / 本地失败
     * @param warnings warnings / 警告集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedRestoreOutcome {
        targetServerId = Objects.requireNonNull(targetServerId, "targetServerId");
        restore = Objects.requireNonNull(restore, "restore");
        controlState = Objects.requireNonNull(controlState, "controlState");
        localFailure = Objects.requireNonNull(localFailure, "localFailure");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (warnings.size() > 16 || warnings.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("restore warnings are invalid");
        }
        boolean remoteSucceeded = restore
                .status() == gold.debug.windowstolinux.shared.backup.restore.BackupRestoreStatus.SUCCEEDED;
        if (!remoteSucceeded && (controlState != ManagedRestoreControlState.FAILED || localFailure.isPresent())
                || remoteSucceeded && controlState == ManagedRestoreControlState.UPDATED && localFailure.isPresent()
                || remoteSucceeded && controlState == ManagedRestoreControlState.DEFERRED_SOURCE_RETAINED
                        && localFailure.isPresent()
                || remoteSucceeded && controlState == ManagedRestoreControlState.FAILED && localFailure.isEmpty()) {
            throw new IllegalArgumentException("local restore adoption evidence is inconsistent");
        }
    }
}
