package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;

/** Strict bounded JSON for execution identity, reservation evidence and formal results. / 执行身份、预留证据及正式结果的严格有界JSON。 */
final class DeploymentExecutionCodec {
    /** Single complete current execution envelope. / 单一完整当前执行信封。 */
    static final int VERSION = 11;

    /** Initial single-unit publication request limit. / 初始单单元发布请求上限。 */
    static final int SINGLE_REQUEST_LIMIT = 40 * 1_048_576;

    /** Complete bounded graph template plus original references and request framing. / 完整有界整图模板加原引用与请求信封。 */
    static final int REQUEST_LIMIT = 144 * 1_048_576;

    /** Maximum complete correction-chain request size. / 完整纠正链请求大小上限。 */
    static final int READ_LIMIT = DeploymentPublishAttemptCodec.LIMIT;

    /** Prevents construction of the fixed codec. / 防止构造固定编解码器。 */
    private DeploymentExecutionCodec() {
    }

    /** Creates the original resource intent document before any reservation. / 在任一预留前创建原资源意图文档。
     * @param identity immutable operation identity / 不可变操作身份
     * @param operationType original operation name / 原操作名
     * @param intents exact resource associations / 精确资源关联
     * @param project complete reviewed project or explicit absence / 完整已审阅项目或明确不存在
     * @return immutable bounded request text / 不可变有界请求文本
     * @throws SQLException if metadata is invalid or cannot be encoded / 元数据无效或无法编码时 */
    static String request(DeploymentExecutionIdentity identity, String operationType,
                          List<DeploymentRefIntent> intents, Optional<DeploymentProjectRecord> project) throws SQLException {
        if (operationType == null || !operationType.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new SQLException("Invalid original operation type");
        var node = envelope(identity).put("operationType", operationType);
        node.putNull("publicationTemplate").putNull("buildIntent");
        node.putArray("buildReviews");
        for (String field : List.of("attemptProofs", "attemptReviews", "attemptBuildReviews", "attemptTemplates"))
            node.putArray(field);
        if (publicationOperation(operationType))
            node.put("attemptOperation", identity.taskId().toString());
        else
            node.putNull("attemptOperation");
        node.putNull("attemptTemplate").putNull("attemptIntent").putNull("attemptPendingProof").put("attemptFallbacks", 0);
        if (project.isPresent()) {
            var value = project.get();
            if (!identity.projectId().equals(Optional.of(value.id())))
                throw new SQLException("Reviewed project belongs to another execution");
            var source = node.putObject("projectSource").put("displayName", value.displayName())
                .put("kind", value.source().kind().name()).put("location", value.source().location().toString())
                .put("sha256", value.source().sha256()).put("createdAt", value.createdAt().toEpochMilli())
                .put("updatedAt", value.updatedAt().toEpochMilli());
            value.source().gitRef().ifPresentOrElse(ref -> source.put("gitRef", ref), () -> source.putNull("gitRef"));
            value.source().gitCommit().ifPresentOrElse(commit -> source.put("gitCommit", commit),
                () -> source.putNull("gitCommit"));
        } else {
            if (identity.projectId().isPresent())
                throw new SQLException("Execution lacks reviewed project facts");
            node.putNull("projectSource");
        }
        var array = node.putArray("references");
        for (var intent : List.copyOf(intents)) {
            var entry = array.addObject().put("kind", intent.kind().name()).put("resourceId", intent.resourceId())
                .put("businessKey", intent.businessKey());
            intent.purpose().ifPresentOrElse(value -> entry.put("purpose", value), () -> entry.putNull("purpose"));
        }
        references(node, identity);
        return encode(node, requestLimit(node));
    }

    /** Identifies the three engines using the same immutable formal publication template. / 识别使用同一不可变正式发布模板的三个引擎。
     * @param operationType exact persisted operation name / 精确持久操作名
     * @return whether formal publication framing applies / 是否适用正式发布信封 */
    private static boolean publicationOperation(String operationType) {
        return Set.of("deployment-publication", "deployment-agent", "deployment-restore").contains(operationType);
    }

    /** Records UUIDs only after they have been observed for original indexed intents. / 仅在观察到原索引意图的UUID后记录。
     * @param identity original identity / 原身份
     * @param phase last persisted stage / 最近持久阶段
     * @param ids original intent index to reservation UUID / 原意图索引到预留UUID
     * @return bounded recovery evidence / 有界恢复证据
     * @throws SQLException if encoding exceeds bounds / 编码超限时 */
    static String recovery(DeploymentExecutionIdentity identity, String phase, Map<Integer, UUID> ids)
        throws SQLException {
        var node = envelope(identity).put("reconciliationType", "deployment-references-v1").put("phase",
            phase);
        var array = node.putArray("referenceIds");
        new TreeMap<>(ids).forEach((index, id) -> array.addObject().put("index", index).put("id", id.toString()));
        return encode(node);
    }

    /** Encodes the formal outcome independently of actual cleanup. / 编码独立于实际收尾的正式结果。
     * @param identity original operation / 原操作
     * @param outcome observed business result / 已观察业务结果
     * @param problemCode original public failure category, absent for success / 原公开失败类别，成功时为空
     * @param eventCode safe stable result code / 安全稳定结果码
     * @param publications successfully committed units, empty for failure / 成功提交单元，失败时为空
     * @param release original whole-application history identity, absent for failure / 原整应用历史身份，失败时为空
     * @return bounded result document / 有界结果文档
     * @throws SQLException if encoding exceeds bounds / 编码超限时 */
    static String result(DeploymentExecutionIdentity identity, TaskResult outcome, Optional<ProblemCode> problemCode,
                         String eventCode, List<DeploymentUnitCommit> publications, Optional<DeploymentReleaseIdentity> release)
        throws SQLException {
        if (eventCode == null || !eventCode.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
            || (outcome == TaskResult.SUCCEEDED) == problemCode.isPresent())
            throw new SQLException("Invalid result event code");
        var node = envelope(identity).put("outcome", outcome.name()).put("eventCode", eventCode);
        if (problemCode.isPresent())
            node.put("problemCode", problemCode.orElseThrow().name());
        else
            node.putNull("problemCode");
        if (release.isPresent())
            node.putObject("applicationRelease").put("id", release.get().id().toString()).put("sha256",
                release.get().sha256());
        else
            node.putNull("applicationRelease");
        var array = node.putArray("releases");
        publications.stream().sorted(Comparator.comparing(p -> p.unit().id()))
            .forEach(p -> array.addObject().put("unitId", p.unit().id()).put("sha256", p.release().sha256())
                .put("configurationSha256", p.configuration().sha256())
                .put("publishedAt", p.release().publishedAt().toEpochMilli()));
        return encode(node);
    }

    /** Adds fixed identity fields to each current-version document. / 向每个当前版本文档添加固定身份字段。
     * @param identity original operation / 原操作
     * @return fixed versioned envelope / 固定版本化信封 */
    private static ObjectNode envelope(DeploymentExecutionIdentity identity) {
        var node = object().put("formatVersion", VERSION).put("executionId", identity.executionId().toString())
            .put("taskId", identity.taskId().toString()).put("applicationId", identity.applicationId())
            .put("serverId", identity.server().id()).put("reviewedRevision", identity.reviewedRevision())
            .put("mode", identity.mode().name());
        if (identity.projectId().isPresent())
            node.put("projectId", identity.projectId().get().toString());
        else
            node.putNull("projectId");
        return node;
    }

    /** Determines whether all original documents use the supported format. / 判断全部原文档是否使用受支持格式。
     * @param record retained original row / 保留原行
     * @return whether semantic reconciliation is supported / 是否支持语义核对
     * @throws SQLException if a document is malformed / 文档无效时 */
    static boolean supported(DeploymentExecutionRecord record) throws SQLException {
        boolean supported = current(parse(record.requestJson(), READ_LIMIT));
        supported &= current(parse(record.recoveryJson()));
        if (record.resultJson().isPresent()) supported &= current(parse(record.resultJson().get()));
        return supported;
    }

    /** Checks the exact integer version before interpreting business fields. / 解释业务字段前核对精确整数版本。
     * @param document parsed envelope / 已解析信封
     * @return whether current framing is supported / 是否支持当前分界 */
    private static boolean current(JsonNode document) {
        return document.path("formatVersion").isInt() && document.path("formatVersion").intValue() == VERSION;
    }

    /** Validates current-version semantics and refuses mutation of future versions. / 校验当前版本语义并拒绝更改未来版本。
     * @param record original stored record / 原存储记录
     * @return original resource intents and observed UUIDs / 原资源意图和已观察UUID
     * @throws SQLException if unsupported, inconsistent or malformed / 不支持、不一致或无效时 */
    static Checkpoint known(DeploymentExecutionRecord record) throws SQLException {
        if (!supported(record))
            throw new SQLException("Execution format requires a matching reader", "0A000");
        var request = parse(record.requestJson(), READ_LIMIT);
        identity(request, record.identity(), "operationType", "references", "projectSource", "publicationTemplate", "buildReviews", "buildIntent",
            "attemptProofs", "attemptReviews", "attemptBuildReviews", "attemptTemplates", "attemptOperation", "attemptTemplate", "attemptIntent", "attemptPendingProof", "attemptFallbacks");
        if (record.requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > requestLimit(request))
            throw new SQLException("Execution request exceeds its admitted operation boundary");
        var intents = references(request, record.identity());
        if (record.phase().equals("PREPARED") && (!request.path("publicationTemplate").isNull()
            || !request.path("buildIntent").isNull() || !array(request, "buildReviews", 2).isEmpty()))
            throw new SQLException("Prepared execution contains effects-stage publication evidence");
        boolean publication = publicationOperation(string(request, "operationType"));
        boolean assisted = assistedRequest(request);
        if (publication) {
            if (!request.path("publicationTemplate").isNull()) {
                var proposal = DeploymentPublishAttemptCodec.template(request, record.identity().executionId());
                if (proposal.isPresent()) {
                    if (singleRequest(request) != (proposal.orElseThrow().units().size() == 1))
                        throw new SQLException("Publish template graph differs from original references");
                    if (assisted) requireReviewedTemplate(record, request, proposal.orElseThrow());
                }
            }
            if (assisted && request.path("publicationTemplate").isObject())
                DeploymentPublishAttemptCodec.known(record, request);
            else {
                requireInitialAttempts(request);
                if (!uuid(request, "attemptOperation").equals(record.identity().taskId()))
                    throw new SQLException("Initial publication operation differs from original task");
            }
        } else {
            if (!request.path("publicationTemplate").isNull() || !request.path("attemptOperation").isNull())
                throw new SQLException("Reference operation contains publication facts");
            requireInitialAttempts(request);
        }
        buildReviews(request, record.identity());
        project(request, record.identity());
        if (!string(request, "operationType").matches("[a-z][a-z0-9_-]{0,63}"))
            throw new SQLException("Invalid original operation type");
        var recovery = parse(record.recoveryJson());
        identity(recovery, record.identity(), "reconciliationType", "phase", "referenceIds");
        if (!string(recovery, "reconciliationType").equals("deployment-references-v1")
            || !string(recovery, "phase").equals(record.phase())
            || !Set.of("PREPARED", "EXECUTING", "COMMITTED", "FINISHED").contains(record.phase()))
            throw new SQLException("Execution recovery stage differs from its row");
        var ids = new TreeMap<Integer, UUID>();
        for (var entry : array(recovery, "referenceIds", intents.size())) {
            fields(entry, Set.of("index", "id"));
            if (!entry.path("index").isInt())
                throw new SQLException("Invalid reference index");
            int index = entry.path("index").intValue();
            if (index < 0 || index >= intents.size() || ids.putIfAbsent(index, uuid(entry, "id")) != null)
                throw new SQLException("Reference checkpoint differs from original intents");
        }
        if (new HashSet<>(ids.values()).size() != ids.size())
            throw new SQLException("Duplicate reservation identity");
        if ((record.phase().equals("EXECUTING") || record.businessCommitted()) && ids.size() != intents.size())
            throw new SQLException("Execution lacks original reference reservations");
        if (record.businessCommitted() != (record.outcome().orElse(null) == TaskResult.SUCCEEDED)
            || record.phase().equals("COMMITTED") != (record.businessCommitted() && !record.cleanupComplete())
            || record.phase().equals("FINISHED") != record.cleanupComplete()
            || record.outcome().isPresent() != record.resultJson().isPresent())
            throw new SQLException("Execution result and stage disagree");
        if (record.resultJson().isPresent())
            validateResult(parse(record.resultJson().get()), record);
        return new Checkpoint(intents, Map.copyOf(ids));
    }

    /** Returns the original supported version without changing it during recovery. / 返回原受支持版本，恢复期间不改变它。
     * @param record original validated execution / 原已校验执行
     * @return original document version / 原文档版本
     * @throws SQLException if unsupported or malformed / 不支持或畸形时 */
    static int version(DeploymentExecutionRecord record) throws SQLException {
        known(record);
        return parse(record.requestJson(), READ_LIMIT).path("formatVersion").intValue();
    }

    /** Reads the complete original proposal only when it was durably saved before publish. / 仅在发布前已持久化时读取完整原提案。
     * @param record original execution / 原执行
     * @return original proposal or explicit absence / 原提案或明确缺失
     * @throws SQLException if evidence is malformed / 依据畸形时 */
    static Optional<DeploymentPublishProposal> publicationTemplate(DeploymentExecutionRecord record)
        throws SQLException {
        known(record);
        var request = parse(record.requestJson(), READ_LIMIT);
        return DeploymentPublishAttemptCodec.template(request, record.identity().executionId());
    }

    /** Adds an immutable single-unit proposal to the same original request before any publish. / 在发布前向同一原请求加入不可变单元提案。
     * @param record original execution / 原执行
     * @param publication full non-secret proposal / 完整非秘密提案
     * @return bounded request document / 有界请求文档
     * @throws SQLException if the original format or proposal conflicts / 原格式或提案冲突时 */
    static String withPublicationTemplate(DeploymentExecutionRecord record, DeploymentPublishProposal publication)
        throws SQLException {
        known(record);
        var request = parse(record.requestJson(), READ_LIMIT);
        if (!publicationOperation(string(request, "operationType")) || singleRequest(request) != (publication.units().size() == 1))
            throw new SQLException("Original execution cannot store a publication template");
        if (assistedRequest(request)) requireReviewedTemplate(record, request, publication);
        if (!array(request, "attemptReviews", 2).isEmpty())
            return DeploymentPublishAttemptCodec.withTemplate(record, publication);
        String template = DeploymentPublishTemplateCodec.encodeTemplate(publication);
        if (!request.path("publicationTemplate").isNull()) {
            if (!template.equals(request.path("publicationTemplate").toString()))
                throw new SQLException("Original publication proposal is immutable");
            return record.requestJson();
        }
        request.set("publicationTemplate", parse(template, DeploymentPublishTemplateCodec.LIMIT));
        return DeploymentPublishAttemptCodec.encodeBounded(request);
    }

    /** Checks the actual final template against every latest corrected unit and the formally confirmed root. / 按每个最近纠正单元及正式确认根核对实际最终模板。
     * @param record original formal row / 原正式行
     * @param request complete retained request / 完整留存请求
     * @param publication actual verified final template / 实际已核实最终模板
     * @throws SQLException if any original or corrected binding conflicts / 任一原绑定或纠正绑定冲突时 */
    private static void requireReviewedTemplate(DeploymentExecutionRecord record, JsonNode request,
                                                DeploymentPublishProposal publication) throws SQLException {
        if (!request.path("buildIntent").isNull())
            throw new SQLException("Environment recovery has not confirmed its final review");
        var latest = new LinkedHashMap<String, DeploymentAssistedBuildReview>();
        for (var review : buildReviews(request, record.identity())) latest.put(review.unitId(), review);
        for (var review : latest.values()) {
            var description = DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan());
            var component = publication.graph().components().stream().filter(value -> value.unit().id().equals(review.unitId())).findFirst();
            var unit = publication.units().stream().filter(value -> value.unit().id().equals(review.unitId())).findFirst();
            if (component.isEmpty() || unit.isEmpty())
                throw new SQLException("Corrected unit is absent from the final template");
            var actual = component.orElseThrow();
            var commit = unit.orElseThrow();
            var expectedConfig = object();
            var actualConfig = object();
            DeploymentPublishTemplateCodec.configuration(expectedConfig, description.configuration());
            DeploymentPublishTemplateCodec.configuration(actualConfig, commit.configuration());
            if (!actual.unit().server().id().equals(description.serverId()) || !actual.reviewedRuntime().equals(description.runtime())
                || !actual.resources().equals(description.resources()) || !commit.secrets().equals(description.secrets()) || !expectedConfig.equals(actualConfig))
                throw new SQLException("Final template differs from the complete confirmed build review");
        }
    }

