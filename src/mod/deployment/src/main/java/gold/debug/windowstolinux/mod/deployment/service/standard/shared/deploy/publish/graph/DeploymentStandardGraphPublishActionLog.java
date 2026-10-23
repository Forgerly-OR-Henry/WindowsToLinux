package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.MultiPartDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.attempt.pointer.DeploymentPublishAttemptPointers;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.policy.DeploymentPublishGraphJournal;

/** Occupies one original graph while its indexed component journals retain exact remote facts. / 各索引组件账本保留精确远端依据期间占用同一原图。 */
public final class DeploymentStandardGraphPublishActionLog implements DeploymentPublishGraphJournal {
    /** One root key distinct from each component's original physical occupation. / 区别于各组件物理占用的唯一根键。 */
    public static final String KEY = "deployment-publication-graph";

    /** Exact JSON parser for the bounded author checkpoint. / 有界作者检查点的精确JSON解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Durable graph stages used by actual execution and original recovery. / 实际执行及原恢复使用的持久图阶段。 */
    private static final Set<String> PHASES = Set.of("PREPARING", "ALL_PREPARED", "ALL_BUILT", "INPUTS_STAGED",
        "SNAPSHOTS_VERIFIED", "PUBLISHING", "ROLLING_BACK", "REMOTE_CLEANUP_VERIFIED", "SESSION_CLOSED",
        "CLEANUP_VERIFIED");

    /** Original root occupation. / 原根占用。 */
    private final TaskCheckpoint checkpoint;

    /** Last confirmed compare-and-set revision. / 最近确认的CAS修订。 */
    private TaskCheckpoint.Snapshot snapshot;

    /** Original immutable graph index and latest phase. / 原不可变图索引及最新阶段。 */
    private final ObjectNode facts;

    /** Whether a previous update was durably confirmed. / 上次推进是否已持久确认。 */
    private boolean confirmed = true;

    /** Binds an existing original root. / 绑定已有原根。
     * @param checkpoint original occupation / 原占用
     * @param facts verified index / 已核实索引
     * @throws ApiException if the checkpoint cannot be read / 检查点无法读取时 */
    private DeploymentStandardGraphPublishActionLog(TaskCheckpoint checkpoint, ObjectNode facts) throws ApiException {
        this.checkpoint = checkpoint;
        this.snapshot = checkpoint.snapshot();
        this.facts = facts;
    }

