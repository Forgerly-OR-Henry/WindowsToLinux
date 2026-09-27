package gold.debug.windowstolinux.mod.deployment.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.task.Outcome;

/** Strict bounded JSON for execution identity, reservation evidence and formal results. / 执行身份、预留证据及正式结果的严格有界JSON。 */
final class DeploymentExecutionCodec {
    /** Existing project JSON implementation with duplicate and trailing input rejection. / 已有项目JSON实现，拒绝重复键及尾随输入。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Maximum persisted document size in UTF-8 bytes. / 持久文档的UTF-8字节上限。 */
    private static final int LIMIT = 1_048_576;

    /** Prevents construction of the fixed codec. / 防止构造固定编解码器。 */
    private DeploymentExecutionCodec() {
    }

    /** Creates the original resource intent document before any reservation. / 在任一预留前创建原资源意图文档。
     * @param identity immutable operation identity / 不可变操作身份
     * @param operationType original operation name / 原操作名
     * @param intents exact resource associations / 精确资源关联
     * @return immutable bounded request text / 不可变有界请求文本
     * @throws SQLException if metadata is invalid or cannot be encoded / 元数据无效或无法编码时 */
    static String request(DeploymentExecutionIdentity identity, String operationType,
            List<DeploymentReferenceIntent> intents) throws SQLException {
        if (operationType == null || !operationType.matches("[a-z][a-z0-9_]{0,63}"))
            throw new SQLException("Invalid original operation type");
        var node = envelope(identity).put("operationType", operationType);
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
     * @param eventCode safe stable result code / 安全稳定结果码
     * @param publications successfully committed units, empty for failure / 成功提交单元，失败时为空
     * @return bounded result document / 有界结果文档
     * @throws SQLException if encoding exceeds bounds / 编码超限时 */
    static String result(DeploymentExecutionIdentity identity, Outcome outcome, String eventCode,
            List<DeploymentUnitCommit> publications) throws SQLException {
        if (eventCode == null || !eventCode.matches("[A-Z][A-Z0-9_]{0,127}"))
            throw new SQLException("Invalid result event code");
        var node = envelope(identity).put("outcome", outcome.name()).put("eventCode", eventCode);
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
        var node = JSON.createObjectNode().put("formatVersion", 1).put("executionId", identity.executionId().toString())
                .put("taskId", identity.taskId().toString()).put("applicationId", identity.applicationId())
                .put("serverId", identity.server().id()).put("reviewedRevision", identity.reviewedRevision())
                .put("mode", identity.mode().name());
        if (identity.projectId().isPresent())
            node.put("projectId", identity.projectId().get().toString());
        else
            node.putNull("projectId");
        return node;
    }

    /** Reads well-formed UTF-8 without interpreting a future format. / 读取合法UTF-8，不解释未来格式。
     * @param bytes original bounded bytes / 原有界字节
     * @return unchanged original JSON text / 未改写的原JSON文本
     * @throws SQLException if bytes or JSON are malformed / 字节或JSON无效时 */
    static String text(byte[] bytes) throws SQLException {
        if (bytes == null || bytes.length > LIMIT)
            throw new SQLException("Missing or oversized execution document");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            parse(text);
            return text;
        } catch (CharacterCodingException invalid) {
            throw new SQLException("Execution document is not UTF-8", invalid);
        }
    }

    /** Parses only a bounded JSON object with an explicit positive format version. / 仅解析带明确正格式版本的有界JSON对象。
     * @param text original JSON / 原JSON
     * @return parsed object / 解析对象
     * @throws SQLException if shape, size or syntax is invalid / 形状、大小或语法无效时 */
    private static ObjectNode parse(String text) throws SQLException {
        try {
            if (text == null || text.getBytes(StandardCharsets.UTF_8).length > LIMIT)
                throw new IOException("Execution document exceeds bound");
            var node = JSON.readTree(text);
            if (!(node instanceof ObjectNode object) || !object.path("formatVersion").isInt()
                    || object.path("formatVersion").intValue() < 1)
                throw new IOException("Execution document version is absent");
            return object;
        } catch (IOException invalid) {
            throw new SQLException("Execution document is malformed", invalid);
        }
    }

