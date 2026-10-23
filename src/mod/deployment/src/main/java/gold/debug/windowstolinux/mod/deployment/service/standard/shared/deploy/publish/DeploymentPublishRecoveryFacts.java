package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentRemoteReleaseSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.attempt.pointer.DeploymentPublishAttemptPointers;

/** Validated original non-secret facts, never inferred from a path or current remote state. / 已校验原非秘密事实，绝不由路径或当前远端状态推断。
 * @param unit exact original owner / 精确原归属
 * @param candidate original task candidate / 原任务候选
 * @param plan original reviewed plan identity / 原已审阅计划身份
 * @param phase last durably recorded remote step / 最近已持久远端步骤
 * @param backend original native or container backend / 原原生或容器后端
 * @param engine original engine or none / 原引擎或none
 * @param operation formally confirmed explicit operation or original absence / 正式已确认明确操作或原空值
 * @param release attempted remote release, if known / 尝试远端发布；可能未形成
 * @param snapshot verified original old snapshot, if acknowledged / 已获确认的原旧快照；可能未确认
 * @param failure actual known failure before rollback, if any / 回滚前实际已知失败；可能不存在
 * @param verifiedOutcome independently persisted verified business result / 独立持久的已核实业务结果
 * @param verifiedProblem bounded original verified failure, if any / 原已核实失败的有界问题 */
