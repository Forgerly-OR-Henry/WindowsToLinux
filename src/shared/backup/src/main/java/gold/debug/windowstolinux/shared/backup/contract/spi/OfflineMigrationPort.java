package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;

import java.util.List;
import java.util.Objects;

/**
 * Platform seam for an explicitly offline, manually switched migration. / 显式离线且人工切流迁移的平台接缝。
 */
public interface OfflineMigrationPort {
    /**
     * Verifies target ownership, capacity, ports and restore compatibility before transfer. / 在传输前验证目标归属、容量、端口及恢复兼容性。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved target preflight evidence / 构造或解析得到的目标预检证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request) throws BackupException;

    /**
     * Copies the first immutable baseline while the source remains active. / 在源端仍运行时复制首个不可变基线。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    SyncEvidence initialSync(OfflineMigrationRequest request) throws BackupException;

    /**
     * Stops source writes and returns a verifiable recovery token. / 停止源端写入并返回可验证恢复令牌。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved source quiesce evidence / 构造或解析得到的源码停写证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request) throws BackupException;

    /**
     * Copies the final delta only after source writes are proven stopped. / 仅在源端写入已确认停止后复制最终增量。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param initial initial / 初始
     * @param quiesced quiesced / 已停写
     * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    SyncEvidence finalSync(
            OfflineMigrationRequest request, SyncEvidence initial, SourceQuiesceEvidence quiesced)
            throws BackupException;

    /**
     * Restores and verifies the complete target candidate without switching external traffic. / 恢复并验证完整目标候选且不切换外部流量。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param finalSync final sync / 最终同步
     * @return constructed or resolved target candidate evidence / 构造或解析得到的目标候选证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    TargetCandidateEvidence restoreAndVerifyTarget(
            OfflineMigrationRequest request, SyncEvidence finalSync) throws BackupException;

    /**
     * Removes only the owned uncommitted target candidate. / 仅移除有归属的未提交目标候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) throws BackupException;

    /**
     * Restores and verifies the source after a failed stopped-write migration. / 在停写迁移失败后恢复并验证源端。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param quiesced quiesced / 已停写
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    RecoveryEvidence recoverSource(
            OfflineMigrationRequest request, SourceQuiesceEvidence quiesced) throws BackupException;

    /**
     * Target preflight evidence. / 目标前置检查证据。
     *
     * @param ownershipVerified ownership verified / 归属已验证
     * @param portsAvailable ports available / 端口集合可用
     * @param restoreCompatible restore compatible / 恢复兼容
     * @param availableBytes available bytes / 可用字节
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record TargetPreflightEvidence(
            boolean ownershipVerified,
            boolean portsAvailable,
            boolean restoreCompatible,
            long availableBytes,
            List<String> evidence
    ) {
        /**
         * Validates bounded evidence and capacity. / 校验有界证据及容量。
         *
         * @param ownershipVerified ownership verified / 归属已验证
         * @param portsAvailable ports available / 端口集合可用
         * @param restoreCompatible restore compatible / 恢复兼容
         * @param availableBytes available bytes / 可用字节
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public TargetPreflightEvidence {
            if (availableBytes < 0) throw new IllegalArgumentException("availableBytes must not be negative");
            evidence = checkedEvidence(evidence);
        }
    }

    /**
     * One digest-bound synchronization result. / 单个摘要绑定的同步结果。
     *
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param contentSha256 content sha 256 / 内容SHA256
     * @param digestVerified digest verified / 摘要已验证
     * @param sourceWritesStopped source writes stopped / 源码写入集合已停止
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record SyncEvidence(
            long byteCount,
            String contentSha256,
            boolean digestVerified,
            boolean sourceWritesStopped,
            List<String> evidence
    ) {
        /**
         * Validates synchronization identity and evidence. / 校验同步身份及证据。
         *
         * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
         * @param contentSha256 content sha 256 / 内容SHA256
         * @param digestVerified digest verified / 摘要已验证
         * @param sourceWritesStopped source writes stopped / 源码写入集合已停止
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public SyncEvidence {
            if (byteCount < 1) throw new IllegalArgumentException("byteCount must be positive");
            contentSha256 = digest(contentSha256, "contentSha256");
            evidence = checkedEvidence(evidence);
        }
    }

    /**
     * Source stopped-write and recovery-token evidence. / 源端停写及恢复令牌证据。
     *
     * @param writesStopped writes stopped / 写入集合已停止
     * @param noActiveWriters no active writers / 未活跃写入器集合
     * @param recoveryToken recovery token / 恢复令牌
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record SourceQuiesceEvidence(
            boolean writesStopped,
            boolean noActiveWriters,
            String recoveryToken,
            List<String> evidence
    ) {
        /**
         * Requires a bounded recovery token. / 要求有界恢复令牌。
         *
         * @param writesStopped writes stopped / 写入集合已停止
         * @param noActiveWriters no active writers / 未活跃写入器集合
         * @param recoveryToken recovery token / 恢复令牌
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public SourceQuiesceEvidence {
            recoveryToken = identifier(recoveryToken, "recoveryToken");
            evidence = checkedEvidence(evidence);
        }
    }

    /**
     * Fully restored target candidate evidence before manual traffic switching. / 人工切流前完整恢复的目标候选证据。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     * @param componentsHealthy components healthy / 组件集合健康
     * @param applicationHealthy application healthy / 应用健康
     * @param externalTrafficUnchanged external traffic unchanged / 外部流量Unchanged
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record TargetCandidateEvidence(
            String candidateId,
            boolean componentsHealthy,
            boolean applicationHealthy,
            boolean externalTrafficUnchanged,
            List<String> evidence
    ) {
        /**
         * Validates target candidate evidence. / 校验目标候选证据。
         *
         * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
         * @param componentsHealthy components healthy / 组件集合健康
         * @param applicationHealthy application healthy / 应用健康
         * @param externalTrafficUnchanged external traffic unchanged / 外部流量Unchanged
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public TargetCandidateEvidence {
            candidateId = identifier(candidateId, "candidateId");
            evidence = checkedEvidence(evidence);
        }
    }

    /**
     * One cleanup or source recovery result. / 单次清理或源端恢复结果。
     *
     * @param completed completed / 已完成
     * @param verified verified / 已验证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RecoveryEvidence(boolean completed, boolean verified, List<String> evidence) {
        /**
         * Validates recovery evidence. / 校验恢复证据。
         *
         * @param completed completed / 已完成
         * @param verified verified / 已验证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public RecoveryEvidence {
            evidence = checkedEvidence(evidence);
        }
    }

    /**
     * Computes or retrieves content identity for independent evidence checks.
     * <p>计算或取得用于独立证据检查的内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return or retrieves content identity for independent evidence checks / 或取得用于独立证据检查的内容身份
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String digest(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identifier(String value, String field) {
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
        Objects.requireNonNull(values, "evidence");
        if (values.isEmpty() || values.size() > 64) {
            throw new IllegalArgumentException("migration evidence is incomplete");
        }
        List<String> result = values.stream().map(value -> {
            String item = Objects.requireNonNull(value, "evidence item").trim();
            if (item.isEmpty() || item.length() > 512 || item.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("migration evidence item is invalid");
            }
            return item;
        }).distinct().toList();
        if (result.size() != values.size()) throw new IllegalArgumentException("migration evidence has duplicates");
        return result;
    }
}