    /** Determines the exact admitted unit scope from original references. / 从原引用确定精确准入单元范围。
     * @param request complete original request / 完整原请求
     * @return whether at most one unit is admitted / 是否至多准入一个单元
     * @throws SQLException if original references are malformed / 原引用畸形时 */
    static boolean singleRequest(JsonNode request) throws SQLException {
        String application = DeploymentRefKey.applicationKey(string(request, "applicationId"));
        int units = 0;
        for (var entry : array(request, "references", 1024))
            if (string(entry, "kind").equals("SERVER") && !string(entry, "businessKey").equals(application)) units++;
        return units <= 1;
    }

    /** Selects the current assisted publication contract. / 选择当前辅助发布合同。
     * @param request complete current request / 完整当前请求
     * @return whether assisted review is admitted / 是否准入辅助审阅
     * @throws SQLException if operation metadata is malformed / 操作元数据畸形时 */
    private static boolean assistedRequest(JsonNode request) throws SQLException {
        return string(request, "mode").equals("ASSISTED") && string(request, "operationType").equals("deployment-publication");
    }

    /** Rejects attempt evidence before its actual publication boundary. / 在实际发布边界前拒绝尝试依据。
     * @param request complete current request / 完整当前请求
     * @throws SQLException if inactive fields are not explicit empty values / 未生效字段不是明确空值时 */
    private static void requireInitialAttempts(JsonNode request) throws SQLException {
        for (String field : List.of("attemptProofs", "attemptReviews", "attemptBuildReviews", "attemptTemplates"))
            if (!array(request, field, 2).isEmpty()) throw new SQLException("Unexpected publication attempt history");
        for (String field : List.of("attemptTemplate", "attemptIntent", "attemptPendingProof"))
            if (!request.path(field).isNull()) throw new SQLException("Unexpected publication attempt evidence");
        if (!request.path("attemptFallbacks").isInt() || request.path("attemptFallbacks").intValue() != 0)
            throw new SQLException("Unexpected publication fallback allowance");
    }