public record DeploymentPublishRecoveryFacts(DeploymentUnitIdentity unit, String candidate, String plan, String phase,
                                             String backend, String engine, Optional<UUID> operation,
                                             Optional<String> release,
                                             Optional<DeploymentRemoteReleaseSnapshot> snapshot,
                                             Optional<Problem> failure, Optional<TaskResult> verifiedOutcome,
                                             Optional<Problem> verifiedProblem) {
    /** Fixed recorded phases; later phases cannot invent earlier missing identities. / 固定记录阶段；后续阶段不得补造先前缺失身份。 */
    private static final Set<String> PHASES = Set.of("CANDIDATE_MAY_EXIST", "SNAPSHOT_MAY_HAVE_RUN",
        "SNAPSHOT_VERIFIED", "SNAPSHOT_DISCARD_MAY_HAVE_RUN", "SNAPSHOT_DISCARDED", "PUBLISH_MAY_HAVE_RUN",
        "STOP_MAY_HAVE_RUN", "STOP_VERIFIED", "PUBLISHED_VERIFIED", "ROLLBACK_MAY_HAVE_RUN", "ROLLBACK_VERIFIED",
        "MAINTENANCE_END_MAY_HAVE_RUN", "MAINTENANCE_ENDED", "RETAIN_MAY_HAVE_RUN", "RETAIN_VERIFIED",
        "CANDIDATE_CLEANUP_MAY_HAVE_RUN", "CANDIDATE_CLEANED", "REMOTE_CLEANUP_VERIFIED", "SESSION_CLOSED",
        "CLEANUP_VERIFIED");

    /** Reads only exact original identities from the retained checkpoint. / 仅从留存检查点读取精确原身份。
     * @param node original JSON object / 原JSON对象
     * @param record original execution / 原执行
     * @return validated facts / 已校验事实 */
    public static DeploymentPublishRecoveryFacts read(JsonNode node, DeploymentExecutionRecord record) {
        return read(node, record, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static DeploymentPublishRecoveryFacts read(JsonNode node, DeploymentExecutionRecord record,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        return read(node, record, record.identity().applicationId(), record.identity().reviewedRevision(), layout);
    }

    /** Reads an indexed component only when the graph root supplied its exact original identity. / 仅在图根提供精确原身份时读取索引组件。
     * @param node original component journal / 原组件账本
     * @param record whole original execution / 完整原执行
     * @param application expected component unit / 预期组件单元
     * @param reviewed expected component review / 预期组件审阅
     * @return validated component facts / 已校验组件事实 */
    public static DeploymentPublishRecoveryFacts read(JsonNode node, DeploymentExecutionRecord record, String application,
                                                      String reviewed) {
        return read(node, record, application, reviewed,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public static DeploymentPublishRecoveryFacts read(JsonNode node, DeploymentExecutionRecord record, String application,
            String reviewed, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        java.util.Objects.requireNonNull(layout);
        String task = text(node, "task");
        String app = text(node, "application");
        String server = text(node, "server");
        String owner = text(node, "ownerManifestSha256");
        String archive = text(node, "archiveSha256");
        String candidate = text(node, "candidate");
        String plan = text(node, "planSha256");
        String phase = text(node, "phase");
        String backend = text(node, "backend");
        String engine = text(node, "engine");
        DeploymentPublishAttemptPointers.requireChild(node);
        var operation = node.has("operation") ? Optional.of(UUID.fromString(text(node, "operation"))) : Optional.<UUID>empty();
        if (!task.equals(record.identity().taskId().toString()) || !app.equals(application)
            || !server.equals(record.identity().server().id()) || !plan.equals(reviewed)
            || !archive.matches("[0-9a-f]{64}") || !plan.matches("[0-9a-f]{64}") || !PHASES.contains(phase)
            || !Set.of("native:none", "container:docker", "container:podman").contains(backend + ":" + engine)
            || node.has("attemptState") && !text(node, "attemptState").equals("CONFIRMED")
            || !candidate.equals(new DeploymentRemoteWorkspace(app, archive, operation, layout).candidateId()))
            throw new IllegalArgumentException("Original publication identity conflicts");
        var unit = DeploymentUnitIdentity.forManaged(app, new ServerId(server), owner, layout);
        if (!unit.releaseRoot().equals(text(node, "releaseRoot")))
            throw new IllegalArgumentException("Original publication root conflicts");
        var release = node.has("releaseSha256") ? Optional.of(text(node, "releaseSha256")) : Optional.<String>empty();
        if (release.isPresent() && !release.orElseThrow().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Original attempted release is invalid");
        Optional<DeploymentRemoteReleaseSnapshot> snapshot = Optional.empty();
        if (node.has("previousToken")) {
            String token = text(node, "previousToken");
            if (!token.isEmpty() && !token.equals(operation.map(UUID::toString).orElse(task)) || !node.path("previousRunning").isBoolean())
                throw new IllegalArgumentException("Original rollback token conflicts");
            String digest = text(node, "previousDigest");
            snapshot = Optional
                .of(new DeploymentRemoteReleaseSnapshot(token.isEmpty() ? Optional.empty() : Optional.of(token),
                    node.path("previousRunning").booleanValue(),
                    digest.isEmpty() ? Optional.empty() : Optional.of(digest), text(node, "previousKind")));
        }
        Optional<Problem> failure = Optional.empty();
        if (node.has("failureCode") || node.has("failureEvent")) {
            var code = ProblemCode.valueOf(text(node, "failureCode"));
            String event = text(node, "failureEvent");
            if (code == ProblemCode.UNKNOWN || !event.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
                throw new IllegalArgumentException("Original known failure is invalid");
            failure = Optional.of(
                new Problem(code, event, "Original release failed and was restored", Map.of(), Optional.empty()));
        }
        Optional<TaskResult> verifiedOutcome = Optional.empty();
        Optional<Problem> verifiedProblem = Optional.empty();
        if (node.has("verifiedOutcome")) {
            var outcome = TaskResult.valueOf(text(node, "verifiedOutcome"));
            if (outcome != TaskResult.SUCCEEDED && outcome != TaskResult.FAILED)
                throw new IllegalArgumentException("Original verified outcome is invalid");
            verifiedOutcome = Optional.of(outcome);
            if (outcome == TaskResult.FAILED) {
                var code = ProblemCode.valueOf(text(node, "verifiedCode"));
                String event = text(node, "verifiedEvent");
                if (code == ProblemCode.UNKNOWN || !event.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
                    throw new IllegalArgumentException("Original verified failure is invalid");
                verifiedProblem = Optional
                    .of(new Problem(code, event, "Original publication failed", Map.of(), Optional.empty()));
            } else if (node.has("verifiedCode") || node.has("verifiedEvent"))
                throw new IllegalArgumentException("Successful publication has failure metadata");
        } else if (node.has("verifiedCode") || node.has("verifiedEvent"))
            throw new IllegalArgumentException("Unverified publication has failure metadata");
        if (phase.equals("PUBLISH_MAY_HAVE_RUN") && (snapshot.isEmpty() || release.isEmpty())
            || phase.startsWith("ROLLBACK_") && (release.isEmpty() || snapshot.isEmpty() || failure.isEmpty()))
            throw new IllegalArgumentException("Original publication stage lacks its durable predecessor");
        return new DeploymentPublishRecoveryFacts(unit, candidate, plan, phase, backend, engine, operation, release, snapshot,
            failure, verifiedOutcome, verifiedProblem);
    }

    /** Requires an original bounded textual field. / 要求原有界文本字段。
     * @param node original object / 原对象
     * @param name exact field / 精确字段
     * @return field value / 字段值 */
    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.textValue().length() > 512)
            throw new IllegalArgumentException("Original publication field is absent");
        return value.textValue();
    }
}
