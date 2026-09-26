package gold.debug.windowstolinux.app.db.task;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

/** Bounded host recovery facts stored atomically with an explicit lease. / 与显式占用原子保存的有界宿主恢复事实。
 * @param protocol exact versioned host protocol / 精确的带版本宿主协议
 * @param fields immutable non-secret recovery facts / 不可变且不含秘密的恢复事实 */
public record ResourceRecovery(String protocol, Map<String, String> fields) {
    /** Existing encoding for logical admission claims. / 逻辑准入申请的既有编码。 */
    static final String TASK_END = "{\"release\":\"TASK_END\"}";

    /** Existing encoding for explicit claims without recovery facts. / 不含恢复事实的显式申请既有编码。 */
    static final String EXPLICIT = "{\"release\":\"EXPLICIT\"}";

    /** Strict decoder rejecting duplicate keys and trailing documents. / 拒绝重复键及尾随文档的严格解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(3)
                    .maxStringLength(65536).build())
            .build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Validates bounded flat facts before any database write. / 在任何数据库写入前校验有界扁平事实。
     * @param protocol versioned host protocol / 带版本宿主协议
     * @param fields non-secret string facts / 不含秘密的字符串事实 */
    public ResourceRecovery {
        if (protocol == null || !protocol.matches("[a-z][a-z0-9.-]{0,63}"))
            throw new IllegalArgumentException("Invalid resource recovery protocol");
        fields = Map.copyOf(fields);
        if (fields.isEmpty() || fields.size() > 64)
            throw new IllegalArgumentException("Invalid resource recovery facts");
        for (var entry : fields.entrySet())
            if (!entry.getKey().matches("[a-zA-Z][a-zA-Z0-9.]{0,63}") || entry.getValue().length() > 16384)
                throw new IllegalArgumentException("Invalid resource recovery field");
    }

    /** Encodes one supported explicit-release envelope. / 编码一个受支持的显式释放封套。
     * @return bounded versioned JSON / 有界带版本 JSON
     * @throws SQLException if encoding exceeds its limit / 编码超限时 */
    String encode() throws SQLException {
        try {
            String value = JSON.writeValueAsString(
                    Map.of("release", "EXPLICIT", "version", 1, "protocol", protocol, "fields", new TreeMap<>(fields)));
            if (value.length() > 65536)
                throw new SQLException("Resource recovery metadata is too large");
            return value;
        } catch (IOException failure) {
            throw new SQLException("Cannot encode resource recovery metadata", failure);
        }
    }

    /** Decodes only a supported flat envelope; corrupt facts never authorize release. / 仅解码受支持的扁平封套；损坏事实不授权释放。
     * @param value original stored envelope / 原存储封套
     * @return typed immutable facts / 有类型的不可变事实
     * @throws SQLException if the envelope is unknown or invalid / 封套未知或无效时 */
    static ResourceRecovery decode(String value) throws SQLException {
        if (value == null || value.length() > 65536)
            throw new SQLException("Invalid resource recovery metadata size");
        try {
            var root = JSON.readTree(value);
            if (root == null || !root.isObject() || root.size() != 4 || !root.path("release").isTextual()
                    || !root.path("release").textValue().equals("EXPLICIT") || !root.path("version").isInt()
                    || root.path("version").intValue() != 1 || !root.path("protocol").isTextual()
                    || !root.path("fields").isObject())
                throw new IllegalArgumentException("Unknown recovery envelope");
            var fields = new TreeMap<String, String>();
            var entries = root.path("fields").fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (!entry.getValue().isTextual())
                    throw new IllegalArgumentException("Recovery facts must be strings");
                fields.put(entry.getKey(), entry.getValue().textValue());
            }
            return new ResourceRecovery(root.path("protocol").textValue(), fields);
        } catch (IOException | IllegalArgumentException failure) {
            throw new SQLException("Invalid resource recovery metadata", failure);
        }
    }

    /** Checks explicit ownership without accepting automatic or malformed envelopes. / 检查显式归属，不接受自动或损坏封套。
     * @param value original stored envelope / 原存储封套
     * @throws SQLException if explicit release is not established / 无法确立显式释放时 */
    static void requireExplicit(String value) throws SQLException {
        if (!EXPLICIT.equals(value))
            decode(value);
    }
}
