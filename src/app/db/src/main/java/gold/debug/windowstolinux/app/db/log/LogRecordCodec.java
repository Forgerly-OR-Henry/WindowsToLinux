package gold.debug.windowstolinux.app.db.log;

import gold.debug.windowstolinux.appapi.task.LogEvent;
import gold.debug.windowstolinux.appapi.task.LogLevel;
import gold.debug.windowstolinux.appapi.task.Outcome;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One bounded redacted record format shared by SQL and emergency storage. / SQL 与应急存储共用的有界脱敏记录格式。 */
public final class LogRecordCodec {
    /** Maximum complete UTF-8 envelope including LF. / 含 LF 的完整 UTF-8 封套上限。 */
    public static final int MAX_BYTES = 16 * 1024;

    /** Strict object parser without duplicate keys or trailing documents. / 拒绝重复键及尾随文档的严格对象解析器。 */
    private static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Mandatory version-one record fields. / 第一版记录必需字段。 */
    private static final Set<String> FIELDS = Set.of("log_id", "recorded_at", "owner_id", "category", "level",
        "event_code", "message", "details", "subject_mod_id", "mod_version", "task_id", "operation_id", "outcome",
        "truncated");

    /** Prevents utility construction. / 禁止构造工具实例。 */
    private LogRecordCodec() {
    }

    /** Validates attribution and bounds redacted text before either storage path. / 任一存储路径前校验归属并限制脱敏文字。
     * @param target original registered target / 原登记目标
     * @param version actual producer release / 实际生产方发布版本
     * @param event diagnostic supplied by its owner / 所有者提供的诊断
     * @return immutable record with its original identifier and timestamp / 带原标识及时间的不可变记录 */
    public static Record create(LogRepository.Target target, String version, LogEvent event) {
        return createSummary(target, version, event, UUID.randomUUID(), System.currentTimeMillis(),
            target.owner().equals("app") ? null : target.owner());
    }

