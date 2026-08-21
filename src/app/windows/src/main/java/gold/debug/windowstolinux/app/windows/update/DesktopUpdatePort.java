package gold.debug.windowstolinux.app.windows.update;

import java.util.List;
import java.util.Objects;

/** Split main-process and independent-worker seam for one desktop update. / 单次桌面更新的主进程与独立执行器分阶段接缝。 */
public interface DesktopUpdatePort {
    /** Stops accepting work and waits for every task to become safe or recoverable. / 停止接收工作并等待全部任务进入安全或可恢复状态。 */
    StepEvidence quiesceTasks() throws DesktopUpdateException;

    /** Backs up the current program, SQLite file, data location and credential mode. / 备份当前程序、SQLite 文件、数据位置及凭据模式。 */
    BackupEvidence backupCurrent(DesktopUpdateVerification update) throws DesktopUpdateException;

    /** Called by the worker to verify its independent identity and the main-process exit. / 由执行器验证其独立身份及主进程退出。 */
    HandoffEvidence verifyIndependentUpdater(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /** Replaces program files only after an independent handoff. / 仅在独立交接后替换程序文件。 */
    StepEvidence replaceProgram(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /** Migrates the existing SQLite database with the new program's migrator. / 使用新程序的迁移器迁移现有 SQLite 数据库。 */
    StepEvidence migrateDatabase(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /** Starts and verifies the new program against the preserved data location and credential mode. / 使用保留的数据位置及凭据模式启动并验证新程序。 */
    StepEvidence startAndVerify(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException;

    /** Restores the old program and pre-migration SQLite backup as one pair. / 将旧程序及迁移前 SQLite 备份作为一对恢复。 */
    RollbackEvidence rollbackProgramAndDatabase(BackupEvidence backup) throws DesktopUpdateException;

    /** Generic verified update step. / 通用已验证更新步骤。 */
    record StepEvidence(boolean completed, boolean verified, List<String> evidence) {
        /** Validates bounded evidence. / 校验有界证据。 */
        public StepEvidence { evidence = validatedEvidence(evidence); }
    }

    /** Program and database backup point. / 程序及数据库备份点。 */
    record BackupEvidence(
            String backupToken,
            boolean programBackedUp,
            boolean databaseBackedUp,
            boolean dataLocationPreserved,
            boolean credentialModePreserved,
            List<String> evidence
    ) {
        /** Validates paired backup evidence. / 校验成对备份证据。 */
        public BackupEvidence {
            backupToken = identifier(backupToken, "backupToken");
            evidence = validatedEvidence(evidence);
        }
    }

    /** Independent updater handoff evidence. / 独立更新器交接证据。 */
    record HandoffEvidence(
            boolean independentUpdaterVerified,
            boolean mainProcessExited,
            boolean handoffAuthenticated,
            List<String> evidence
    ) {
        /** Validates handoff evidence. / 校验交接证据。 */
        public HandoffEvidence { evidence = validatedEvidence(evidence); }
    }

    /** Paired rollback evidence. / 成对回滚证据。 */
    record RollbackEvidence(
            boolean programRestored,
            boolean databaseRestored,
            boolean previousVersionHealthy,
            List<String> evidence
    ) {
        /** Validates rollback evidence. / 校验回滚证据。 */
        public RollbackEvidence { evidence = validatedEvidence(evidence); }
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

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
