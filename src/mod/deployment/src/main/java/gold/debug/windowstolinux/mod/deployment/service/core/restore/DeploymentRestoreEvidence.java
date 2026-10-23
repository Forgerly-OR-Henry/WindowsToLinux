package gold.debug.windowstolinux.mod.deployment.service.core.restore;

import static gold.debug.windowstolinux.mod.deployment.service.core.restore.DeploymentRestoreSteps.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentActivation;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentRestoreRequest;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentHandoffChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;

/** Exact reviewed target, runtime health and restore outcome evidence. / 精确审阅目标、运行健康及恢复结果依据。 */
final class DeploymentRestoreEvidence {
    /** Prevents instances of the fixed original restore evidence functions. / 防止固定原恢复依据函数产生实例。 */
    private DeploymentRestoreEvidence() {
    }

    /** Verifies each current release, component policy and the whole application policy. / 核实每项当前发布、组件策略及整应用策略。
     * @param plan complete immutable request / 完整不可变请求
     * @param channel fixed original helper channel / 固定原helper频道
     * @param publisher original runtime observations / 原运行时观察
     * @return complete health conclusion / 完整健康结论
     * @throws ApiException if authority or remote observations are unavailable / 权限或远端观察不可用时 */
    static OperationResult<Void> health(DeploymentRestorePlan plan, DeploymentHandoffChannel channel,
                                        DeploymentPublishChannel publisher) throws ApiException {
        for (var component : plan.proposal.graph().components()) {
            var current = channel.current(component, plan.commit(component.componentId()).release().sha256());
            if (current.outcome() != TaskResult.SUCCEEDED)
                return current;
            var checked = publisher.healthCheck(component.unit(), component.reviewedRuntime().healthCheck());
            if (checked.outcome() != TaskResult.SUCCEEDED)
                return checked;
            var observed = publisher.observe(component.unit(), component.reviewedRuntime());
            String expected = component.reviewedRuntime().workload()
                .mode() == DeploymentWorkload.ExecutionMode.ON_DEMAND ? "INSTALLED" : "RUNNING";
            if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
                return copy(observed);
            if (!observed.value().orElseThrow().state().name().equals(expected))
                return failed("DEPLOYMENT_RESTORE_COMPONENT_UNHEALTHY");
        }
        var owner = plan.proposal.graph().components().stream()
            .filter(value -> value.componentId().equals(plan.proposal.graph().healthComponentId())).findFirst()
            .orElseThrow();
        return publisher.healthCheck(owner.unit(), plan.proposal.graph().healthCheck());
    }