    /** Bounds a host summary using its already persisted identity, timestamp and subject. / 按已持久身份、时间及主体限制宿主摘要。
     * @param target original category / 原类别
     * @param version original release / 原发布
     * @param event original safe event / 原安全事件
     * @param id original fixed summary identity / 原固定摘要身份
     * @param recordedAt original recorded time / 原记录时间
     * @param subject associated Mod or null for platform work / 关联 Mod，平台工作可为空
     * @return bounded exact summary / 有界精确摘要 */
    public static Record createSummary(LogRepository.Target target, String version, LogEvent event, UUID id,
                                       long recordedAt, String subject) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(id);
        if (recordedAt < 0 || subject != null && (subject.isBlank() || subject.length() > 512))
            throw new IllegalArgumentException("Invalid summary attribution");
        if (!event.eventCode().matches("[A-Za-z0-9_.:-]{1,128}") || version == null || version.length() > 512
            || version.isBlank() || event.outcome().orElse(null) == Outcome.UNKNOWN)
            throw new IllegalArgumentException("Invalid diagnostic identity or terminal outcome");
        String message = redact(event.message()), details = event.details().map(LogRecordCodec::redact).orElse(null);
        boolean truncated = event.message().length() > 65536
            || event.details().map(s -> s.length() > 65536).orElse(false);
        String limited = utf8Prefix(message, 2048);
        truncated |= !limited.equals(message);
        if (limited.isBlank())
            throw new IllegalArgumentException("Diagnostic message is empty");
        var record = new Record(id, recordedAt, target.owner(), target.category(), event.level(), event.eventCode(),
            limited, details, subject, version, event.taskId().orElse(null), event.operationId().orElse(null),
            event.outcome().orElse(null), truncated);
        if (encode(target.key(), record).length <= MAX_BYTES)
            return record;
        int low = 0, high = details == null ? 0 : details.codePointCount(0, details.length());
        var empty = record.withDetails(details == null ? null : "", true);
        if (encode(target.key(), empty).length > MAX_BYTES)
            throw new IllegalArgumentException("Diagnostic identities exceed frame limit");
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (encode(target.key(), record.withDetails(details.substring(0, details.offsetByCodePoints(0, middle)),
                true)).length <= MAX_BYTES)
                low = middle;
            else
                high = middle - 1;
        }
        return record.withDetails(details == null ? null : details.substring(0, details.offsetByCodePoints(0, low)),
            true);
    }

    /** Removes common secret forms before any diagnostic persistence or display. / 任一诊断持久化或显示前移除常见秘密形式。
     * @param text author-selected non-secret excerpt / 作者选取的不含秘密片段
     * @return bounded sanitized excerpt / 有界脱敏片段 */
    public static String redact(String text) {
        Objects.requireNonNull(text);
        String value = text.substring(0, Math.min(text.length(), 65536));
        if (!value.isEmpty() && Character.isHighSurrogate(value.charAt(value.length() - 1)))
            value = value.substring(0, value.length() - 1);
        value = value
            .replaceAll("(?is)-----BEGIN[^-]*PRIVATE KEY-----.*?(?:-----END[^-]*PRIVATE KEY-----|$)", "[redacted]")
            .replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer [redacted]").replaceAll(
                "(?i)([\"']?(?:password|passwd|secret|token|api[-_ ]?key|authorization|密码|密钥)[\"']?\\s*[:=：]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
                "$1[redacted]");
        if (value.contains("://"))
            value = value.replaceAll("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+", "[redacted-uri]");
        return value.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    }

    /** Finds a complete Unicode prefix fitting the UTF-8 byte limit. / 找到满足 UTF-8 字节上限的完整 Unicode 前缀。
     * @param text validated text / 已校验文字
     * @param limit byte limit / 字节上限
     * @return character-aligned prefix / 字符边界前缀 */
    private static String utf8Prefix(String text, int limit) {
        int used = 0, offset = 0;
        while (offset < text.length()) {
            int cp = text.codePointAt(offset), size = cp <= 127 ? 1 : cp <= 2047 ? 2 : cp <= 65535 ? 3 : 4;
            if (used + size > limit)
                break;
            used += size;
            offset += Character.charCount(cp);
        }
        return text.substring(0, offset);
    }

    /** Encodes the same complete canonical record for both storage paths. / 为两条存储路径编码同一完整规范记录。
     * @param target original registration key / 原登记键
     * @param record immutable bounded record / 不可变有界记录
     * @return UTF-8 JSON with LF / 带 LF 的 UTF-8 JSON */
    public static byte[] encode(UUID target, Record record) {
        var root = JSON.createObjectNode().put("format_version", 1).put("target_key", target.toString());
        var body = root.putObject("record");
        body.put("log_id", record.id().toString()).put("recorded_at", record.recordedAt())
            .put("owner_id", record.owner()).put("category", record.category()).put("level", record.level().name())
            .put("event_code", record.eventCode()).put("message", record.message()).put("details", record.details())
            .put("subject_mod_id", record.subjectMod()).put("mod_version", record.version())
            .put("task_id", string(record.taskId())).put("operation_id", string(record.operationId()))
            .put("outcome", record.outcome() == null ? null : record.outcome().name())
            .put("truncated", record.truncated());
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(root + "\n"));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Diagnostic text contains invalid Unicode");
        }
    }

    /** Decodes only a complete, correctly attributed, bounded emergency line. / 仅解码完整、归属正确、有界的应急行。
     * @param target expected registered category / 预期登记类别
     * @param bytes one complete line including LF / 包含 LF 的一条完整行
     * @return original diagnostic without new identity or timestamp / 不生成新身份或时间的原诊断
     * @throws IOException if content, fields or attribution is invalid / 内容、字段或归属无效时 */
    public static Record decode(LogRepository.Target target, byte[] bytes) throws IOException {
        try {
            if (bytes.length == 0 || bytes.length > MAX_BYTES || bytes[bytes.length - 1] != '\n')
                throw new IllegalArgumentException();
            String line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            if (line.charAt(0) == '\uFEFF')
                throw new IllegalArgumentException();
            var root = JSON.readTree(line);
            fields(root, Set.of("format_version", "target_key", "record"));
            if (!root.get("format_version").isInt() || root.get("format_version").intValue() != 1
                || !target.key().equals(uuid(root, "target_key", false)))
                throw new IllegalArgumentException();
            var n = root.get("record");
            fields(n, FIELDS);
            if (!n.get("recorded_at").isIntegralNumber() || !n.get("recorded_at").canConvertToLong()
                || n.get("recorded_at").longValue() < 0 || !n.get("truncated").isBoolean())
                throw new IllegalArgumentException();
            var outcome = text(n, "outcome", true);
            var record = new Record(uuid(n, "log_id", false), n.get("recorded_at").longValue(),
                text(n, "owner_id", false), text(n, "category", false), LogLevel.valueOf(text(n, "level", false)),
                text(n, "event_code", false), text(n, "message", false), text(n, "details", true),
                text(n, "subject_mod_id", true), text(n, "mod_version", true), uuid(n, "task_id", true),
                uuid(n, "operation_id", true), outcome == null ? null : Outcome.valueOf(outcome),
                n.get("truncated").booleanValue());
            if (!target.owner().equals(record.owner()) || !target.category().equals(record.category())
                || !record.eventCode().matches("[A-Za-z0-9_.:-]{1,128}") || record.outcome() == Outcome.UNKNOWN
                || record.message().isBlank() || record.message().getBytes(StandardCharsets.UTF_8).length > 2048
                || !target.owner().equals("app")
                && (!Objects.equals(record.subjectMod(), target.owner()) || record.version() == null)
                || record.subjectMod() != null
                && (record.subjectMod().isBlank() || record.subjectMod().length() > 512)
                || record.version() != null && (record.version().isBlank() || record.version().length() > 512)
                || !record.message().equals(redact(record.message()))
                || record.details() != null && !record.details().equals(redact(record.details()))
                || encode(target.key(), record).length > MAX_BYTES)
                throw new IllegalArgumentException();
            return record;
        } catch (RuntimeException | IOException invalid) {
            throw new IOException("Emergency diagnostic format or ownership is invalid");
        }
    }

    /** Requires the exact versioned object shape. / 要求精确版本化对象形状。
     * @param node parsed object / 已解析对象
     * @param required exact field set / 精确字段集合 */
    private static void fields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException();
        var found = new HashSet<String>();
        node.fieldNames().forEachRemaining(found::add);
        if (!required.equals(found))
            throw new IllegalArgumentException();
    }

    /** Reads nullable text without coercion. / 不强制转换地读取可空文本。
     * @param node source object / 来源对象
     * @param name field name / 字段名
     * @param nullable permitted null / 是否允许空值
     * @return exact text / 精确文本 */
    private static String text(JsonNode node, String name, boolean nullable) {
        var value = node.get(name);
        if (nullable && value.isNull())
            return null;
        if (!value.isTextual())
            throw new IllegalArgumentException();
        return value.textValue();
    }

    /** Reads only canonical UUIDs. / 仅读取规范 UUID。
     * @param node source object / 来源对象
     * @param name field name / 字段名
     * @param nullable permitted null / 是否允许空值
     * @return exact optional identity / 精确可空身份 */
    private static UUID uuid(JsonNode node, String name, boolean nullable) {
        String value = text(node, name, nullable);
        if (value == null)
            return null;
        var id = UUID.fromString(value);
        if (!id.toString().equals(value))
            throw new IllegalArgumentException();
        return id;
    }

    /** Formats optional identifiers without a textual null value. / 格式化可空标识，不生成文字 null。
     * @param id optional identity / 可空身份
     * @return optional canonical text / 可空规范文本 */
    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }

    /** Immutable shared diagnostic data; only message and details may be truncated. / 不可变共用诊断数据；仅消息及正文可以截断。
     * @param id stable log identifier / 稳定日志标识
     * @param recordedAt first reception UTC milliseconds / 首次接收 UTC 毫秒
     * @param owner trusted producer owner / 可信生产方所有者
     * @param category registered category / 登记类别
     * @param level severity / 严重度
     * @param eventCode stable event code / 稳定事件码
     * @param message bounded redacted summary / 有界脱敏摘要
     * @param details bounded redacted detail / 有界脱敏正文
     * @param subjectMod associated Mod snapshot / 关联 Mod 快照
     * @param version actual release snapshot / 实际发布快照
     * @param taskId associated task / 关联任务
     * @param operationId associated operation / 关联操作
     * @param outcome optional known terminal outcome / 可空已知终态
     * @param truncated incomplete diagnostic flag / 诊断不完整标记 */
    public record Record(UUID id, long recordedAt, String owner, String category, LogLevel level, String eventCode,
                         String message, String details, String subjectMod, String version, UUID taskId,
                         UUID operationId,
                         Outcome outcome, boolean truncated) {
        /** Copies only the bounded body and truncation marker. / 仅复制有界正文及截断标记。
         * @param value replacement detail / 替换正文
         * @param shortened truncation state / 截断状态
         * @return same identity and attribution / 相同身份及归属 */
        public Record withDetails(String value, boolean shortened) {
            return new Record(id, recordedAt, owner, category, level, eventCode, message, value, subjectMod, version,
                taskId, operationId, outcome, shortened);
        }
    }
}