    /** Encodes an object under the same fixed bound as reads. / 在与读取相同的固定上限下编码对象。
     * @param node original object / 原对象
     * @return UTF-8 JSON text / UTF-8 JSON文本
     * @throws SQLException if encoding fails or exceeds bounds / 编码失败或超限时 */
    private static String encode(ObjectNode node) throws SQLException {
        try {
            return text(JSON.writeValueAsBytes(node));
        } catch (IOException invalid) {
            throw new SQLException("Execution document cannot be encoded", invalid);
        }
    }

    /** Determines whether all original documents use the supported format. / 判断全部原文档是否使用受支持格式。
     * @param record retained original row / 保留原行
     * @return whether semantic reconciliation is supported / 是否支持语义核对
     * @throws SQLException if a document is malformed / 文档无效时 */
    static boolean supported(DeploymentExecutionRecord record) throws SQLException {
        boolean supported = parse(record.requestJson()).path("formatVersion").intValue() == 1;
        supported &= parse(record.recoveryJson()).path("formatVersion").intValue() == 1;
        if (record.resultJson().isPresent())
            supported &= parse(record.resultJson().get()).path("formatVersion").intValue() == 1;
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
        identity(request, record.identity(), "operationType", "references");
        if (!string(request, "operationType").matches("[a-z][a-z0-9_]{0,63}"))
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
        identity(result, record.identity(), "outcome", "eventCode", "releases");
        if (!string(result, "outcome").equals(record.outcome().orElseThrow().name())
                || !string(result, "eventCode").matches("[A-Z][A-Z0-9_]{0,127}"))
            throw new SQLException("Formal execution result differs from its row");
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

    /** Requires a JSON array under a concrete bound. / 要求具体上限内的JSON数组。
     * @param node source object / 来源对象
     * @param field exact field / 精确字段
     * @param limit maximum entries / 最大条目数
     * @return original array / 原数组
     * @throws SQLException if shape or size differs / 形状或大小不符时 */
    private static ArrayNode array(JsonNode node, String field, int limit) throws SQLException {
        if (!(node.path(field) instanceof ArrayNode array) || array.size() > limit)
            throw new SQLException("Execution array is invalid");
        return array;
    }

    /** Requires exact fields without implicit defaults or coercion. / 要求精确字段，不使用隐式默认或类型强转。
     * @param node source object / 来源对象
     * @param expected exact field names / 精确字段名
     * @throws SQLException if fields differ / 字段不符时 */
    private static void fields(JsonNode node, Set<String> expected) throws SQLException {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected))
            throw new SQLException("Execution fields are invalid");
    }

    /** Reads a required text value without JSON coercion. / 读取必需文本，不进行JSON类型强转。
     * @param node source object / 来源对象
     * @param field exact field / 精确字段
     * @return original string / 原字符串
     * @throws SQLException if absent or not textual / 缺失或非文本时 */
    private static String string(JsonNode node, String field) throws SQLException {
        if (!node.path(field).isTextual())
            throw new SQLException("Execution text is absent");
        return node.path(field).textValue();
    }

    /** Reads a canonical original UUID. / 读取规范原UUID。
     * @param node source object / 来源对象
     * @param field exact field / 精确字段
     * @return original identity / 原身份
     * @throws SQLException if not canonical / 非规范值时 */
    private static UUID uuid(JsonNode node, String field) throws SQLException {
        String value = string(node, field);
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value))
                throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Execution UUID is invalid", invalid);
        }
    }

    /** Decoded current-version recovery inputs, never fabricated for a future format. / 已解码当前版本恢复输入，不为未来格式补造。
     * @param intents original ordered associations / 原有序关联
     * @param ids original index-to-UUID observations / 原索引到UUID观测 */
    record Checkpoint(List<DeploymentReferenceIntent> intents, Map<Integer, UUID> ids) {
    }
}