    /** Binds archive, authenticated target and every actual current release to one review. / 将归档、已认证目标及全部实际当前发布绑定同一审阅。
     * @param archived original archived description / 原归档描述
     * @param digest complete caller-verified archive digest / 调用方核实的完整归档摘要
     * @param server actual saved target / 实际已保存目标
     * @param facts authenticated remote facts / 已认证远端事实
     * @param current actual component current digests / 实际组件当前摘要
     * @return canonical target review digest / 规范目标审阅摘要 */
    static String reviewed(DeploymentApplicationSnapshot archived, String digest,
                           gold.debug.windowstolinux.appapi.server.model.ServerInfo server, RemoteFacts facts, Map<String, String> current) {
        StringBuilder value = new StringBuilder(DeploymentRestorePlan.review(archived, digest, server, facts));
        new TreeMap<>(current).forEach((key, sha) -> value.append('\n').append(key).append(':').append(sha));
        return DeploymentApplicationIdentity.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Reads exact managed target identity, including explicit absence. / 读取精确受管目标身份，含明确不存在。
     * @param graph original restored graph / 原恢复图
     * @param channel fixed original helper channel / 固定原helper频道
     * @return component current digests or explicit absence / 组件当前摘要或明确不存在
     * @throws Exception if owner, kind or current identity is unconfirmed / 所有者、种类或当前身份未确证时 */
    static Map<String, String> targets(DeploymentApplicationGraph graph, DeploymentHandoffChannel channel)
        throws Exception {
        var result = new TreeMap<String, String>();
        for (var component : graph.components()) {
            String kind = component.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container
                ? "container"
                : "deployment";
            var response = send(channel, "managed-target-status",
                List.of(component.unit().id(), component.unit().ownershipManifestSha256(), kind),
                Duration.ofMinutes(2));
            if (response.outcome() != TaskResult.SUCCEEDED || response.value().isEmpty()
                || response.value().orElseThrow().truncated()
                || !response.value().orElseThrow().exitCode().equals(Optional.of(0)))
                throw new IllegalStateException("Target identity unconfirmed");
            String text = response.value().orElseThrow().stdout();
            if (text.equals("MANAGED_TARGET=1\nPRESENT=0\nKIND=" + kind + "\nRELEASE_SHA256=-\n"))
                result.put(component.componentId(), "-");
            else {
                String prefix = "MANAGED_TARGET=1\nPRESENT=1\nKIND=" + kind + "\nRELEASE_SHA256=";
                if (!text.startsWith(prefix) || !text.substring(prefix.length()).matches("[a-f0-9]{64}\\n"))
                    throw new IllegalStateException("Target current differs");
                result.put(component.componentId(), text.substring(prefix.length(), text.length() - 1));
            }
        }
        return Map.copyOf(result);
    }

    /** Reads original reviewed target digests from durable component facts. / 从持久组件事实读取原已审阅目标摘要。
     * @param cursor original authorized journal / 原已授权账本
     * @return immutable original target digests / 不可变原目标摘要 */
    static Map<String, String> before(DeploymentRestoreState cursor) {
        var values = new TreeMap<String, String>();
        cursor.facts.path("components").fields()
            .forEachRemaining(value -> values.put(value.getKey(), value.getValue().path("beforeDigest").asText()));
        return Map.copyOf(values);
    }

    /** Reports verified health without inventing an access URL. / 报告已确证健康而不捏造访问地址。
     * @param cursor original authorized journal / 原已授权账本
     * @param cleanup whether cleanup is confirmed / 是否清理已确证
     * @return original activation result / 原激活结果 */
    static DeploymentActivation activation(DeploymentRestoreState cursor, boolean cleanup) {
        return new DeploymentActivation(cursor.entry.publicValue(), "",
            "Verified each component and the whole application against the original archived policy", cleanup);
    }

    /** Preserves a classified original restore blocker. / 保留已分类原恢复阻碍。
     * @param code classified cause / 已分类原因
     * @param event original event / 原事件
     * @return explicit restore problem / 明确恢复问题 */
    static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Restore requires exact original archive, target and effect evidence", Map.of(),
            Optional.empty());
    }

    /** Returns only an observed successful payload. / 仅返回已观察成功载荷。
     * @param <T> payload type / 载荷类型
     * @param value observed payload / 已观察载荷
     * @return known successful result / 已知成功结果 */
    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Rejects incompatible input before an unknown effect is assumed. / 不假设未知副作用地拒绝不兼容输入。
     * @param <T> payload type / 载荷类型
     * @param event original failure / 原失败
     * @return known failed result / 已知失败结果 */
    static <T> OperationResult<T> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(problem(ProblemCode.INCOMPATIBLE, event)));
    }

    /** Retains original uncertainty without a terminal success claim. / 保留原未知而不宣称终态成功。
     * @param <T> payload type / 载荷类型
     * @param event original uncertainty / 原未知
     * @return unknown result / 未知结果 */
    static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Reports cancellation observed before effects. / 报告副作用前观察到的取消。
     * @param <T> payload type / 载荷类型
     * @param event original cancellation / 原取消
     * @return known cancelled result / 已知取消结果 */
    static <T> OperationResult<T> cancelled(String event) {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
            Optional.of(problem(ProblemCode.CANCELLED, event)));
    }

    /** Propagates failure evidence without discarding successful payloads. / 传递失败依据而不丢弃成功载荷。
     * @param <T> destination payload type / 目标载荷类型
     * @param value non-successful original result / 原非成功结果
     * @return unchanged conclusion and problem / 不变结论与问题
     * @throws IllegalArgumentException if a successful result is supplied / 传入成功结果时 */
    static <T> OperationResult<T> copy(OperationResult<?> value) {
        if (value.outcome() == TaskResult.SUCCEEDED)
            throw new IllegalArgumentException("Successful evidence cannot be discarded");
        return new OperationResult<>(value.outcome(), Optional.empty(), value.problem());
    }

    /** Borrows the authenticated original session for fixed handoff commands. / 为固定交接命令借用已认证原会话。
     * @param context actual provider / 实际提供方
     * @param session authenticated borrowed session / 已认证借用会话
     * @param request immutable original request / 不可变原请求
     * @param task authentic original task / 真实原任务
     * @return original handoff channel / 原交接频道 */
    static DeploymentHandoffChannel channel(ModContext context, RemoteSession session, DeploymentRestoreRequest request,
                                            TaskContext task, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return new DeploymentHandoffChannel(session, context.interaction(), request.target(), task,
            request.reviewedTargetSha256(), layout);
    }

    /** Borrows the authenticated session for original runtime and immutable staging operations. / 为原运行时与不可变暂存操作借用已认证会话。
     * @param context actual provider / 实际提供方
     * @param session authenticated borrowed session / 已认证借用会话
     * @param request immutable original request / 不可变原请求
     * @param task authentic original task / 真实原任务
     * @return original publication channel / 原发布频道 */
    static DeploymentPublishChannel publication(ModContext context, RemoteSession session,
                                                DeploymentRestoreRequest request, TaskContext task,
                                                gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return new DeploymentPublishChannel(session, context.interaction(), request.target(), task,
            request.reviewedTargetSha256(), layout);
    }

    /** Rechecks endpoint and trust against the original before-state review. / 对照原变更前审阅复核端点与信任。
     * @param context actual provider / 实际提供方
     * @param cursor original authorized journal / 原已授权账本
     * @param plan immutable original request / 不可变原请求
     * @param session authenticated borrowed session / 已认证借用会话
     * @return whether the original target review still matches / 是否原目标审阅仍匹配
     * @throws ApiException if current target rememberAuthorization is unavailable / 当前目标授权不可用时 */
    static boolean sameTarget(ModContext context, DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                              RemoteSession session) throws ApiException {
        return reviewed(plan.request.archivedApplication(), plan.request.archiveSha256(),
            context.servers().get(plan.request.target(), cursor.task), session.facts(), before(cursor))
            .equals(plan.request.reviewedTargetSha256());
    }
}
