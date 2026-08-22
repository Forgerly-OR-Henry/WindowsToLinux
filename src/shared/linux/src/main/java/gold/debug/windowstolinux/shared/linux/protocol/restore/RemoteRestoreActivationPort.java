package gold.debug.windowstolinux.shared.linux.protocol.restore;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fixed Linux capability for one staged restore activation transaction. / 单个已暂存恢复激活事务的固定 Linux 能力。 */
public interface RemoteRestoreActivationPort {
    /** Reads occupied TCP ports and managed-root facts without mutation. / 只读采集已占用 TCP 端口及受管根事实。 */
    PreflightEvidence inspectRestoreActivation(String applicationId, long requiredBytes) throws LinuxOperationException;

    /** Extracts and starts either isolated candidates or one tentative short-stop graph. / 提取并启动隔离候选或一个暂定短停机图。 */
    StepEvidence startRestoreActivation(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /** Verifies every candidate component in dependency order. / 按依赖顺序验证每个候选组件。 */
    StepEvidence verifyRestoreComponents(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /** Verifies the candidate whole-application health gate. / 验证候选整应用健康门。 */
    StepEvidence verifyRestoreApplication(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /** Commits formal ports and re-verifies component and application health. / 提交正式端口并重新验证组件及整应用健康。 */
    CommitEvidence commitRestoreActivation(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /** Removes candidate effects and verifies the exact previous graph. / 移除候选影响并验证精确旧图。 */
    RecoveryEvidence recoverRestoreActivation(RemoteRestoreActivationRequest request) throws LinuxOperationException;

    /** Read-only target facts. / 只读目标事实。 */
    record PreflightEvidence(boolean managedRootWritable, boolean foreignApplicationConflict,
                             long availableBytes, Set<Integer> occupiedTcpPorts, List<String> evidence) {
        /** Validates bounded target evidence. / 校验有界目标证据。 */
        public PreflightEvidence {
            if (availableBytes < 0) throw new IllegalArgumentException("availableBytes is invalid");
            occupiedTcpPorts = Set.copyOf(Objects.requireNonNull(occupiedTcpPorts, "occupiedTcpPorts"));
            if (occupiedTcpPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
                throw new IllegalArgumentException("occupiedTcpPorts is invalid");
            }
            evidence = checked(evidence);
        }
    }

    /** One activation or health step. / 单个激活或健康步骤。 */
    record StepEvidence(boolean completed, List<String> evidence) {
        /** Validates bounded evidence. / 校验有界证据。 */
        public StepEvidence { evidence = checked(evidence); }
    }

    /** Final formal activation evidence. / 最终正式激活证据。 */
    record CommitEvidence(boolean committed, boolean previousReleaseRetained,
                          boolean formalComponentsHealthy, boolean formalApplicationHealthy,
                          String activeReleaseToken, List<String> evidence) {
        /** Validates the formal result. / 校验正式结果。 */
        public CommitEvidence {
            activeReleaseToken = Objects.requireNonNull(activeReleaseToken, "activeReleaseToken").trim();
            if (!activeReleaseToken.matches("[a-z0-9][a-z0-9-]{0,79}")) {
                throw new IllegalArgumentException("activeReleaseToken is invalid");
            }
            evidence = checked(evidence);
        }
    }

    /** Candidate cleanup and old-graph verification. / 候选清理及旧图验证。 */
    record RecoveryEvidence(boolean candidateRemoved, boolean previousGraphVerified, List<String> evidence) {
        /** Validates bounded evidence. / 校验有界证据。 */
        public RecoveryEvidence { evidence = checked(evidence); }
    }

    private static List<String> checked(List<String> values) {
        values = List.copyOf(Objects.requireNonNull(values, "evidence"));
        if (values.isEmpty() || values.size() > 64 || values.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("restore activation evidence is invalid");
        }
        return values;
    }
}