    /** Keeps each responsibility's existing capacity at its actual current stage. / 在实际当前阶段保留各职责既有容量。
     * @param request complete current request / 完整当前请求
     * @return admitted complete UTF-8 byte capacity / 准入完整UTF-8字节容量
     * @throws SQLException if operation or stage metadata is malformed / 操作或阶段元数据畸形时 */
    static int requestLimit(JsonNode request) throws SQLException {
        if (!publicationOperation(string(request, "operationType"))) return 1_048_576;
        if (!array(request, "attemptReviews", 2).isEmpty() || !request.path("attemptIntent").isNull()
            || request.path("attemptFallbacks").intValue() != 0) return READ_LIMIT;
        if (!array(request, "buildReviews", 2).isEmpty() || !request.path("buildIntent").isNull()) return REQUEST_LIMIT;
        return singleRequest(request) ? SINGLE_REQUEST_LIMIT : REQUEST_LIMIT;
    }

    /** Reads actual complete build reviews from the existing formal request. / 从既有正式请求读取实际完整构建审阅。
     * @param record original execution / 原执行
     * @return original ordered review chain / 原有序审阅链
     * @throws SQLException if a bound or identity differs / 边界或身份不符时 */
    static List<DeploymentAssistedBuildReview> buildReviews(DeploymentExecutionRecord record) throws SQLException {
        known(record);
        var request = parse(record.requestJson(), READ_LIMIT);
        return buildReviews(request, record.identity());
    }

