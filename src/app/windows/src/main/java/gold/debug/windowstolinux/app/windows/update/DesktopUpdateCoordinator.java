package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates an independently handed-off program and SQLite update transaction. / 编排已独立交接的程序及 SQLite 更新事务。
 */
public final class DesktopUpdateCoordinator {
    /**
     * Network port number in the reviewed endpoint.
     * <p>已审阅端点中的网络端口号。
     */
    private final DesktopUpdatePort port;

    /**
     * Creates an updater coordinator over one platform implementation. / 基于一个平台实现创建更新协调器。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateCoordinator(DesktopUpdatePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /**
     * Quiesces the main process and creates a paired backup without replacing any running file. / 停收主进程并创建成对备份，不替换任何运行中文件。
     *
     * @param update update / 更新
     * @return constructed or resolved desktop update preparation result / 构造或解析得到的Desktop更新准备结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdatePreparationResult prepare(DesktopUpdateVerification update) {
        Objects.requireNonNull(update, "update");
        OperationIdentity operation = OperationIdentity.create();
        List<DesktopUpdateEvent> events = new ArrayList<>();
        DesktopUpdateState state = DesktopUpdateState.TASKS_QUIESCED;
        try {
            requireStep(port.quiesceTasks(), DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "desktop tasks did not reach a safe terminal or recoverable state");
            events.add(success(state, "new tasks stopped and existing tasks reached safe states"));

            state = DesktopUpdateState.BACKUP_CREATED;
            DesktopUpdatePort.BackupEvidence created = port.backupCurrent(update);
            if (!created.programBackedUp() || !created.databaseBackedUp()
                    || !created.dataLocationPreserved() || !created.credentialModePreserved()) {
                throw DesktopUpdateException.create(DesktopUpdateFailureType.TRANSACTION_FAILED,
                        "program, SQLite, data location and credential mode were not backed up as one update point");
            }
            events.add(success(state, created.evidence()));
            DesktopUpdateHandoff handoff = new DesktopUpdateHandoff(operation, update, created, events);
            return new DesktopUpdatePreparationResult(operation, DesktopUpdatePreparationStatus.READY_FOR_HANDOFF,
                    update.version(), events, Optional.of(handoff), Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor failure = failure(exception).withOperationIdentity(operation).withRecovery(
                    FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_REQUIRED);
            if (events.isEmpty() || events.get(events.size() - 1).state() != state
                    || events.get(events.size() - 1).succeeded()) {
                events.add(new DesktopUpdateEvent(state, false, failure.diagnostic()));
            }
            return new DesktopUpdatePreparationResult(operation,
                    DesktopUpdatePreparationStatus.PRECONDITION_REJECTED, update.version(), events,
                    Optional.empty(), Optional.of(failure));
        }
    }

    /**
     * Runs only after an external updater receives the handoff and observes the main process exit. / 仅在外部更新器收到交接并确认主进程退出后执行。
     *
     * @param handoff handoff / 交接
     * @return constructed or resolved desktop update result / 构造或解析得到的Desktop更新结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateResult apply(DesktopUpdateHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        OperationIdentity operation = handoff.operationIdentity();
        DesktopUpdateVerification update = handoff.update();
        DesktopUpdatePort.BackupEvidence backup = handoff.backup();
        List<DesktopUpdateEvent> events = new ArrayList<>(handoff.preparationEvents());
        DesktopUpdateState state = DesktopUpdateState.INDEPENDENT_UPDATER_VERIFIED;
        boolean replacementAttempted = false;
        try {

            DesktopUpdatePort.HandoffEvidence worker = port.verifyIndependentUpdater(update, backup);
            if (!worker.independentUpdaterVerified() || !worker.mainProcessExited()
                    || !worker.handoffAuthenticated()) {
                throw DesktopUpdateException.create(DesktopUpdateFailureType.TRANSACTION_FAILED,
                        "independent updater identity, main process exit or handoff authenticity could not be verified");
            }
            events.add(success(state, worker.evidence()));

            state = DesktopUpdateState.PROGRAM_REPLACED;
            replacementAttempted = true;
            DesktopUpdatePort.StepEvidence replaced = port.replaceProgram(update, backup);
            requireStep(replaced, DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "signed program files could not be replaced and verified");
            events.add(success(state, replaced.evidence()));

            state = DesktopUpdateState.DATABASE_MIGRATED;
            DesktopUpdatePort.StepEvidence migrated = port.migrateDatabase(update, backup);
            requireStep(migrated, DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "SQLite migration did not commit and verify successfully");
            events.add(success(state, migrated.evidence()));

            state = DesktopUpdateState.NEW_VERSION_HEALTHY;
            DesktopUpdatePort.StepEvidence healthy = port.startAndVerify(update, backup);
            requireStep(healthy, DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "new desktop version did not pass startup health verification");
            events.add(success(state, healthy.evidence()));
            return new DesktopUpdateResult(operation, DesktopUpdateStatus.SUCCEEDED, update.version(), events,
                    Optional.of(backup.backupToken()), Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor original = failure(exception).withOperationIdentity(operation);
            if (events.isEmpty() || events.get(events.size() - 1).state() != state
                    || events.get(events.size() - 1).succeeded()) {
                events.add(new DesktopUpdateEvent(state, false, original.diagnostic()));
            }
            if (!replacementAttempted) {
                FailureDescriptor safe = original.withRecovery(
                        FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_REQUIRED);
                return new DesktopUpdateResult(operation, DesktopUpdateStatus.PRECONDITION_REJECTED,
                        update.version(), events, Optional.of(backup.backupToken()),
                        Optional.of(safe));
            }
            return rollback(update, operation, events, backup, original);
        }
    }

    /**
     * Builds desktop update result from the supplied rollback inputs.
     * <p>根据所提供回滚输入构建Desktop更新结果。
     *
     * @param update update / 更新
     * @param operation operation / 操作
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param backup the local backup page state / 本地备份页面状态
     * @param original original / 原始
     * @return desktop update result from the supplied rollback inputs / 根据所提供回滚输入构建Desktop更新结果
     */
    private DesktopUpdateResult rollback(
            DesktopUpdateVerification update,
            OperationIdentity operation,
            List<DesktopUpdateEvent> events,
            DesktopUpdatePort.BackupEvidence backup,
            FailureDescriptor original
    ) {
        try {
            DesktopUpdatePort.RollbackEvidence rollback = port.rollbackProgramAndDatabase(backup);
            if (!rollback.programRestored() || !rollback.databaseRestored() || !rollback.previousVersionHealthy()) {
                throw DesktopUpdateException.create(DesktopUpdateFailureType.ROLLBACK_FAILED,
                        "program and pre-migration SQLite backup were not both restored and verified");
            }
            events.add(success(DesktopUpdateState.ROLLBACK_VERIFIED, rollback.evidence()));
            FailureDescriptor safe = original.withRecovery(
                    FailureRecoveryAction.ROLLBACK, FailureRecoveryDisposition.SUCCEEDED);
            return new DesktopUpdateResult(operation, DesktopUpdateStatus.FAILED_ROLLED_BACK, update.version(),
                    events, Optional.of(backup.backupToken()), Optional.of(safe));
        } catch (Exception exception) {
            FailureDescriptor failed = failure(DesktopUpdateException.create(DesktopUpdateFailureType.ROLLBACK_FAILED,
                    "program and SQLite rollback could not be verified", exception))
                    .withOperationIdentity(operation)
                    .withRecovery(FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY,
                            FailureRecoveryDisposition.FAILED);
            events.add(new DesktopUpdateEvent(DesktopUpdateState.ROLLBACK_VERIFIED, false, failed.diagnostic()));
            return new DesktopUpdateResult(operation, DesktopUpdateStatus.MANUAL_RECOVERY_REQUIRED,
                    update.version(), events, Optional.of(backup.backupToken()), Optional.of(failed));
        }
    }

