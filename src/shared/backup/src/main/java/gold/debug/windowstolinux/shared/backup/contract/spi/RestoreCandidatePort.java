package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;

/**
 * Platform/deploy seam for staging, checking, committing and recovering restored files. / 暂存、检查、提交和恢复已还原文件的平台及部署接缝。
 */
public interface RestoreCandidatePort {
    /**
     * Stages all non-database content without changing the current release. / 暂存全部非数据库内容且不改变当前发布。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved file evidence / 构造或解析得到的文件证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    FileEvidence stageFiles(RestoreCandidateRequest request) throws BackupException;

    /**
     * Checks every restored component against the candidate database token. / 结合候选数据库令牌检查每个恢复组件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    HealthEvidence verifyComponents(RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken)
            throws BackupException;

    /**
     * Checks the whole-application business health gate. / 检查整应用业务健康门。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    HealthEvidence verifyApplication(RestoreCandidateRequest request, FileEvidence files,
            Optional<String> databaseToken) throws BackupException;

    /**
     * Establishes the stopped-write boundary before a database candidate is activated. / 在数据库候选激活前建立停写边界。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    HealthEvidence prepareCommit(RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken)
            throws BackupException;

    /**
     * Atomically commits the fully verified candidate and retains the previous release. / 原子提交完整已验证候选并保留旧发布。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved commit evidence / 构造或解析得到的提交证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    CommitEvidence commit(RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken)
            throws BackupException;

    /**
     * Stops candidate and newly restored processes before database rollback. / 在数据库回滚前停止候选及新恢复进程。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    HealthEvidence quiesceForRecovery(RestoreCandidateRequest request, Optional<FileEvidence> files)
            throws BackupException;

    /**
     * Removes an uncommitted file candidate and verifies the existing release. / 移除未提交文件候选并验证现有发布。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    RecoveryEvidence recoverExisting(RestoreCandidateRequest request, Optional<FileEvidence> files)
            throws BackupException;

    /**
     * Isolated staged-file evidence. / 隔离暂存文件证据。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param candidateToken candidate token / 候选令牌
     * @param stagedBytes staged bytes / 已暂存字节
     * @param isolated isolated / 隔离
     * @param integrityVerified integrity verified / 完整性已验证
     * @param existingReleaseUntouched existing release untouched / 既有发布Untouched
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record FileEvidence(String candidateId, String candidateToken, long stagedBytes, boolean isolated,
            boolean integrityVerified, boolean existingReleaseUntouched, List<String> evidence) {
        /**
         * Validates bounded stage evidence. / 校验有界暂存证据。
         *
         * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
         * @param candidateToken candidate token / 候选令牌
         * @param stagedBytes staged bytes / 已暂存字节
         * @param isolated isolated / 隔离
         * @param integrityVerified integrity verified / 完整性已验证
         * @param existingReleaseUntouched existing release untouched / 既有发布Untouched
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public FileEvidence {
            candidateId = id(candidateId, "candidateId");
            candidateToken = id(candidateToken, "candidateToken");
            if (stagedBytes < 0)
                throw new IllegalArgumentException("stagedBytes must not be negative");
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Component or whole-application health evidence. / 组件或整应用健康证据。
     *
     * @param healthy healthy / 健康
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record HealthEvidence(boolean healthy, List<String> evidence) {
        /**
         * Validates bounded health evidence. / 校验有界健康证据。
         *
         * @param healthy healthy / 健康
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public HealthEvidence {
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Commit evidence retaining a rollback point. / 保留回滚点的提交证据。
     *
     * @param committed committed / 已提交
     * @param previousReleaseRetained previous release retained / 此前发布已保留
     * @param activeReleaseToken active release token / 活跃发布令牌
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record CommitEvidence(boolean committed, boolean previousReleaseRetained, String activeReleaseToken,
            List<String> evidence) {
        /**
         * Validates bounded commit evidence. / 校验有界提交证据。
         *
         * @param committed committed / 已提交
         * @param previousReleaseRetained previous release retained / 此前发布已保留
         * @param activeReleaseToken active release token / 活跃发布令牌
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public CommitEvidence {
            activeReleaseToken = id(activeReleaseToken, "activeReleaseToken");
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Failed-candidate cleanup and existing-release verification evidence. / 失败候选清理及现有发布验证证据。
     *
     * @param candidateRemoved candidate removed / 候选已移除
     * @param existingReleaseVerified existing release verified / 既有发布已验证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RecoveryEvidence(boolean candidateRemoved, boolean existingReleaseVerified, List<String> evidence) {
        /**
         * Validates bounded recovery evidence. / 校验有界恢复证据。
         *
         * @param candidateRemoved candidate removed / 候选已移除
         * @param existingReleaseVerified existing release verified / 既有发布已验证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public RecoveryEvidence {
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Checks id syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查标识语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return id text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String id(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    /**
     * Checks returned evidence against the exact requested operation.
     * <p>按精确请求操作检查返回证据。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<String> validatedEvidence(List<String> values) {
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64)
            throw new IllegalArgumentException("restore evidence is incomplete");
        List<String> result = values.stream().map(value -> {
            String item = Objects.requireNonNull(value, "evidence item").trim();
            if (item.isEmpty() || item.length() > 512 || item.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("restore evidence item is invalid");
            }
            return item;
        }).distinct().toList();
        if (result.size() != values.size())
            throw new IllegalArgumentException("restore evidence contains duplicates");
        return result;
    }
}
