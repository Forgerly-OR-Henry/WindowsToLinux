package gold.debug.windowstolinux.mod.deployment.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** Shared strict JSON primitives for versioned business evidence. / 版本化业务证据共用的严格JSON原语。 */
final class DeploymentJsonDocument {
    /** Existing project JSON parser with strict duplicate and trailing content checks. / 已有项目JSON解析器，严格检查重复键和尾随内容。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Default bound for one business document. / 单份执行文档的默认上限。 */
    private static final int LIMIT = 1_048_576;

    /** Prevents construction of the fixed primitives. / 防止构造固定原语。 */
    private DeploymentJsonDocument() {
    }

    /** Creates an empty object for an explicitly versioned document. / 创建用于明确版本化文档的空对象。
     * @return new object / 新对象 */
    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    /** Reads well-formed UTF-8 without interpreting a future format. / 读取合法UTF-8，不解释未来格式。
     * @param bytes original bounded bytes / 原有界字节
     * @return unchanged original JSON text / 未改写的原JSON文本
     * @throws SQLException if bytes or JSON are malformed / 字节或JSON无效时 */
    static String text(byte[] bytes) throws SQLException {
        return text(bytes, LIMIT);
    }

    /** Uses the concrete format's declared byte bound. / 使用具体格式声明的字节上限。
     * @param bytes original document value / 原文档值
     * @param limit declared UTF-8 byte bound / 声明的UTF-8字节上限
     * @return validated document value / 已校验文档值
     * @throws SQLException if validation or encoding fails / 校验或编码失败时 */
    static String text(byte[] bytes, int limit) throws SQLException {
        if (bytes == null || bytes.length > limit)
            throw new SQLException("Missing or oversized business document");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            parse(text, limit);
            return text;
        } catch (CharacterCodingException invalid) {
            throw new SQLException("Business document is not UTF-8", invalid);
        }
    }

    /** Parses only a bounded JSON object with an explicit positive format version. / 仅解析带明确正格式版本的有界JSON对象。
     * @param text original JSON / 原JSON
     * @return parsed object / 解析对象
     * @throws SQLException if shape, size or syntax is invalid / 形状、大小或语法无效时 */
    static ObjectNode parse(String text) throws SQLException {
        return parse(text, LIMIT);
    }

    /** Uses the concrete format's declared byte bound. / 使用具体格式声明的字节上限。
     * @param text original document value / 原文档值
     * @param limit declared UTF-8 byte bound / 声明的UTF-8字节上限
     * @return validated document value / 已校验文档值
     * @throws SQLException if validation or encoding fails / 校验或编码失败时 */
    static ObjectNode parse(String text, int limit) throws SQLException {
        try {
            if (text == null || text.getBytes(StandardCharsets.UTF_8).length > limit)
                throw new IOException("Business document exceeds bound");
            var node = JSON.readTree(text);
            if (!(node instanceof ObjectNode object) || !object.path("formatVersion").isInt()
                || object.path("formatVersion").intValue() < 1)
                throw new IOException("Business document version is absent");
            return object;
        } catch (IOException invalid) {
            throw new SQLException("Business document is malformed", invalid);
        }
    }

    /** Encodes an object under the same fixed bound as reads. / 在与读取相同的固定上限下编码对象。
     * @param node original object / 原对象
     * @return UTF-8 JSON text / UTF-8 JSON文本
     * @throws SQLException if encoding fails or exceeds bounds / 编码失败或超限时 */
    static String encode(ObjectNode node) throws SQLException {
        return encode(node, LIMIT);
    }

    /** Uses the concrete format's declared byte bound. / 使用具体格式声明的字节上限。
     * @param node original document value / 原文档值
     * @param limit declared UTF-8 byte bound / 声明的UTF-8字节上限
     * @return validated document value / 已校验文档值
     * @throws SQLException if validation or encoding fails / 校验或编码失败时 */
    static String encode(ObjectNode node, int limit) throws SQLException {
        try {
            return text(JSON.writeValueAsBytes(node), limit);
        } catch (IOException invalid) {
            throw new SQLException("Business document cannot be encoded", invalid);
        }
    }

    /** Requires a JSON array under a concrete bound. / 要求具体上限内的JSON数组。
     * @param node source object / 来源对象
     * @param field exact field / 精确字段
     * @param limit maximum entries / 最大条目数
     * @return original array / 原数组
     * @throws SQLException if shape or size differs / 形状或大小不符时 */
    static ArrayNode array(JsonNode node, String field, int limit) throws SQLException {
        if (!(node.path(field) instanceof ArrayNode array) || array.size() > limit)
            throw new SQLException("Business array is invalid");
        return array;
    }

    /** Requires exact fields without implicit defaults or coercion. / 要求精确字段，不使用隐式默认或类型强转。
     * @param node source object / 来源对象
     * @param expected exact field names / 精确字段名
     * @throws SQLException if fields differ / 字段不符时 */
    static void fields(JsonNode node, Set<String> expected) throws SQLException {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected))
            throw new SQLException("Business fields are invalid");
    }

    /** Reads a required text value without JSON coercion. / 读取必需文本，不进行JSON类型强转。
     * @param node source object / 来源对象
     * @param field exact field / 精确字段
     * @return original string / 原字符串
     * @throws SQLException if absent or not textual / 缺失或非文本时 */
    static String string(JsonNode node, String field) throws SQLException {
        if (!node.path(field).isTextual())
            throw new SQLException("Business text is absent");
        return node.path(field).textValue();
    }

    /** Reads a canonical original UUID. / 读取规范原UUID。
     * @param node source object / 来源对象
     * @param field exact field / 精确字段
     * @return original identity / 原身份
     * @throws SQLException if not canonical / 非规范值时 */
    static UUID uuid(JsonNode node, String field) throws SQLException {
        String value = string(node, field);
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value))
                throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Business UUID is invalid", invalid);
        }
    }

}
