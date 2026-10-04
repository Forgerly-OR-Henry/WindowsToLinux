package gold.debug.windowstolinux.app.mod.process;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import gold.debug.windowstolinux.appapi.task.process.ProcessMessage;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Strict version-one auxiliary-process frames, independent of task admission. / 独立于任务准入的严格第一版辅助进程帧。 */
public final class ProcessProtocol {
    /** Maximum encoded frame including LF. / 包含 LF 的编码帧上限。 */
    public static final int MAX_BYTES = 1024 * 1024;

    /** Bounded parser rejecting ambiguous objects and trailing documents. / 拒绝歧义对象及尾随文档的有界解析器。 */
    private static final ObjectMapper JSON = JsonMapper
        .builder(JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Exact envelope fields. / 精确封套字段。 */
    private static final Set<String> FIELDS = Set.of("protocolVersion", "processId", "taskId", "requestId", "kind",
        "type", "payload");

    /** Prevents utility instances. / 禁止工具实例。 */
    private ProcessProtocol() {
    }

    /** Supported protocol directions and control messages. / 受支持协议方向及控制消息。 */
    public enum Kind {
        /** Platform introduction. / 平台介绍。 */
        HELLO,
        /** Child readiness. / 子进程就绪。 */
        READY,
        /** One business request. / 一次业务请求。 */
        REQUEST,
        /** Correlated business result. / 关联业务结果。 */
        RESULT,
        /** Correlated progress observation. / 关联进度观察。 */
        EVENT,
        /** Cooperative request cancellation. / 协作请求取消。 */
        CANCEL,
        /** Seal admission and stop. / 封闭准入并停止。 */
        STOP,
        /** Child stop acknowledgement, not exit proof. / 子进程停止确认，不是退出证明。 */
        STOPPED,
        /** Correlated failed or unknown result. / 关联失败或未知结果。 */
        ERROR
    }

    /** Validated immutable envelope retaining canonical JSON text. / 保留规范 JSON 文本的已校验不可变封套。
     * @param process original process ID / 原进程 ID
     * @param task original task ID / 原任务 ID
     * @param request exact correlation ID / 精确关联 ID
     * @param kind message kind / 消息种类
     * @param type owner-local type / 所有者内部类型
     * @param payload bounded JSON object / 有界 JSON 对象 */
    public record Frame(UUID process, UUID task, UUID request, Kind kind, String type, String payload) {
        /** Validates all identities and payload before transmission. / 发送前校验所有身份和载荷。
         * @param process original process / 原进程
         * @param task original task / 原任务
         * @param request correlation identity / 关联身份
         * @param kind protocol kind / 协议种类
         * @param type owner-local type / 所有者内部类型
         * @param payload object text / 对象文本 */
        public Frame {
            Objects.requireNonNull(process);
            Objects.requireNonNull(task);
            Objects.requireNonNull(request);
            Objects.requireNonNull(kind);
            if (type == null || type.isBlank() || type.length() > 256
                || type.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid process message type");
            try {
                payload = object(payload).toString();
            } catch (IOException failure) {
                throw new IllegalArgumentException("Invalid process payload");
            }
        }
    }

    /** Reads one complete frame with bounded allocation, preserving EOF distinction. / 有界分配读取一条完整帧，区分 EOF。
     * @param input original stdout / 原 stdout
     * @return frame, or empty only for EOF between frames / 帧，仅在帧间 EOF 时为空
     * @throws IOException if framing, encoding or fields are invalid / 帧、编码或字段无效时 */
    public static Optional<Frame> read(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (bytes.size() == MAX_BYTES)
                throw new IOException("Process frame exceeds byte limit");
            bytes.write(value);
            if (value == '\n')
                return Optional.of(decode(bytes.toByteArray()));
        }
        if (bytes.size() != 0)
            throw new IOException("Process frame ended before LF");
        return Optional.empty();
    }

    /** Decodes only the exact versioned UTF-8 envelope. / 仅解码精确版本化 UTF-8 封套。
     * @param bytes complete frame including LF / 包含 LF 的完整帧
     * @return validated immutable frame / 已校验不可变帧
     * @throws IOException if content is ambiguous or invalid / 内容歧义或无效时 */
    public static Frame decode(byte[] bytes) throws IOException {
        try {
            if (bytes.length == 0 || bytes.length > MAX_BYTES || bytes[bytes.length - 1] != '\n')
                throw new IllegalArgumentException();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.charAt(0) == '\uFEFF')
                throw new IllegalArgumentException();
            var node = object(text);
            fields(node, FIELDS);
            if (!node.get("protocolVersion").isInt() || node.get("protocolVersion").intValue() != 1
                || !node.get("payload").isObject())
                throw new IllegalArgumentException();
            return new Frame(uuid(node, "processId"), uuid(node, "taskId"), uuid(node, "requestId"),
                Kind.valueOf(text(node, "kind")), text(node, "type"), node.get("payload").toString());
        } catch (RuntimeException | IOException invalid) {
            throw new IOException("Invalid process protocol frame");
        }
    }

