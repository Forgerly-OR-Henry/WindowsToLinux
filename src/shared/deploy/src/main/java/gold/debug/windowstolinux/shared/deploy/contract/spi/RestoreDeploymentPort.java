package gold.debug.windowstolinux.shared.deploy.contract.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deploy-owned seam for candidate activation, two-level health, commit and rollback. / 由 deploy 持有的候选激活、两级健康、提交和回滚接缝。
 */
public interface RestoreDeploymentPort {
    /**
     * Starts candidates in dependency order and verifies every component. / 按依赖顺序启动候选并验证每个组件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    HealthEvidence verifyComponents(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /**
     * Verifies the declared whole-application health gate. / 验证已声明的整应用健康门。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    HealthEvidence verifyApplication(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /**
     * Stops the old graph and proves the database activation write boundary. / 停止旧图并证明数据库激活停写边界。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    HealthEvidence prepareCommit(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /**
     * Commits the healthy candidate while retaining a rollback point. / 提交健康候选并保留回滚点。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved commit evidence / 构造或解析得到的提交证据
     */
    CommitEvidence commit(RestoreDeploymentRequest request, Optional<String> databaseToken);

    /**
     * Stops candidate and newly restored processes before database rollback. / 在数据库回滚前停止候选及新恢复进程。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    HealthEvidence quiesceForRecovery(RestoreDeploymentRequest request);

    /**
     * Rolls back any attempted activation and verifies the existing release. / 回滚任何已尝试激活并验证现有发布。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     */
    RecoveryEvidence recoverExisting(RestoreDeploymentRequest request);

    /**
     * Component or whole-application health evidence. / 组件或整应用健康证据。
     *
     * @param healthy healthy / 健康
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record HealthEvidence(boolean healthy, List<String> evidence) {
        /**
         * Validates bounded evidence. / 校验有界证据。
         *
         * @param healthy healthy / 健康
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public HealthEvidence { evidence = checkedEvidence(evidence); }
    }

    /**
     * Commit evidence retaining the exact old release. / 保留精确旧发布的提交证据。
     *
     * @param committed committed / 已提交
     * @param previousReleaseRetained previous release retained / 此前发布已保留
     * @param activeReleaseToken active release token / 活跃发布令牌
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record CommitEvidence(boolean committed, boolean previousReleaseRetained,
                          String activeReleaseToken, List<String> evidence) {
        /**
         * Validates the release token and evidence. / 校验发布令牌和证据。
         *
         * @param committed committed / 已提交
         * @param previousReleaseRetained previous release retained / 此前发布已保留
         * @param activeReleaseToken active release token / 活跃发布令牌
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public CommitEvidence {
            activeReleaseToken = token(activeReleaseToken, "activeReleaseToken");
            evidence = checkedEvidence(evidence);
        }
    }

    /**
     * Rollback and current-release verification evidence. / 回滚及当前发布验证证据。
     *
     * @param existingReleaseVerified existing release verified / 既有发布已验证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RecoveryEvidence(boolean existingReleaseVerified, List<String> evidence) {
        /**
         * Validates bounded evidence. / 校验有界证据。
         *
         * @param existingReleaseVerified existing release verified / 既有发布已验证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public RecoveryEvidence { evidence = checkedEvidence(evidence); }
    }

    /**
     * Checks token syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查令牌语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return token text / 令牌文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String token(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    /**
     * Checks operation evidence before it is accepted as an authoritative result.
     * <p>在将操作证据接受为权威结果前完成检查。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<String> checkedEvidence(List<String> values) {
        values = List.copyOf(Objects.requireNonNull(values, "evidence"));
        if (values.isEmpty() || values.size() > 64 || values.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("restore deployment evidence is invalid");
        }
        return values;
    }
}