    /** Requires one monotonic original failure/review chain and its exact confirmed root. / 要求一条单调原失败审阅链及其精确已确认根。
     * @param request complete original request / 完整原请求
     * @param identity current original row / 当前原行
     * @return strict complete reviews / 严格完整审阅
     * @throws SQLException if the chain conflicts / 审阅链冲突时 */
    private static List<DeploymentAssistedBuildReview> buildReviews(JsonNode request, DeploymentExecutionIdentity identity) throws SQLException {
        if (identity.mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED
            || !string(request, "operationType").equals("deployment-publication")) {
            if (!array(request, "buildReviews", 2).isEmpty() || !request.path("buildIntent").isNull())
                throw new SQLException("Build reviews belong only to original assisted publication");
            return List.of();
        }
        var values = new ArrayList<DeploymentAssistedBuildReview>();
        var failures = new HashSet<String>();
        String root = null;
        for (var entry : array(request, "buildReviews", 2)) {
            var value = DeploymentAssistedBuildReview.decode(entry);
            var description = DeploymentAssistedBuildReviewCodec.decodeDescription(value.nextPlan());
            DeploymentAssistedBuildReview.requireContinuation(values, value.unitId(), value.previousPlan(), value.previousScript(), value.previousTools());
            if (value.attempt() != values.size() + 1 || root != null && !value.previousRoot().equals(root)
                || !failures.add(value.failureBinding()) || !description.serverId().equals(identity.server().id()))
                throw new SQLException("Original assisted build review chain changed");
            root = value.nextRoot();
            values.add(value);
        }
        if (!values.isEmpty() && !identity.reviewedRevision().equals(root))
            throw new SQLException("Build review lacks its exact confirmed formal root");
        var pending = request.path("buildIntent");
        if (!pending.isNull()) {
            var intent = DeploymentAssistedBuildIntent.decode(pending);
            DeploymentAssistedBuildReview.requireContinuation(values, intent.unitId(), intent.previousPlan(), intent.previousScript(), intent.previousTools());
            if (intent.attempt() != values.size() + 1 || !intent.previousRoot().equals(identity.reviewedRevision())
                || !DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan()).serverId().equals(identity.server().id())
                || singleRequest(request) != intent.originalGraph().isEmpty()
                || failures.contains(DeploymentAssistedBuildReview.failureBinding(intent.previousPlan(), intent.previousScript(), intent.previousTools(), intent.failureEvidence())))
                throw new SQLException("Pending recovery intent differs from the original formal root");
        }
        return List.copyOf(values);
    }

    /** Stores a complete typed recovery consent without advancing the formal plan. / 存储完整类型化恢复同意，不推进正式计划。
     * @param record original executing record / 原执行记录
     * @param intent explicitly reviewed bounded recovery / 明确审阅有界恢复
     * @return full explicitly versioned request / 完整明确版本化请求
     * @throws SQLException if original source, phase or chain is no longer available / 原来源、阶段或链不再可用时 */
    static String withBuildIntent(DeploymentExecutionRecord record, DeploymentAssistedBuildIntent intent) throws SQLException {
        known(record);
        var request = parse(record.requestJson(), READ_LIMIT);
        var prior = buildReviews(record);
        DeploymentAssistedBuildReview.requireContinuation(prior, intent.unitId(), intent.previousPlan(), intent.previousScript(), intent.previousTools());
        if (!publicationOperation(string(request, "operationType")) || !DeploymentPublishAttemptCodec.buildBoundary(request)
            || record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED
            || !string(request, "operationType").equals("deployment-publication") || !intent.previousRoot().equals(record.identity().reviewedRevision())
            || intent.attempt() != prior.size() + 1 || singleRequest(request) != intent.originalGraph().isEmpty()
            || !request.path("buildIntent").isNull())
            throw new SQLException("Original build cannot start another recovery intent");
        var reviews = request.putArray("buildReviews");
        for (var review : prior) reviews.add(parse(review.encode(), REQUEST_LIMIT));
        request.set("buildIntent", parse(intent.encode(), REQUEST_LIMIT));
        return DeploymentPublishAttemptCodec.encodeBounded(request);
    }

    /** Returns retained full consent for observation or exact consumption, never automatic recovery execution. / 返回留存完整同意供观察或精确消费，绝不自动执行恢复。
     * @param record original authenticated record / 原已认证记录
     * @return existing bounded intent / 已有有界意图
     * @throws SQLException if the retained request conflicts / 留存请求冲突时 */
    static Optional<DeploymentAssistedBuildIntent> buildIntent(DeploymentExecutionRecord record) throws SQLException {
        known(record);
        var request = parse(record.requestJson(), READ_LIMIT);
        return !request.path("buildIntent").isNull()
            ? Optional.of(DeploymentAssistedBuildIntent.decode(request.path("buildIntent"))) : Optional.empty();
    }

    /** Appends one fully reviewed build-only correction before any publication template. / 在任一发布模板前追加一份完整审阅的仅构建纠正。
     * @param record original executing row / 原执行行
     * @param review actual bounded new review / 实际有界新审阅
     * @return explicitly versioned complete request / 明确版本化完整请求
     * @throws SQLException if a previous binding or immutable stage differs / 原绑定或不可变阶段不符时 */
    static String withBuildReview(DeploymentExecutionRecord record, DeploymentAssistedBuildReview review) throws SQLException {
        known(record);
        var request = parse(record.requestJson(), READ_LIMIT);
        var prior = buildReviews(record);
        if (!publicationOperation(string(request, "operationType")) || !DeploymentPublishAttemptCodec.buildBoundary(request)
            || record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED
            || !string(request, "operationType").equals("deployment-publication")
            || !review.previousRoot().equals(record.identity().reviewedRevision())
            || review.attempt() != prior.size() + 1 || prior.stream().anyMatch(value -> value.failureBinding().equals(review.failureBinding()))
            || request.path("buildIntent").isNull() || !DeploymentAssistedBuildIntent.decode(request.path("buildIntent")).matches(review))
            throw new SQLException("Original assisted build cannot adopt another review");
        request.put("reviewedRevision", review.nextRoot());
        var reviews = request.putArray("buildReviews");
        for (var value : prior) reviews.add(parse(value.encode(), REQUEST_LIMIT));
        reviews.add(parse(review.encode(), REQUEST_LIMIT));
        request.putNull("buildIntent");
        return DeploymentPublishAttemptCodec.encodeBounded(request);
    }

    /** Checks the immutable identity envelope and exact current-version fields. / 检查不可变身份信封及当前版本精确字段。
     * @param node original object / 原对象
     * @param identity original row identity / 原行身份
     * @param extras document-specific fields / 文档专属字段
     * @throws SQLException if identity or fields differ / 身份或字段不符时 */
    private static void identity(JsonNode node, DeploymentExecutionIdentity identity, String... extras)
        throws SQLException {
        var expected = new HashSet<>(Set.of("formatVersion", "executionId", "taskId", "applicationId", "serverId",
            "reviewedRevision", "mode", "projectId"));
        expected.addAll(List.of(extras));
        fields(node, expected);
        if (!uuid(node, "executionId").equals(identity.executionId()) || !uuid(node, "taskId").equals(identity.taskId())
            || !string(node, "applicationId").equals(identity.applicationId())
            || !string(node, "serverId").equals(identity.server().id())
            || !string(node, "reviewedRevision").equals(identity.reviewedRevision())
            || !string(node, "mode").equals(identity.mode().name())
            || !(node.path("projectId").isNull() ? Optional.<UUID>empty() : Optional.of(uuid(node, "projectId")))
            .equals(identity.projectId()))
            throw new SQLException("Execution document belongs to another original operation");
    }

    /** Reads the immutable reviewed project facts from the original request. / 从原请求读取不可变已审阅项目事实。
     * @param record retained original execution / 已留存原执行
     * @return frozen project or explicit absence / 冻结项目或明确不存在
     * @throws SQLException if format or stored facts are invalid / 格式或已存事实无效时 */
    static Optional<DeploymentProjectRecord> project(DeploymentExecutionRecord record) throws SQLException {
        known(record);
        return project(parse(record.requestJson(), READ_LIMIT), record.identity());
    }

    /** Decodes and checks the exact original project snapshot. / 解码并核验精确原项目快照。
     * @param request current-version original request / 当前版本原请求
     * @param identity original execution identity / 原执行身份
     * @return frozen project or explicit absence / 冻结项目或明确不存在
     * @throws SQLException if facts differ or are malformed / 事实不符或无效时 */
    private static Optional<DeploymentProjectRecord> project(JsonNode request, DeploymentExecutionIdentity identity)
        throws SQLException {
        var node = request.path("projectSource");
        if (node.isNull()) {
            if (identity.projectId().isPresent())
                throw new SQLException("Execution project snapshot is absent");
            return Optional.empty();
        }
        if (identity.projectId().isEmpty())
            throw new SQLException("Execution project snapshot has no identity");
        fields(node,
            Set.of("displayName", "kind", "location", "gitRef", "gitCommit", "sha256", "createdAt", "updatedAt"));
        try {
            if (!node.path("createdAt").isIntegralNumber() || !node.path("createdAt").canConvertToLong()
                || !node.path("updatedAt").isIntegralNumber() || !node.path("updatedAt").canConvertToLong())
                throw new IllegalArgumentException("Invalid project time");
            var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.valueOf(string(node, "kind")),
                URI.create(string(node, "location")),
                node.path("gitRef").isNull() ? Optional.empty() : Optional.of(string(node, "gitRef")),
                node.path("gitCommit").isNull() ? Optional.empty() : Optional.of(string(node, "gitCommit")),
                string(node, "sha256"));
            return Optional.of(new DeploymentProjectRecord(identity.projectId().orElseThrow(),
                string(node, "displayName"), source, Instant.ofEpochMilli(node.path("createdAt").longValue()),
                Instant.ofEpochMilli(node.path("updatedAt").longValue())));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Stored execution project snapshot is invalid", invalid);
        }
    }

    /** Decodes exact resource intent order with no duplicate association keys. / 解码精确资源意图顺序，不允许重复关联键。
     * @param request original request object / 原请求对象
     * @param identity original row identity / 原行身份
     * @return immutable ordered intents / 不可变有序意图
     * @throws SQLException if a resource or purpose is invalid / 资源或用途无效时 */
    private static List<DeploymentRefIntent> references(JsonNode request, DeploymentExecutionIdentity identity)
        throws SQLException {
        var result = new ArrayList<DeploymentRefIntent>();
        var associations = new HashSet<List<String>>();
        try {
            for (var entry : array(request, "references", 512)) {
                var kind = DeploymentRefIntent.DeploymentRefKind.valueOf(string(entry, "kind"));
                fields(entry, Set.of("kind", "resourceId", "businessKey", "purpose"));
                var intent = new DeploymentRefIntent(kind, string(entry, "resourceId"),
                    entry.get("purpose").isNull() ? Optional.empty() : Optional.of(string(entry, "purpose")),
                    string(entry, "businessKey"));
                if (kind == DeploymentRefIntent.DeploymentRefKind.SERVER
                    && !intent.resourceId().equals(identity.server().id()))
                    throw new SQLException("Execution references another server");
                if (!associations.add(List.of(kind.name(), intent.resourceId(), intent.businessKey())))
                    throw new SQLException("Duplicate original resource association");
                result.add(intent);
            }
            if (result.isEmpty()
                || result.stream()
                .noneMatch(i -> i.kind() == DeploymentRefIntent.DeploymentRefKind.SERVER)
                || new HashSet<>(result).size() != result.size())
                throw new SQLException("Execution requires unique original references and its server");
            return List.copyOf(result);
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Execution reference metadata is invalid", invalid);
        }
    }

    /** Verifies formal outcome metadata without confusing cleanup with business success. / 核验正式结果元数据，不混淆收尾与业务成功。
     * @param result original result document / 原结果文档
     * @param record original row / 原行
     * @throws SQLException if the result is inconsistent / 结果不一致时 */
    private static void validateResult(JsonNode result, DeploymentExecutionRecord record) throws SQLException {
        identity(result, record.identity(), "outcome", "problemCode", "eventCode", "releases", "applicationRelease");
        if (!string(result, "outcome").equals(record.outcome().orElseThrow().name())
            || !string(result, "eventCode").matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
            throw new SQLException("Formal execution result differs from its row");
        if (record.outcome().orElseThrow() == TaskResult.SUCCEEDED) {
            if (!result.path("problemCode").isNull())
                throw new SQLException("Successful execution has a failure category");
        } else {
            try {
                ProblemCode.valueOf(string(result, "problemCode"));
            } catch (IllegalArgumentException invalid) {
                throw new SQLException("Unknown formal problem category", invalid);
            }
        }
        if (record.businessCommitted() != release(result).isPresent())
            throw new SQLException("Formal application release evidence is incomplete");
        var releases = array(result, "releases", 64);
        if (record.businessCommitted() == releases.isEmpty())
            throw new SQLException("Formal publication evidence is incomplete");
        var units = new HashSet<String>();
        for (var release : releases) {
            fields(release, Set.of("unitId", "sha256", "configurationSha256", "publishedAt"));
            if (!string(release, "unitId").matches("[a-z0-9][a-z0-9-]{0,62}") || !units.add(string(release, "unitId"))
                || !string(release, "sha256").matches("[0-9a-f]{64}")
                || !string(release, "configurationSha256").matches("[0-9a-f]{64}")
                || !release.path("publishedAt").isIntegralNumber()
                || !release.path("publishedAt").canConvertToLong())
                throw new SQLException("Formal publication metadata is invalid");
        }
    }

    /** Restores only bounded original problem category and event, never a discarded message. / 仅恢复有界原问题类别及事件，不补造已舍弃消息。
     * @param record supported original execution / 受支持原执行
     * @return original public failure metadata or absence / 原公开失败元数据或空
     * @throws SQLException if the result is unsupported or malformed / 结果不受支持或无效时 */
    static Optional<Problem> problem(DeploymentExecutionRecord record) throws SQLException {
        known(record);
        if (record.outcome().orElse(null) == TaskResult.SUCCEEDED || record.resultJson().isEmpty())
            return Optional.empty();
        var result = parse(record.resultJson().orElseThrow());
        return Optional.of(new Problem(ProblemCode.valueOf(string(result, "problemCode")), string(result, "eventCode"),
            "Deployment result requires review", Map.of(), Optional.empty()));
    }

    /** Reads a supported execution's immutable history link. / 读取受支持执行的不可变历史关联。
     * @param record original execution / 原执行
     * @return history identity when business committed / 业务提交时的历史身份
     * @throws SQLException if the evidence is unsupported or malformed / 证据不支持或无效时 */
    static Optional<DeploymentReleaseIdentity> release(DeploymentExecutionRecord record) throws SQLException {
        known(record);
        return record.resultJson().isEmpty() ? Optional.empty() : release(parse(record.resultJson().get()));
    }

    /** Decodes the explicit link without inventing missing metadata. / 解码明确关联，不补造缺失元数据。
     * @param result validated result envelope / 已校验结果信封
     * @return original link or explicit absence / 原关联或明确不存在
     * @throws SQLException if the link is malformed / 关联无效时 */
    private static Optional<DeploymentReleaseIdentity> release(JsonNode result) throws SQLException {
        var link = result.path("applicationRelease");
        if (link.isNull())
            return Optional.empty();
        fields(link, Set.of("id", "sha256"));
        try {
            return Optional.of(new DeploymentReleaseIdentity(uuid(link, "id"), string(link, "sha256")));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Invalid application release identity", invalid);
        }
    }

    /** Decoded current-version recovery inputs, never fabricated for a future format. / 已解码当前版本恢复输入，不为未来格式补造。
     * @param intents original ordered associations / 原有序关联
     * @param ids original index-to-UUID observations / 原索引到UUID观测 */
    record Checkpoint(List<DeploymentRefIntent> intents, Map<Integer, UUID> ids) {
    }
}
