package gold.debug.windowstolinux.shared.deploy.contract.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deploy-owned seam for candidate activation, two-level health, commit and rollback. / 由 deploy 持有的候选激活、两级健康、提交和回滚接缝。 */
public interface RestoreDeploymentPort {
    /** Starts candidates in dependency order and verifies every component. / 按依赖顺序启动候选并验证每个组件。 */
    HealthEvidence verifyComponents(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /** Verifies the declared whole-application health gate. / 验证已声明的整应用健康门。 */
    HealthEvidence verifyApplication(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /** Stops the old graph and proves the database activation write boundary. / 停止旧图并证明数据库激活停写边界。 */
    HealthEvidence prepareCommit(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /** Commits the healthy candidate while retaining a rollback point. / 提交健康候选并保留回滚点。 */
    CommitEvidence commit(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /** Stops candidate and newly restored processes before database rollback. / 在数据库回滚前停止候选及新恢复进程。 */
    HealthEvidence quiesceForRecovery(RestoreDeploymentRequest request);

    /** Rolls back any attempted activation and verifies the existing release. / 回滚任何已尝试激活并验证现有发布。 */
    RecoveryEvidence recoverExisting(RestoreDeploymentRequest request);

    /** Component or whole-application health evidence. / 组件或整应用健康证据。 */
    record HealthEvidence(boolean healthy, List<String> evidence) {
        /** Validates bounded evidence. / 校验有界证据。 */
        public HealthEvidence { evidence = checkedEvidence(evidence); }
    }

    /** Commit evidence retaining the exact old release. / 保留精确旧发布的提交证据。 */
    record CommitEvidence(boolean committed, boolean previousReleaseRetained,
                          String activeReleaseToken, List<String> evidence) {
        /** Validates the release token and evidence. / 校验发布令牌和证据。 */
        public CommitEvidence {
            activeReleaseToken = token(activeReleaseToken, "activeReleaseToken");
            evidence = checkedEvidence(evidence);
        }
    }

    /** Rollback and current-release verification evidence. / 回滚及当前发布验证证据。 */
    record RecoveryEvidence(boolean existingReleaseVerified, List<String> evidence) {
        /** Validates bounded evidence. / 校验有界证据。 */
        public RecoveryEvidence { evidence = checkedEvidence(evidence); }
    }

    private static String token(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static List<String> checkedEvidence(List<String> values) {
        values = List.copyOf(Objects.requireNonNull(values, "evidence"));
        if (values.isEmpty() || values.size() > 64 || values.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("restore deployment evidence is invalid");
        }
        return values;
    }
}
