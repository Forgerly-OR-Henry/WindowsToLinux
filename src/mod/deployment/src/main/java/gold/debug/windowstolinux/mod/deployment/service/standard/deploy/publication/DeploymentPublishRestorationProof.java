package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.util.*;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;

/** Reads every restored original unit before another distinct operation can be reviewed. / 另一独立操作可审阅前读取每个已恢复原单元。 */
final class DeploymentPublishRestorationProof {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentPublishRestorationProof() {
    }

    /** Requires closed author checkpoints and fresh exact remote absence, current and activity evidence. / 要求已关闭作者检查点与新鲜精确远端不存在、当前及活动依据。
     * @param context original Mod scope / 原Mod作用域
     * @param task authentic original root / 真实原根
     * @param units complete original unit mapping / 完整原单元映射
     * @param journals complete original cleanup journals / 完整原清理账本
     * @param root original graph journal or absence / 原图账本或不存在
     * @param loop original shared execution clocks / 原共用执行时钟
     * @param failure actual known restored failure / 实际已知恢复失败
     * @return complete physically checked retired operation / 完整物理检查已退出操作
     * @throws Exception if cleanup, identity or session close is not confirmed / 清理、身份或会话关闭未确认时 */
    static DeploymentPublishAttemptProof read(ModContext context, TaskContext task,
                                              Map<String, DeploymentUnitIdentity> units, Map<String, DeploymentPublishCleanupActionLog> journals,
                                              DeploymentGraphPublishActionLog root, DeploymentAssistedPublishBuild loop, Problem failure) throws Exception {
        if (task.cancelToken().requested() || units.isEmpty() || !units.keySet().equals(journals.keySet()))
            throw new IllegalArgumentException("Original restoration scope is incomplete");
        var executions = new DeploymentExecutionStore(context.db());
        var record = executions.findForTask(task).orElseThrow();
        var attempts = new DeploymentPublishAttemptStore(context.db());
        UUID operation = attempts.operation(record);
        String templateSha = attempts.templateSha256(record);
        var children = new ArrayList<DeploymentPublishAttemptProof.Child>();
        try (var session = context.linux().connect(record.identity().server(), task)) {
            for (var entry : units.entrySet()) {
                var facts = journals.get(entry.getKey()).facts();
                var prepared = loop.selection(entry.getValue().id());
                var state = observe(context, session, task, entry.getValue(), facts, operation);
                children.add(new DeploymentPublishAttemptProof.Child(entry.getValue().id(), facts,
                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.encode(prepared.tools()),
                    state.current(), state.currentKind(), facts.path("previousRunning").booleanValue(),
                    prepared.plan().request().limits().timeoutSeconds(), prepared.remainingSeconds()));
            }
        }
        return new DeploymentPublishAttemptProof(task.id(), operation, record.identity().reviewedRevision(),
            templateSha, failure.code(), failure.eventCode(), loop.retriesSpent(),
            children, Optional.ofNullable(root).map(DeploymentGraphPublishActionLog::facts));
    }

    /** Checks exact previous content and running state while refusing all retained physical effects. / 拒绝所有留存物理效果，同时检查精确旧内容及运行状态。
     * @param context original Mod scope / 原Mod作用域
     * @param session actual authenticated observation session / 实际已认证观察会话
     * @param task authentic root / 真实原根
     * @param unit exact owned original unit / 精确所属原单元
     * @param facts closed original checkpoint / 已关闭原检查点
     * @param operation exact retired operation / 精确已退出操作
     * @return exact original status / 精确原状态
     * @throws ApiException if original scope rejects / 原作用域拒绝时 */
    private static DeploymentOriginalPublishStatus observe(ModContext context, RemoteSession session, TaskContext task,
                                                           DeploymentUnitIdentity unit, com.fasterxml.jackson.databind.JsonNode facts, UUID operation) throws ApiException {
        if (!facts.path("phase").asText().equals("SESSION_CLOSED") || !facts.has("previousRunning")
            || !facts.path("previousRunning").isBoolean())
            throw new IllegalArgumentException("Original operation is not fully restored");
        var channel = new DeploymentPublishChannel(session, context.interaction(), unit.server(), task, facts.path("planSha256").asText());
        Optional<String> release = facts.has("releaseSha256") ? Optional.of(facts.path("releaseSha256").asText()) : Optional.empty();
        var observed = facts.has("operation")
            ? channel.originalStatus(unit, facts.path("candidate").asText(), release, facts.path("backend").asText(), facts.path("engine").asText(), operation)
            : channel.originalStatus(unit, facts.path("candidate").asText(), release, facts.path("backend").asText(), facts.path("engine").asText());
        if (observed.outcome() != TaskResult.SUCCEEDED)
            throw new IllegalArgumentException("Original restored evidence is unconfirmed");
        var state = observed.value().orElseThrow();
        if (state.candidate() || state.buildActive() || state.snapshot() || state.maintenance()
            || !state.current().orElse("").equals(facts.path("previousDigest").asText())
            || !state.currentKind().equals(facts.path("previousKind").asText()))
            throw new IllegalArgumentException("Original current, marker, candidate or snapshot changed");
        if (state.current().isPresent()) {
            var runtime = channel.observeRestored(unit);
            if (runtime.outcome() != TaskResult.SUCCEEDED || runtime.value().orElseThrow().state() == DeploymentRemoteRuntimeObservation.StateType.ERROR
                || (runtime.value().orElseThrow().state() == DeploymentRemoteRuntimeObservation.StateType.RUNNING) != facts.path("previousRunning").booleanValue())
                throw new IllegalArgumentException("Original restored running state changed");
        } else if (state.unitPresent() || state.containerPresent().orElse(false) || facts.path("previousRunning").booleanValue()) {
            throw new IllegalArgumentException("Previously empty target contains an original runtime");
        }
        return state;
    }

    /** Hashes full canonical nonsecret evidence. / 哈希完整规范非秘密依据。
     * @param value actual canonical text / 实际规范文本
     * @return lowercase SHA / 小写SHA */
    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }
}
