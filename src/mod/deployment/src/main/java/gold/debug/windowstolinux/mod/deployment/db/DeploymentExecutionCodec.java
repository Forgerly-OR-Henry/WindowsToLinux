package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;

/** Strict bounded JSON for execution identity, reservation evidence and formal results. / 执行身份、预留证据及正式结果的严格有界JSON。 */
final class DeploymentExecutionCodec {
    /** Current journal version freezes the reviewed project's complete source revision. / 当前账本版本冻结已审阅项目的完整来源修订。 */
    static final int VERSION = 4;

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
            List<DeploymentReferenceIntent> intents, Optional<DeploymentProjectRecord> project) throws SQLException {
        if (operationType == null || !operationType.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new SQLException("Invalid original operation type");
        var node = envelope(identity).put("operationType", operationType);
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
            intent.purpose().ifPresent(value -> entry.put("purpose", value));
        }
        references(node, identity);
        return encode(node);
    }

    /** Records UUIDs only after they have been observed for original indexed intents. / 仅在观察到原索引意图的UUID后记录。
     * @param identity original identity / 原身份
     * @param phase last persisted stage / 最近持久阶段
     * @param ids original intent index to reservation UUID / 原意图索引到预留UUID
     * @return bounded recovery evidence / 有界恢复证据
     * @throws SQLException if encoding exceeds bounds / 编码超限时 */
    static String recovery(DeploymentExecutionIdentity identity, String phase, Map<Integer, UUID> ids)
            throws SQLException {
        var node = envelope(identity).put("reconciliationType", "deployment-references-v1").put("phase", phase);
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
    static String result(DeploymentExecutionIdentity identity, Outcome outcome, Optional<ProblemCode> problemCode,
            String eventCode, List<DeploymentUnitCommit> publications, Optional<DeploymentReleaseIdentity> release)
            throws SQLException {
        if (eventCode == null || !eventCode.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                || (outcome == Outcome.SUCCEEDED) == problemCode.isPresent())
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
        boolean supported = parse(record.requestJson()).path("formatVersion").intValue() == VERSION;
        supported &= parse(record.recoveryJson()).path("formatVersion").intValue() == VERSION;
        if (record.resultJson().isPresent())
            supported &= parse(record.resultJson().get()).path("formatVersion").intValue() == VERSION;
        return supported;
    }

    /** Validates current-version semantics and refuses mutation of future versions. / 校验当前版本语义并拒绝更改未来版本。
     * @param record original stored record / 原存储记录
     * @return original resource intents and observed UUIDs / 原资源意图和已观察UUID
     * @throws SQLException if unsupported, inconsistent or malformed / 不支持、不一致或无效时 */
    static Checkpoint known(DeploymentExecutionRecord record) throws SQLException {
        if (!supported(record))
            throw new SQLException("Execution format requires a matching reader", "0A000");
        var request = parse(record.requestJson());
        identity(request, record.identity(), "operationType", "references", "projectSource");
        project(request, record.identity());
        if (!string(request, "operationType").matches("[a-z][a-z0-9_-]{0,63}"))
            throw new SQLException("Invalid original operation type");
        var intents = references(request, record.identity());
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
        if (record.businessCommitted() != (record.outcome().orElse(null) == Outcome.SUCCEEDED)
                || record.phase().equals("COMMITTED") != (record.businessCommitted() && !record.cleanupComplete())
                || record.phase().equals("FINISHED") != record.cleanupComplete()
                || record.outcome().isPresent() != record.resultJson().isPresent())
            throw new SQLException("Execution result and stage disagree");
        if (record.resultJson().isPresent())
            validateResult(parse(record.resultJson().get()), record);
        return new Checkpoint(intents, Map.copyOf(ids));
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
        return project(parse(record.requestJson()), record.identity());
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
    private static List<DeploymentReferenceIntent> references(JsonNode request, DeploymentExecutionIdentity identity)
            throws SQLException {
        var result = new ArrayList<DeploymentReferenceIntent>();
        var associations = new HashSet<List<String>>();
        try {
            for (var entry : array(request, "references", 512)) {
                var kind = DeploymentReferenceIntent.DeploymentReferenceKind.valueOf(string(entry, "kind"));
                fields(entry,
                        kind == DeploymentReferenceIntent.DeploymentReferenceKind.SECRET
                                ? Set.of("kind", "resourceId", "businessKey", "purpose")
                                : Set.of("kind", "resourceId", "businessKey"));
                var intent = new DeploymentReferenceIntent(kind, string(entry, "resourceId"),
                        entry.has("purpose") ? Optional.of(string(entry, "purpose")) : Optional.empty(),
                        string(entry, "businessKey"));
                if (kind == DeploymentReferenceIntent.DeploymentReferenceKind.SERVER
                        && !intent.resourceId().equals(identity.server().id()))
                    throw new SQLException("Execution references another server");
                if (!associations.add(List.of(kind.name(), intent.resourceId(), intent.businessKey())))
                    throw new SQLException("Duplicate original resource association");
                result.add(intent);
            }
            if (result.isEmpty()
                    || result.stream()
                            .noneMatch(i -> i.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SERVER)
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
        if (record.outcome().orElseThrow() == Outcome.SUCCEEDED) {
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
        if (record.outcome().orElse(null) == Outcome.SUCCEEDED || record.resultJson().isEmpty())
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
    record Checkpoint(List<DeploymentReferenceIntent> intents, Map<Integer, UUID> ids) {
    }
}
