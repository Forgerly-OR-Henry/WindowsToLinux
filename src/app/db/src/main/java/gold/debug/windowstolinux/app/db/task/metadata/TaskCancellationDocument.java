package gold.debug.windowstolinux.app.db.task.metadata;

import java.sql.SQLException;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Strict orthogonal cancellation intent in the existing host task metadata. / 既有宿主任务元数据内严格的正交取消意图。 */
public final class TaskCancellationDocument {
    /** Fixed host document codec. / 固定宿主文档编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Existing maximum admission document size. / 既有最大准入文档大小。 */
    private static final int LIMIT = 1024 * 1024;

    /** Prevents construction of the fixed host metadata codec. / 禁止构造固定宿主元数据编解码器。 */
    private TaskCancellationDocument() {
    }

    /** Validates the exact original top-level shape and optional boolean without interpreting graph authority. / 校验精确原顶层形状及可选布尔，不解释图授权。
     * @param value original host-owned metadata / 原宿主所属元数据
     * @return parsed original fields including their unchanged graph / 含未改图的已解析原字段
     * @throws SQLException if size, fields or types differ / 大小、字段或类型不符时 */
    public static ObjectNode parse(String value) throws SQLException {
        try {
            if (value == null || value.length() > LIMIT)
                throw new IllegalArgumentException("Invalid task metadata size");
            var node = JSON.readTree(value);
            if (!(node instanceof ObjectNode object) || !object.path("formatVersion").isInt()
                || !Set.of(1, 2, 3).contains(object.path("formatVersion").intValue())
                || !object.path("initialClaims").isArray()
                || object.has("cancelRequested") && !object.path("cancelRequested").isBoolean()
                || object.size() != (object.path("formatVersion").intValue() == 1 ? 2 : 3)
                + (object.has("cancelRequested") ? 1 : 0)
                || object.path("formatVersion").intValue() != 1 && !object.path("graph").isObject())
                throw new IllegalArgumentException("Unsupported original task metadata");
            return object;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new SQLException("Invalid original task metadata", invalid);
        }
    }

    /** Serializes original metadata while retaining the existing size bound. / 序列化原元数据，保留既有大小限制。
     * @param document original fields with a known host mutation / 带已知宿主修改的原字段
     * @return bounded serialized metadata / 有界序列化元数据
     * @throws SQLException if the original shape or bound differs / 原形状或限制不符时 */
    public static String encode(ObjectNode document) throws SQLException {
        try {
            String encoded = JSON.writeValueAsString(document);
            parse(encoded);
            return encoded;
        } catch (java.io.IOException invalid) {
            throw new SQLException("Original task metadata cannot be encoded", invalid);
        }
    }

    /** Records known host cancellation without changing original claims or graph. / 记录已知宿主取消，不改变原占用或图。
     * @param value original metadata / 原元数据
     * @return same original facts with durable cancellation intent / 带持久取消意图的同一原事实
     * @throws SQLException if original facts cannot be confirmed / 原事实无法确认时 */
    public static String request(String value) throws SQLException {
        return encode(parse(value).put("cancelRequested", true));
    }
}
