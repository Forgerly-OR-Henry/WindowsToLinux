package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.shared.backup.restore.BackupRestoreResult;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

/** Product restore result including whether the desktop adopted the verified remote graph. / 包含桌面是否接管已验证远端图的产品恢复结果。 */
public record ManagedRestoreOutcome(
        String targetServerId,
        BackupRestoreResult restore,
        ManagedRestoreControlState controlState,
        Optional<FailureDescriptor> localFailure,
        List<String> warnings
) {
    /** Keeps a successful remote result distinct from a later local persistence failure. / 区分远端成功与随后本地持久化失败。 */
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
        boolean remoteSucceeded = restore.status()
                == gold.debug.windowstolinux.shared.backup.restore.BackupRestoreStatus.SUCCEEDED;
        if (!remoteSucceeded && (controlState != ManagedRestoreControlState.FAILED || localFailure.isPresent())
                || remoteSucceeded && controlState == ManagedRestoreControlState.UPDATED && localFailure.isPresent()
                || remoteSucceeded && controlState == ManagedRestoreControlState.DEFERRED_SOURCE_RETAINED
                && localFailure.isPresent()
                || remoteSucceeded && controlState == ManagedRestoreControlState.FAILED && localFailure.isEmpty()) {
            throw new IllegalArgumentException("local restore adoption evidence is inconsistent");
        }
    }
}