    /** Encodes and checks the complete frame before it enters the writer queue. / 进入写入队列前编码并检查完整帧。
     * @param frame validated envelope / 已校验封套
     * @return complete bounded bytes / 完整有界字节
     * @throws IOException if the wire representation exceeds its limit / 线上表示超限时 */
    public static byte[] encode(Frame frame) throws IOException {
        var node = JSON.createObjectNode().put("protocolVersion", 1).put("processId", frame.process().toString())
            .put("taskId", frame.task().toString()).put("requestId", frame.request().toString())
            .put("kind", frame.kind().name()).put("type", frame.type());
        node.set("payload", object(frame.payload()));
        var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(node + "\n"));
        if (encoded.remaining() > MAX_BYTES)
            throw new IOException("Process frame exceeds byte limit");
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    /** Compares duplicate response content independently of JSON object field order. / 独立于 JSON 对象字段顺序比较重复响应内容。
     * @param first original response / 原响应
     * @param second repeated response / 重复响应
     * @return whether the envelope and object contents match / 封套及对象内容是否匹配
     * @throws IOException if either object is invalid / 任一对象无效时 */
    public static boolean equivalent(Frame first, Frame second) throws IOException {
        return first.process().equals(second.process()) && first.task().equals(second.task())
            && first.request().equals(second.request()) && first.kind() == second.kind()
            && first.type().equals(second.type()) && object(first.payload()).equals(object(second.payload()));
    }

    /** Decodes a result with the same invariants as the public Java result. / 按公开 Java 结果相同不变量解码结果。
     * @param frame correlated RESULT or ERROR / 关联 RESULT 或 ERROR
     * @return validated business result without interpreting it as resource release / 已校验业务结果，不将其解释为资源释放
     * @throws IOException if result fields or constraints are invalid / 结果字段或约束无效时 */
    public static OperationResult<ProcessMessage> result(Frame frame) throws IOException {
        try {
            if (frame.kind() != Kind.RESULT && frame.kind() != Kind.ERROR)
                throw new IllegalArgumentException();
            var node = object(frame.payload());
            fields(node, Set.of("outcome", "value", "problem"));
            TaskResult outcome = TaskResult.valueOf(text(node, "outcome"));
            if (frame.kind() == Kind.ERROR && outcome != TaskResult.FAILED && outcome != TaskResult.UNKNOWN)
                throw new IllegalArgumentException();
            var value = node.get("value");
            if (!value.isObject() && !value.isNull())
                throw new IllegalArgumentException();
            Optional<Problem> problem = node.get("problem").isNull()
                ? Optional.empty()
                : Optional.of(problem(node.get("problem")));
            return new OperationResult<>(outcome,
                value.isNull() ? Optional.empty() : Optional.of(new ProcessMessage(frame.type(), value.toString())),
                problem);
        } catch (RuntimeException | IOException invalid) {
            throw new IOException("Invalid process result contract");
        }
    }

    /** Parses a bounded object with no duplicate fields at any depth. / 解析有界对象，所有深度均不允许重复字段。
     * @param value JSON object text / JSON 对象文本
     * @return independently parsed object / 独立解析对象
     * @throws IOException if parsing or shape validation fails / 解析或形状校验失败时 */
    private static JsonNode object(String value) throws IOException {
        if (value == null || value.length() > MAX_BYTES)
            throw new IOException("Invalid process object length");
        var node = JSON.readTree(value);
        if (node == null || !node.isObject())
            throw new IOException("Process payload must be an object");
        return node;
    }

    /** Verifies an exact versioned shape without optional unknown fields. / 核验精确版本化形状，不接受可选未知字段。
     * @param node object / 对象
     * @param expected exact keys / 精确键 */
    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || node.size() != expected.size())
            throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(key -> {
            if (!expected.contains(key))
                throw new IllegalArgumentException();
        });
    }

    /** Requires a genuine string without coercion. / 要求真实字符串，不强制转换。
     * @param node object / 对象
     * @param key field / 字段
     * @return original string / 原字符串 */
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual())
            throw new IllegalArgumentException();
        return node.get(key).textValue();
    }

    /** Requires a canonical UUID rather than accepting shortened aliases. / 要求规范 UUID，不接受缩短别名。
     * @param node object / 对象
     * @param key field / 字段
     * @return original UUID / 原 UUID */
    private static UUID uuid(JsonNode node, String key) {
        String value = text(node, key);
        var id = UUID.fromString(value);
        if (!id.toString().equals(value))
            throw new IllegalArgumentException();
        return id;
    }

    /** Validates the public structured problem without including parser errors. / 校验公开结构化问题，不包含解析异常。
     * @param node problem object / 问题对象
     * @return structured problem / 结构化问题 */
    private static Problem problem(JsonNode node) {
        fields(node, Set.of("code", "eventCode", "message", "context", "diagnosticId"));
        if (!node.get("context").isObject())
            throw new IllegalArgumentException();
        var context = new TreeMap<String, String>();
        node.get("context").fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual())
                throw new IllegalArgumentException();
            context.put(entry.getKey(), entry.getValue().textValue());
        });
        return new Problem(ProblemCode.valueOf(text(node, "code")), text(node, "eventCode"), text(node, "message"),
            context,
            node.get("diagnosticId").isNull() ? Optional.empty() : Optional.of(uuid(node, "diagnosticId")));
    }
}