    /** Persists all original component IDs before the first component's remote request. / 首组件远端请求前持久化全部原组件ID。
     * @param context actual Mod / 实际Mod
     * @param task original task / 原任务
     * @param server exact server ID / 精确服务器ID
     * @param ordering admitted graph order / 已准入图顺序
     * @param planSha256 complete reviewed graph digest / 完整审阅图摘要
     * @param components exact component plan identities / 精确组件计划身份
     * @return occupied root / 已占用根
     * @throws ApiException if original persistence is unconfirmed / 原持久确认不明时 */
    public static DeploymentStandardGraphPublishActionLog begin(ModContext context, TaskContext task, String server,
                                                                MultiPartDeploymentPlan ordering, String planSha256, List<PartIndex> components)
        throws ApiException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(task);
        Objects.requireNonNull(ordering);
        if (components.size() < 2 || components.size() > 16
            || !components.stream().map(PartIndex::componentId).toList().equals(ordering.startOrder()))
            throw new IllegalArgumentException("Original graph index differs from execution order");
        var facts = JSON.createObjectNode().put("version", 1).put("task", task.id().toString()).put("server", server)
            .put("application", ordering.applicationId()).put("planSha256", planSha256).put("phase", "PREPARING");
        var indexed = facts.putArray("components");
        for (var component : components)
            indexed.addObject().put("componentId", component.componentId()).put("unitId", component.unitId())
                .put("key", component.key()).put("planSha256", component.planSha256());
        indexes(facts);
        return new DeploymentStandardGraphPublishActionLog(context.tasks().beginCheckpoint(task, KEY, facts.toString()),
            facts);
    }

    /** Reopens only this original graph root, without creating resources. / 仅重开原图根，不创建资源。
     * @param context current Mod / 当前Mod
     * @param task original recovery task / 原恢复任务
     * @return original graph root or absence / 原图根或不存在
     * @throws ApiException if the task cannot be observed / 原任务无法观察时 */
    public static Optional<DeploymentStandardGraphPublishActionLog> reopen(ModContext context, TaskContext task)
        throws ApiException {
        var found = context.tasks().findCheckpoint(task, KEY);
        if (found.isEmpty())
            return Optional.empty();
        var checkpoint = found.orElseThrow();
        try {
            JsonNode parsed = JSON.readTree(checkpoint.snapshot().payload());
            if (!(parsed instanceof ObjectNode facts) || !validFields(facts) || !facts.path("version").isInt()
                || !Set.of(1, 2, 3).contains(facts.path("version").intValue())
                || !task.id().toString().equals(facts.path("task").textValue()) || !facts.path("server").isTextual()
                || facts.path("server").textValue().isBlank() || facts.path("server").textValue().length() > 512
                || !facts.path("application").isTextual()
                || !facts.path("application").textValue().matches("[a-z0-9][a-z0-9-]{0,62}")
                || !facts.path("planSha256").isTextual()
                || !facts.path("planSha256").textValue().matches("[0-9a-f]{64}")
                || !PHASES.contains(facts.path("phase").textValue()))
                throw new IllegalArgumentException("Original graph checkpoint is incomplete");
            indexes(facts);
            requireReviewFacts(facts, checkpoint.snapshot().payload());
            DeploymentPublishAttemptPointers.requireRoot(facts);
            if (facts.has("verifiedOutcome")) {
                if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(facts.path("verifiedOutcome").textValue())
                    || facts.path("verifiedOutcome").textValue()
                    .equals("SUCCEEDED") == (facts.has("failureCode") || facts.has("failureEvent")))
                    throw new IllegalArgumentException("Original graph result conflicts");
                if (facts.has("failureCode")
                    && (!facts.path("failureCode").isTextual() || !facts.path("failureEvent").isTextual()
                    || !facts.path("failureEvent").textValue().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")))
                    throw new IllegalArgumentException("Original graph failure is invalid");
            } else if (facts.has("failureCode") || facts.has("failureEvent"))
                throw new IllegalArgumentException("Unverified graph cannot contain failure metadata");
            return Optional.of(new DeploymentStandardGraphPublishActionLog(checkpoint, facts));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Original graph checkpoint is malformed", invalid);
        }
    }

    /** Refuses any unrecognized root field. / 拒绝任意未识别根字段。
     * @param facts original root document / 原根文档
     * @return whether every field belongs to this version / 全部字段是否属于此版本 */
    private static boolean validFields(ObjectNode facts) {
        var allowed = facts.path("version").asInt() >= 2
            ? Set.of("version", "task", "server", "application", "planSha256", "phase", "components", "verifiedOutcome", "failureCode", "failureEvent",
            "buildReviewState", "buildReviewAttempt", "buildReviewUnit", "buildReviewPrevious", "buildReviewNext", "buildReviewIntent")
            : Set.of("version", "task", "server", "application", "planSha256", "phase", "components", "verifiedOutcome", "failureCode", "failureEvent");
        if (facts.path("version").asInt() == 3) {
            var fields = new HashSet<>(allowed);
            fields.addAll(Set.of("operation", "attemptState", "attemptRetry", "attemptUnit", "attemptPreviousOperation", "attemptNextOperation",
                "attemptRootPrevious", "attemptRootNext", "attemptIntent", "attemptReview"));
            allowed = fields;
        }
        var fields = facts.fieldNames();
        while (fields.hasNext())
            if (!allowed.contains(fields.next()))
                return false;
        return true;
    }

    /** Requires exact bounded root review types rather than numeric or textual coercion. / 要求精确有界根审阅类型，不使用数值或文本强制转换。
     * @param facts original root facts / 原根事实
     * @param payload actual retained envelope / 实际留存信封
     * @throws IllegalArgumentException if exact retained review identity is incomplete / 精确留存审阅身份不完整时 */
    private static void requireReviewFacts(JsonNode facts, String payload) {
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12000)
            throw new IllegalArgumentException("Original graph checkpoint exceeds its admitted bound");
        if (facts.path("version").intValue() != 2 && !facts.has("buildReviewState")) return;
        if (!facts.path("buildReviewState").isTextual() || !Set.of("PENDING", "CONFIRMED").contains(facts.path("buildReviewState").textValue())
            || !facts.path("buildReviewAttempt").isInt() || facts.path("buildReviewAttempt").intValue() < 1 || facts.path("buildReviewAttempt").intValue() > 2
            || !facts.path("buildReviewUnit").isTextual() || indexes(facts).stream().noneMatch(index -> index.unitId().equals(facts.path("buildReviewUnit").textValue())))
            throw new IllegalArgumentException("Original graph review pointer is incomplete");
        for (String field : List.of("buildReviewPrevious", "buildReviewNext", "buildReviewIntent"))
            if (!facts.path(field).isTextual() || !facts.path(field).textValue().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Original graph review digest is malformed");
    }

    /** One durable index into a separate original component checkpoint. / 指向独立原组件检查点的一项持久索引。
     * @param componentId graph identity / 图身份
     * @param unitId managed unit / 受管单元
     * @param key actual checkpoint key / 实际检查点键
     * @param planSha256 exact component review / 精确组件审阅摘要 */
    public record PartIndex(String componentId, String unitId, String key, String planSha256) {
        /** Requires bounded independent original identifiers. / 要求有界独立的原身份。
         * @param componentId graph identity / 图身份
         * @param unitId managed unit / 受管单元
         * @param key actual key / 实际键
         * @param planSha256 exact review / 精确审阅摘要 */
        public PartIndex {
            if (componentId == null || !componentId.matches("[a-z0-9][a-z0-9-]{0,62}") || unitId == null
                || !unitId.matches("[a-z0-9][a-z0-9-]{0,62}") || key == null
                || !key.matches("deployment-publication-unit-[0-9]{2}") || planSha256 == null
                || !planSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid original graph component index");
        }
    }

    /** Returns the original graph index and outcome facts without handing out the mutable node. / 返回原图索引及结果事实而不暴露可变节点。
     * @return detached JSON facts / 分离JSON事实 */
    public JsonNode facts() {
        return facts.deepCopy();
    }

    /** Persists the original graph-to-next pointer before a component can consume its recovery consent. / 组件消费恢复同意前持久化原图、新图指针。
     * @param intent complete original recovery consent / 完整原恢复同意
     * @return whether this exact root pointer was confirmed / 此精确根指针是否获确认 */
    public boolean reviewPending(gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildIntent intent) {
        if (!facts.path("phase").asText().equals("ALL_PREPARED") || !facts.path("planSha256").asText().equals(intent.previousRoot()) || intent.originalGraph().isEmpty())
            return false;
        try {
            var original = gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReviewCodec.decodeGraph(intent.originalGraph());
            var before = gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan());
            if (!original.applicationId().equals(facts.path("application").asText()) || indexes().stream().anyMatch(index ->
                !original.namespaces().get(index.componentId()).equals(index.unitId()) || !original.identities().get(index.componentId()).equals(index.planSha256()))
                || indexes().stream().noneMatch(index -> index.unitId().equals(intent.unitId()) && index.planSha256().equals(before.reviewedRevision())))
                return false;
            facts.put("version", Math.max(2, facts.path("version").intValue())).put("buildReviewState", "PENDING").put("buildReviewAttempt", intent.attempt()).put("buildReviewUnit", intent.unitId())
                .put("buildReviewPrevious", intent.previousRoot()).put("buildReviewNext", intent.nextRoot())
                .put("buildReviewIntent", gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReview.failureBinding(intent.previousPlan(), intent.previousScript(), intent.previousTools(), intent.failureEvidence()));
            return mark("ALL_PREPARED");
        } catch (java.sql.SQLException invalid) {
            return false;
        }
    }

    /** Confirms a formally consumed single-child transition before building it again. / 再构建前确认正式已消费的单子节点转换。
     * @param review actual persisted full review / 实际持久完整审阅
     * @return whether root and exact child pointers were confirmed / 根与精确子指针是否获确认 */
    public boolean reviewConfirmed(gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReview review) {
        if (!facts.path("buildReviewState").asText().equals("PENDING") || facts.path("buildReviewAttempt").asInt() != review.attempt()
            || !facts.path("buildReviewUnit").asText().equals(review.unitId()) || !facts.path("buildReviewPrevious").asText().equals(review.previousRoot())
            || !facts.path("buildReviewNext").asText().equals(review.nextRoot()) || !facts.path("buildReviewIntent").asText().equals(review.failureBinding()))
            return false;
        try {
            var child = gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan());
            for (var index : facts.withArray("components"))
                if (index.path("unitId").asText().equals(review.unitId()))
                    ((ObjectNode) index).put("planSha256", child.reviewedRevision());
            facts.put("planSha256", review.nextRoot()).put("buildReviewState", "CONFIRMED");
            return mark("ALL_PREPARED");
        } catch (java.sql.SQLException invalid) {
            return false;
        }
    }

    /** Persists the next complete graph pointer after all old remote and session cleanup. / 所有旧远端及会话清理后持久化下一完整图指针。
     * @param intent full formally stored original consent / 完整正式已存原同意
     * @return whether the exact pending root was confirmed / 精确待定根是否已确认 */
    public boolean attemptPending(gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptIntent intent) {
        if (!confirmed || !DeploymentPublishAttemptPointers.pendingRoot(facts, intent)) return false;
        return mark("SESSION_CLOSED");
    }

    /** Advances the same root only after the exact formal CAS and all child operation pointers. / 精确正式CAS及全部子操作指针后才推进同一根。
     * @param review complete actually persisted final review / 完整实际持久最终审阅
     * @return whether the current complete graph was confirmed / 当前完整图是否已确认 */
    public boolean attemptConfirmed(gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptReview review) {
        if (!confirmed || !DeploymentPublishAttemptPointers.confirmRoot(facts, review)) return false;
        return mark("PREPARING");
    }

    /** Reads the exact deterministic index, including every unique unit and key. / 读取包括各唯一单元与键的精确确定性索引。
     * @return original ordered components / 原有序组件 */
    public List<PartIndex> indexes() {
        return indexes(facts);
    }

    /** Validates a retained graph's complete indexed scope. / 校验留存图完整索引范围。
     * @param node original JSON / 原JSON
     * @return exact components / 精确组件 */
    private static List<PartIndex> indexes(JsonNode node) {
        var values = node.path("components");
        if (!values.isArray() || values.size() < 2 || values.size() > 16)
            throw new IllegalArgumentException("Original graph index is incomplete");
        var result = new ArrayList<PartIndex>();
        var ids = new HashSet<String>();
        var units = new HashSet<String>();
        for (int offset = 0; offset < values.size(); offset++) {
            var value = values.get(offset);
            if (!value.isObject() || value.size() != 4 || !value.path("componentId").isTextual()
                || !value.path("unitId").isTextual() || !value.path("key").isTextual()
                || !value.path("planSha256").isTextual())
                throw new IllegalArgumentException("Original component index is malformed");
            var component = new PartIndex(value.path("componentId").textValue(), value.path("unitId").textValue(),
                value.path("key").textValue(), value.path("planSha256").textValue());
            if (!component.key()
                .equals("deployment-publication-unit-" + String.format(java.util.Locale.ROOT, "%02d", offset))
                || !ids.add(component.componentId()) || !units.add(component.unitId()))
                throw new IllegalArgumentException("Original component index conflicts");
            result.add(component);
        }
        return List.copyOf(result);
    }

    /** Reports the actual resource release state. / 报告实际资源释放状态。
     * @return whether the root was released / 根是否已释放 */
    public boolean released() {
        return snapshot.released();
    }

    /** Advances only after a confirmed previous CAS. / 仅在上次CAS确认后推进。
     * @param phase actual ordered phase / 实际有序阶段
     * @return whether the new facts were persisted / 新事实是否已持久 */
    public boolean mark(String phase) {
        if (!PHASES.contains(phase))
            throw new IllegalArgumentException("Unsupported graph phase");
        if (!confirmed)
            return false;
        facts.put("phase", phase);
        try {
            snapshot = checkpoint.advance(snapshot, facts.toString());
            return true;
        } catch (ApiException uncertain) {
            confirmed = false;
            return false;
        }
    }

    /** Freezes an independently verified whole-graph result before cleanup. / 收尾前独立冻结已核实的整图结果。
     * @param outcome verified graph result / 已核实整图结果
     * @param failure bounded original failure / 有界原失败
     * @return whether persistence was confirmed / 是否确认持久 */
    boolean verified(TaskResult outcome, Optional<Problem> failure) {
        if (outcome != TaskResult.SUCCEEDED && outcome != TaskResult.FAILED && outcome != TaskResult.CANCELLED
            || (outcome != TaskResult.SUCCEEDED) != failure.isPresent())
            throw new IllegalArgumentException("Only known graph outcomes can be frozen");
        facts.put("verifiedOutcome", outcome.name());
        failure.ifPresent(problem -> {
            facts.put("failureCode", problem.code().name());
            facts.put("failureEvent", problem.eventCode());
        });
        return mark(facts.path("phase").asText());
    }

    /** Releases the original graph occupancy after all actual component and local cleanup. / 所有实际组件及本地清理后释放原图占用。
     * @return whether original release was confirmed / 原释放是否获确认 */
    public boolean release() {
        if (!mark("CLEANUP_VERIFIED"))
            return false;
        try {
            checkpoint.close();
            return true;
        } catch (ApiException unconfirmed) {
            return false;
        }
    }
}
