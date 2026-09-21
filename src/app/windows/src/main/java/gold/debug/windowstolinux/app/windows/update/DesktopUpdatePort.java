package gold.debug.windowstolinux.app.windows.update;

import java.util.List;
import java.util.Objects;

/**
 * Split main-process and independent-worker seam for one desktop update. / 单次桌面更新的主进程与独立执行器分阶段接缝。
 */
public interface DesktopUpdatePort {
    /**
     * Stops accepting work and waits for every task to become safe or recoverable. / 停止接收工作并等待全部任务进入安全或可恢复状态。
     *
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    StepEvidence quiesceTasks() throws DesktopUpdateException;

    /**
     * Backs up the current program, SQLite file, data location and credential mode. / 备份当前程序、SQLite 文件、数据位置及凭据模式。
     *
     * @param update update / 更新
     * @return constructed or resolved backup evidence / 构造或解析得到的备份证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    BackupEvidence backupCurrent(DesktopUpdateVerification update) throws DesktopUpdateException;

    /**
     * Called by the worker to verify its independent identity and the main-process exit. / 由执行器验证其独立身份及主进程退出。
     *
     * @param update update / 更新
     * @param backup the local backup page state / 本地备份页面状态
     * @return constructed or resolved handoff evidence / 构造或解析得到的交接证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    HandoffEvidence verifyIndependentUpdater(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /**
     * Replaces program files only after an independent handoff. / 仅在独立交接后替换程序文件。
     *
     * @param update update / 更新
     * @param backup the local backup page state / 本地备份页面状态
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    StepEvidence replaceProgram(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /**
     * Migrates the existing SQLite database with the new program's migrator. / 使用新程序的迁移器迁移现有 SQLite 数据库。
     *
     * @param update update / 更新
     * @param backup the local backup page state / 本地备份页面状态
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    StepEvidence migrateDatabase(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /**
     * Starts and verifies the new program against the preserved data location and credential mode. / 使用保留的数据位置及凭据模式启动并验证新程序。
     *
     * @param update update / 更新
     * @param backup the local backup page state / 本地备份页面状态
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    StepEvidence startAndVerify(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /**
     * Restores the old program and pre-migration SQLite backup as one pair. / 将旧程序及迁移前 SQLite 备份作为一对恢复。
     *
     * @param backup the local backup page state / 本地备份页面状态
     * @return constructed or resolved rollback evidence / 构造或解析得到的回滚证据
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    RollbackEvidence rollbackProgramAndDatabase(BackupEvidence backup) throws DesktopUpdateException;

    /**
     * Generic verified update step. / 通用已验证更新步骤。
     *
     * @param completed completed / 已完成
     * @param verified verified / 已验证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record StepEvidence(boolean completed, boolean verified, List<String> evidence) {
        /**
         * Validates bounded evidence. / 校验有界证据。
         *
         * @param completed completed / 已完成
         * @param verified verified / 已验证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public StepEvidence { evidence = validatedEvidence(evidence); }
    }

    /**
     * Program and database backup point. / 程序及数据库备份点。
     *
     * @param backupToken backup token / 备份令牌
     * @param programBackedUp program backed up / 程序已备份Up
     * @param databaseBackedUp database backed up / 数据库已备份Up
     * @param dataLocationPreserved data location preserved / 数据位置已保留
     * @param credentialModePreserved credential mode preserved / 凭据模式已保留
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record BackupEvidence(
            String backupToken,
            boolean programBackedUp,
            boolean databaseBackedUp,
            boolean dataLocationPreserved,
            boolean credentialModePreserved,
            List<String> evidence
    ) {
        /**
         * Validates paired backup evidence. / 校验成对备份证据。
         *
         * @param backupToken backup token / 备份令牌
         * @param programBackedUp program backed up / 程序已备份Up
         * @param databaseBackedUp database backed up / 数据库已备份Up
         * @param dataLocationPreserved data location preserved / 数据位置已保留
         * @param credentialModePreserved credential mode preserved / 凭据模式已保留
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public BackupEvidence {
            backupToken = identifier(backupToken, "backupToken");
            evidence = validatedEvidence(evidence);
        }
    }

    /**
     * Independent updater handoff evidence. / 独立更新器交接证据。
     *
     * @param independentUpdaterVerified independent updater verified / 独立更新程序已验证
     * @param mainProcessExited main process exited / 主进程Exited
     * @param handoffAuthenticated handoff authenticated / 交接已认证
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record HandoffEvidence(
            boolean independentUpdaterVerified,
            boolean mainProcessExited,
            boolean handoffAuthenticated,
            List<String> evidence
    ) {
        /**
         * Validates handoff evidence. / 校验交接证据。
         *
         * @param independentUpdaterVerified independent updater verified / 独立更新程序已验证
         * @param mainProcessExited main process exited / 主进程Exited
         * @param handoffAuthenticated handoff authenticated / 交接已认证
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public HandoffEvidence { evidence = validatedEvidence(evidence); }
    }

    /**
     * Paired rollback evidence. / 成对回滚证据。
     *
     * @param programRestored program restored / 程序已恢复
     * @param databaseRestored database restored / 数据库已恢复
     * @param previousVersionHealthy previous version healthy / 此前版本健康
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    record RollbackEvidence(
            boolean programRestored,
            boolean databaseRestored,
            boolean previousVersionHealthy,
            List<String> evidence
    ) {
        /**
         * Validates rollback evidence. / 校验回滚证据。
         *
         * @param programRestored program restored / 程序已恢复
         * @param databaseRestored database restored / 数据库已恢复
         * @param previousVersionHealthy previous version healthy / 此前版本健康
         * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
         */
        public RollbackEvidence { evidence = validatedEvidence(evidence); }
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
        if (values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("update evidence is incomplete");
        List<String> result = values.stream().map(value -> {
            String item = Objects.requireNonNull(value, "evidence item").trim();
            if (item.isEmpty() || item.length() > 512 || item.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("update evidence item is invalid");
            }
            return item;
        }).distinct().toList();
        if (result.size() != values.size()) throw new IllegalArgumentException("update evidence has duplicates");
        return result;
    }
}
