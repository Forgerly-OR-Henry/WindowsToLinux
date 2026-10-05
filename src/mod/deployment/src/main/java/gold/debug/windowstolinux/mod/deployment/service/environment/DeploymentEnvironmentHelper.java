package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentSendGuard;

/** Uses the original environment operation for helper receipts, including recovery without creating another checkpoint. / 使用原环境操作保存 helper 回执，包括不创建另一检查点的恢复。 */
final class DeploymentEnvironmentHelper {
    /** Original capabilities. / 原能力。 */
    private final ModContext context;

    /** Exact original mode review. / 精确原模式审核。 */
    private final DeploymentEnvironmentCommandReview review;

    /** Original authentic task. / 原真实任务。 */
    private final TaskContext task;

    /** Original environment checkpoint. / 原环境检查点。 */
    private final DeploymentEnvironmentJournal journal;

    /** Binds only original evidence and approval. / 仅绑定原依据及审核。
     * @param context original capabilities / 原能力
     * @param review exact actual command review / 精确实际命令审核
     * @param task authentic original task / 真实原任务
     * @param journal original checkpoint / 原检查点 */
    DeploymentEnvironmentHelper(ModContext context, DeploymentEnvironmentCommandReview review, TaskContext task,
                                DeploymentEnvironmentJournal journal) {
        this.context = context;
        this.review = review;
        this.task = task;
        this.journal = journal;
    }

    /** Probes an existing helper or sends one installation under the already retained original operation. / 探测既有 helper 或在已保留原操作下发送一次安装。
     * @param state original installed-package proof / 原已安装包证明
     * @throws ApiException if original authentication or CAS fails / 原认证或 CAS 失败时
     * @throws DeploymentEnvironmentPendingException if helper identity or sent outcome is unknown / helper 身份或发送结果未知时 */
    void prepare(DeploymentEnvironmentState state) throws ApiException, DeploymentEnvironmentPendingException {
        try (var session = context.linux().connect(state.target(), task)) {
            var helper = helper(session, state);
            var observed = helper.probe();
            if (observed.outcome() == TaskResult.SUCCEEDED) {
                journal.advance(state, state.next("HELPER_INSTALLED", state.requestSha(), state.observation().orElseThrow()));
                return;
            }
            if (observed.outcome() != TaskResult.FAILED)
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_HELPER_PROBE_UNCONFIRMED");
            String script = installer(state);
            var sent = state.next("HELPER_DISPATCHED", DeploymentEnvironmentScripts.digest(script), state.observation().orElseThrow());
            var result = helper.installTracked(script.getBytes(StandardCharsets.UTF_8), () -> journal.advance(state, sent));
            if (result.outcome() != TaskResult.SUCCEEDED || result.value().isEmpty()
                || !result.value().orElseThrow().exitCode().equals(Optional.of(0)) || result.value().orElseThrow().truncated())
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_HELPER_DISPATCH_UNCONFIRMED");
        }
    }

    /** Checks only the original receipt and helper bytes through a fresh authenticated connection. / 经新认证连接仅核对原回执及 helper 字节。
     * @param state original sent helper stage / 原已发送 helper 阶段
     * @throws ApiException if original connection, approval or CAS fails / 原连接、审核或 CAS 失败时
     * @throws DeploymentEnvironmentPendingException if exact original proof is unavailable / 精确原证明不可用时 */
    void reconcile(DeploymentEnvironmentState state) throws ApiException, DeploymentEnvironmentPendingException {
        if (!state.requestSha().equals(DeploymentEnvironmentScripts.digest(installer(state))))
            throw new DeploymentEnvironmentPendingException("ENVIRONMENT_HELPER_ORIGINAL_REQUEST_CHANGED");
        try (var session = context.linux().connect(state.target(), task)) {
            var helper = helper(session, state);
            var observed = helper.installationStatus(state.operation());
            String receipt = DeploymentHelperInstallerScript.receipt(task.id(), state.operation(), serverDigest(state),
                helperDigest(), "SUCCEEDED");
            if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty()
                || !observed.value().orElseThrow().exitCode().equals(Optional.of(0)) || observed.value().orElseThrow().truncated()
                || !receipt.equals(observed.value().orElseThrow().stdout()) || helper.probe().outcome() != TaskResult.SUCCEEDED)
                throw new DeploymentEnvironmentPendingException("ENVIRONMENT_HELPER_ORIGINAL_RECEIPT_UNCONFIRMED");
            journal.advance(state, state.next("HELPER_INSTALLED", state.requestSha(), state.observation().orElseThrow()));
        }
    }

    /** Binds full requests, original target and original revision to the required actual reviewer. / 将完整请求、原目标及原修订绑定到所需实际审核器。
     * @param session fresh original-target connection / 原目标新连接
     * @param state original environment facts / 原环境事实
     * @return borrowed helper transport / 借用 helper 传输 */
    private DeploymentManagedHelper helper(RemoteSession session, DeploymentEnvironmentState state) {
        return new DeploymentManagedHelper(session, context.interaction(), state.target().server(), task,
            state.operation() + ":" + state.bodySha(), DeploymentSendGuard.NONE,
            (intent, request, original) -> review.approve(state.target(), intent.revision(), request, original));
    }

    /** Renders the exact original installer without creating an operation UUID. / 不创建操作 UUID 地渲染精确原安装程序。
     * @param state original facts / 原事实
     * @return fixed tracked installer / 固定留痕安装程序 */
    private String installer(DeploymentEnvironmentState state) {
        return DeploymentHelperInstallerScript.renderTracked(task.id(), state.operation(), serverDigest(state), helperDigest());
    }

    /** Identifies the original helper body. / 标识原 helper 主体。
     * @return exact body digest / 精确主体摘要 */
    private static String helperDigest() {
        return DeploymentEnvironmentScripts.digest(DeploymentHelperInstallerScript.render());
    }

    /** Preserves the existing installer server identity contract. / 保留既有安装程序服务器身份合同。
     * @param state original environment facts / 原环境事实
     * @return original server digest / 原服务器摘要 */
    private static String serverDigest(DeploymentEnvironmentState state) {
        return DeploymentEnvironmentScripts.digest(state.target().server().id());
    }
}
