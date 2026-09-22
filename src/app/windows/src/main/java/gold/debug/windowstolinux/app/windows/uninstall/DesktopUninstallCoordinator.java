package gold.debug.windowstolinux.app.windows.uninstall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Executes an explicit-choice uninstall within verified local application boundaries. / 在已验证本地应用边界内执行显式选择的卸载。
 */
public final class DesktopUninstallCoordinator {
    /**
     * Network port number in the reviewed endpoint.
     * <p>已审阅端点中的网络端口号。
     */
    private final DesktopUninstallPort port;

    /**
     * Creates an uninstall coordinator over one platform implementation. / 基于一个平台实现创建卸载协调器。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUninstallCoordinator(DesktopUninstallPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /**
     * Validates the decision and stops owned tasks without deleting any file or credential. / 校验决定并停止自有任务，不删除任何文件或凭据。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved desktop uninstall preparation result / 构造或解析得到的Desktop卸载准备结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUninstallPreparationResult prepare(DesktopUninstallRequest request) {
        Objects.requireNonNull(request, "request");
        OperationIdentity operation = OperationIdentity.create();
        List<DesktopUninstallEvent> events = new ArrayList<>();
        if (request.decision().isEmpty()) {
            FailureDescriptor required = FailureDescriptor.create(DesktopUninstallFailureType.DECISION_REQUIRED,
                    operation, "uninstall requires an explicit data and credential decision");
            events.add(
                    new DesktopUninstallEvent(DesktopUninstallState.DECISION_VALIDATED, false, required.diagnostic()));
            return new DesktopUninstallPreparationResult(operation, DesktopUninstallPreparationStatus.DECISION_REQUIRED,
                    events, Optional.empty(), Optional.of(required));
        }
        DesktopUninstallDecisionType decision = request.decision().orElseThrow();
        events.add(new DesktopUninstallEvent(DesktopUninstallState.DECISION_VALIDATED, true,
                decision == DesktopUninstallDecisionType.KEEP_DATA_AND_CREDENTIALS
                        ? "user explicitly chose to retain local data and credentials"
                        : "user explicitly chose to delete managed local data and credentials"));
        try {
            DesktopUninstallPort.StepEvidence stopped = port.stopOwnedTasks(request);
            require(stopped.completed() && stopped.verified(), DesktopUninstallFailureType.TASKS_ACTIVE,
                    "application-owned tasks could not be stopped and verified");
            events.add(success(DesktopUninstallState.TASKS_STOPPED, stopped.evidence()));
            DesktopUninstallHandoff handoff = new DesktopUninstallHandoff(operation, request, events);
            return new DesktopUninstallPreparationResult(operation, DesktopUninstallPreparationStatus.READY_FOR_HANDOFF,
                    events, Optional.of(handoff), Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor failure = failure(exception).withOperationIdentity(operation)
                    .withRecovery(FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_ATTEMPTED);
            events.add(new DesktopUninstallEvent(DesktopUninstallState.TASKS_STOPPED, false, failure.diagnostic()));
            return new DesktopUninstallPreparationResult(operation,
                    DesktopUninstallPreparationStatus.PRECONDITION_REJECTED, events, Optional.empty(),
                    Optional.of(failure));
        }
    }

    /**
     * Removes managed local program/data/credentials only from the external worker process. / 仅由外部执行器进程删除受管程序、数据及凭据。
     *
     * @param handoff handoff / 交接
     * @return constructed or resolved desktop uninstall result / 构造或解析得到的Desktop卸载结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUninstallResult apply(DesktopUninstallHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        OperationIdentity operation = handoff.operationIdentity();
        DesktopUninstallRequest request = handoff.request();
        DesktopUninstallDecisionType decision = request.decision().orElseThrow();
        List<DesktopUninstallEvent> events = new ArrayList<>(handoff.preparationEvents());
        DesktopUninstallState state = DesktopUninstallState.INDEPENDENT_WORKER_VERIFIED;
        try {
            DesktopUninstallPort.HandoffEvidence worker = port.verifyIndependentWorker(handoff);
            require(worker.independentWorkerVerified() && worker.mainProcessExited() && worker.handoffAuthenticated(),
                    DesktopUninstallFailureType.BOUNDARY_INVALID,
                    "independent uninstall worker, main process exit or handoff authenticity is unverified");
            events.add(success(DesktopUninstallState.INDEPENDENT_WORKER_VERIFIED, worker.evidence()));

            state = DesktopUninstallState.BOUNDARIES_VERIFIED;
            DesktopUninstallPort.BoundaryEvidence boundaries = port.verifyManagedBoundaries(request);
            boolean deleteData = decision == DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS;
            boolean completeBoundary = boundaries.jpackageLayoutVerified() && boundaries.installMarkerVerified()
                    && (!deleteData || boundaries.dataMarkerVerified() && boundaries.credentialNamespaceVerified());
            require(completeBoundary, DesktopUninstallFailureType.BOUNDARY_INVALID,
                    "jpackage, data marker or credential namespace ownership is incomplete");
            events.add(success(DesktopUninstallState.BOUNDARIES_VERIFIED, boundaries.evidence()));

            List<String> residuals = new ArrayList<>();
            state = DesktopUninstallState.PROGRAM_REMOVED;
            DesktopUninstallPort.RemovalEvidence program = port.removeProgram(request);
            addRemoval(events, DesktopUninstallState.PROGRAM_REMOVED, program, residuals);
            List<String> retained = new ArrayList<>();
            if (deleteData) {
                state = DesktopUninstallState.DATA_REMOVED;
                DesktopUninstallPort.RemovalEvidence data = port.removeData(request);
                addRemoval(events, DesktopUninstallState.DATA_REMOVED, data, residuals);
                state = DesktopUninstallState.CREDENTIALS_REMOVED;
                DesktopUninstallPort.RemovalEvidence credentials = port.removeCredentials(request);
                addRemoval(events, DesktopUninstallState.CREDENTIALS_REMOVED, credentials, residuals);
            } else {
                retained.add(request.dataRoot().toString());
                retained.add(request.credentialNamespace());
            }
            if (!residuals.isEmpty()) {
                FailureDescriptor incomplete = FailureDescriptor
                        .create(DesktopUninstallFailureType.REMOVAL_INCOMPLETE, operation,
                                "one or more exact managed uninstall targets remain")
                        .withRecovery(FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY, FailureRecoveryDisposition.FAILED);
                return result(operation, DesktopUninstallStatus.COMPLETED_WITH_RESIDUALS, events, residuals, retained,
                        Optional.of(incomplete));
            }
            DesktopUninstallStatus status = deleteData
                    ? DesktopUninstallStatus.SUCCEEDED_DATA_DELETED
                    : DesktopUninstallStatus.SUCCEEDED_DATA_RETAINED;
            return result(operation, status, events, List.of(), retained, Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor failure = failure(exception).withOperationIdentity(operation)
                    .withRecovery(FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_ATTEMPTED);
            events.add(new DesktopUninstallEvent(state, false, failure.diagnostic()));
            return result(operation, DesktopUninstallStatus.PRECONDITION_REJECTED, events, List.of(), List.of(),
                    Optional.of(failure));
        }
    }

    /**
     * Adds removal.
     * <p>添加移除。
     *
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param removal removal / 移除
     * @param residuals residuals / 残留集合
     */
    private static void addRemoval(List<DesktopUninstallEvent> events, DesktopUninstallState state,
            DesktopUninstallPort.RemovalEvidence removal, List<String> residuals) {
        residuals.addAll(removal.residualItems());
        boolean succeeded = removal.completed() && removal.verified() && removal.residualItems().isEmpty();
        events.add(new DesktopUninstallEvent(state, succeeded, joined(removal.evidence())));
    }