    /**
     * Requires step.
     * <p>要求步骤。
     *
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @throws DesktopUpdateException if the desktop update boundary rejects the operation / Desktop更新边界拒绝当前操作时
     */
    private static void requireStep(
            DesktopUpdatePort.StepEvidence evidence, DesktopUpdateFailureType type, String diagnostic)
            throws DesktopUpdateException {
        if (!evidence.completed() || !evidence.verified()) {
            throw DesktopUpdateException.create(type, diagnostic);
        }
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static FailureDescriptor failure(Exception exception) {
        if (exception instanceof DesktopUpdateException update) return update.failure();
        return DesktopUpdateException.create(DesktopUpdateFailureType.TRANSACTION_FAILED,
                "unexpected desktop update transaction failure", exception).failure();
    }

    /**
     * Builds a successful outcome from the supplied completion evidence.
     * <p>根据所提供的完成证据构建成功结果。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a successful outcome from the supplied completion evidence / 根据所提供的完成证据构建成功结果
     */
    private static DesktopUpdateEvent success(DesktopUpdateState state, List<String> evidence) {
        String joined = String.join("; ", evidence);
        if (joined.length() > 1024) joined = joined.substring(0, 1024);
        return new DesktopUpdateEvent(state, true, joined);
    }

    /**
     * Builds a successful outcome from the supplied completion evidence.
     * <p>根据所提供的完成证据构建成功结果。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a successful outcome from the supplied completion evidence / 根据所提供的完成证据构建成功结果
     */
    private static DesktopUpdateEvent success(DesktopUpdateState state, String evidence) {
        return new DesktopUpdateEvent(state, true, evidence);
    }
}