    /**
     * Builds desktop uninstall result from the supplied result inputs.
     * <p>根据所提供结果输入构建Desktop卸载结果。
     *
     * @param operation operation / 操作
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param residuals residuals / 残留集合
     * @param retained retained / 已保留
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return desktop uninstall result from the supplied result inputs / 根据所提供结果输入构建Desktop卸载结果
     */
    private static DesktopUninstallResult result(OperationIdentity operation, DesktopUninstallStatus status,
            List<DesktopUninstallEvent> events, List<String> residuals, List<String> retained,
            Optional<FailureDescriptor> failure) {
        return new DesktopUninstallResult(operation, status, events, residuals, retained, failure);
    }

    /**
     * Requires desktop uninstall coordinator.
     * <p>要求Desktop卸载协调器。
     *
     * @param condition condition / 条件
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @throws DesktopUninstallException if the desktop uninstall boundary rejects the operation / Desktop卸载边界拒绝当前操作时
     */
    private static void require(boolean condition, DesktopUninstallFailureType type, String diagnostic)
            throws DesktopUninstallException {
        if (!condition)
            throw DesktopUninstallException.create(type, diagnostic);
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static FailureDescriptor failure(Exception exception) {
        if (exception instanceof DesktopUninstallException uninstall)
            return uninstall.failure();
        return DesktopUninstallException
                .create(DesktopUninstallFailureType.BOUNDARY_INVALID, "unexpected desktop uninstall boundary failure")
                .failure();
    }

    /**
     * Builds a successful outcome from the supplied completion evidence.
     * <p>根据所提供的完成证据构建成功结果。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a successful outcome from the supplied completion evidence / 根据所提供的完成证据构建成功结果
     */
    private static DesktopUninstallEvent success(DesktopUninstallState state, List<String> evidence) {
        return new DesktopUninstallEvent(state, true, joined(evidence));
    }

    /**
     * Joins evidence with semicolons and caps the displayed text at 1024 characters.
     * <p>使用分号连接证据，并将显示文本限制为 1024 个字符。
     *
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return joined text / 已连接文本
     */
    private static String joined(List<String> evidence) {
        String joined = String.join("; ", evidence);
        return joined.length() <= 1024 ? joined : joined.substring(0, 1024);
    }
}
