package gold.debug.windowstolinux.mod.deployment.linux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.ActionTarget;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.InteractionService;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/** Bounded native DB helper framing and exact reviewed remote transport. / 有界原生数据库 helper 帧及精确审核远端传输。 */
public final class DeploymentNativeDatabaseWire {
    /** Wipes the expandable frame buffer after returning its independent final copy. / 返回独立最终副本后清零可扩展帧缓冲。 */
    private static final class WipingFrame extends ByteArrayOutputStream {
        /** Creates an owned expandable buffer. / 创建自有可扩展缓冲。 */
        private WipingFrame() {
        }

        /** Reserves the entire sensitive frame before any value is written. / 写入任何敏感值前预留完整帧容量。
         * @param capacity exact bounded frame length / 精确有界帧长度 */
        private WipingFrame(int capacity) {
            super(capacity);
        }

        /** Clears only this temporary expandable array. / 仅清零此临时可扩展数组。 */
        void wipe() {
            Arrays.fill(buf, (byte) 0);
        }
    }

    /** Original session. / 原会话。 */
    private final RemoteSession session;

    /** Host review. / 宿主审核。 */
    private final InteractionService interaction;

    /** Original admitted server. / 原已准入服务器。 */
    private final ServerRef server;

    /** Original task. / 原任务。 */
    private final TaskContext task;

    /** Action immediately before transport; the caller persists its own original journal. / 传输紧前动作；调用方持久保存自己的原日志。 */
    @FunctionalInterface
    public interface BeforeExecute {
        /** Runs only after exact approval and before session execution. / 仅在精确批准后、会话执行前运行。
         * @throws ApiException if original persistence fails / 原持久化失败时 */
        void run() throws ApiException;
    }

    /** Binds the original reviewed transport. / 绑定原已审核传输。
     * @param session borrowed remote session / 借用远端会话
     * @param interaction host review / 宿主审核
     * @param server admitted server / 已准入服务器
     * @param task original task / 原任务 */
    public DeploymentNativeDatabaseWire(RemoteSession session, InteractionService interaction, ServerRef server,
                                        TaskContext task) {
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
    }

    /** Sends a fixed helper action after exact approval; status is read-only by helper contract. / 精确批准后发送固定 helper 动作；status 按 helper 契约只读。
     * @param action fixed helper action / 固定 helper 动作
     * @param frame caller-owned complete byte frame / 调用方持有的完整字节帧
     * @param revision exact request digest / 精确请求摘要
     * @param target non-secret reviewed business target / 非秘密已审阅业务目标
     * @param beforeExecute original durable dispatch transition / 原持久发送阶段转换
     * @return exact platform observation or review rejection / 平台精确观察或审核拒绝
     * @throws ApiException if review, dispatch transition or transport fails / 审核、发送阶段转换或传输失败时 */
    public OperationResult<CommandResult> send(String action, byte[] frame, String revision, String target,
                                               BeforeExecute beforeExecute) throws ApiException {
        if (!Set.of("inspect", "install", "start", "resume", "target", "prepare", "initialize", "status")
            .contains(action))
            throw new IllegalArgumentException("Unsupported native database action");
        try (var request = new CommandRequest("'" + DeploymentManagedHelperBundle.PATH + "' native-db " + action, frame,
            "status".equals(action)
                ? Duration.ofSeconds(30)
                : "install".equals(action) ? Duration.ofMinutes(30) : Duration.ofMinutes(10),
            131072)) {
            ActionIntent intent = RemoteActions.command(server, revision, request)
                .withSemantics(new ActionSemantics("deployment.native-db." + action + ".v1",
                    Set.of(new ActionTarget(server.id(), "native-database", target))));
            var grant = interaction.approve(intent, task);
            if (grant.outcome() != Outcome.SUCCEEDED)
                return new OperationResult<>(grant.outcome(), java.util.Optional.empty(), grant.problem());
            if (task.cancelToken().requested())
                return new OperationResult<>(Outcome.CANCELLED, java.util.Optional.empty(),
                    java.util.Optional.of(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_NATIVE_DB_UNSENT",
                        "Native database operation was not sent", Map.of(), java.util.Optional.empty())));
            beforeExecute.run();
            return session.execute(request, grant.value().orElseThrow());
        }
    }

    /** Encodes a bounded ASCII Base64 frame without turning values into one immutable frame String. / 编码有界 ASCII Base64 帧，不把全部值转为不可清零帧字符串。
     * @param values actual ordered request fields / 实际有序请求字段
     * @return caller-owned mutable frame / 调用方持有的可清零帧 */
    public static byte[] frame(Map<String, String> values) {
        var output = new WipingFrame();
        try {
            for (var entry : values.entrySet()) {
                if (!entry.getKey().matches("[A-Za-z0-9.]{1,64}"))
                    throw new IllegalArgumentException("Invalid native database field name");
                output.writeBytes((entry.getKey() + "=").getBytes(StandardCharsets.US_ASCII));
                byte[] plain = entry.getValue().getBytes(StandardCharsets.UTF_8);
                byte[] encoded = Base64.getEncoder().encode(plain);
                try {
                    output.writeBytes(encoded);
                } finally {
                    Arrays.fill(plain, (byte) 0);
                    Arrays.fill(encoded, (byte) 0);
                }
                output.write('\n');
                if (output.size() > 3 * 1024 * 1024)
                    throw new IllegalArgumentException("Native database frame exceeds helper limit");
            }
            return output.toByteArray();
        } finally {
            output.wipe();
        }
    }

    /** Encodes a fixed-size frame containing borrowed sensitive bytes without immutable plaintext. / 以固定大小编码借用敏感字节的帧，不生成不可清零明文。
     * @param fields non-secret text fields / 非秘密文本字段
     * @param sensitive borrowed sensitive byte fields / 借用的敏感字节字段
     * @return caller-owned mutable complete frame / 调用方持有的可清零完整帧 */
    public static byte[] sensitiveFrame(Map<String, String> fields, Map<String, byte[]> sensitive) {
        var bytes = new LinkedHashMap<String, byte[]>();
        var owned = new java.util.ArrayList<byte[]>();
        try {
            for (var field : fields.entrySet()) {
                byte[] value = field.getValue().getBytes(StandardCharsets.UTF_8);
                owned.add(value);
                if (bytes.putIfAbsent(field.getKey(), value) != null)
                    throw new IllegalArgumentException("Duplicate native database frame field");
            }
            for (var field : sensitive.entrySet())
                if (field.getValue() == null || bytes.putIfAbsent(field.getKey(), field.getValue()) != null)
                    throw new IllegalArgumentException("Invalid sensitive native database field");
            long capacity = 0;
            for (var field : bytes.entrySet()) {
                if (!field.getKey().matches("[A-Za-z0-9.]{1,64}"))
                    throw new IllegalArgumentException("Invalid native database frame field");
                capacity += field.getKey().length() + 2L + 4L * ((field.getValue().length + 2L) / 3L);
            }
            if (capacity > 3L * 1024L * 1024L)
                throw new IllegalArgumentException("Sensitive native database frame exceeds helper limit");
            var output = new WipingFrame((int) capacity);
            try {
                for (var field : bytes.entrySet()) {
                    output.writeBytes((field.getKey() + "=").getBytes(StandardCharsets.US_ASCII));
                    byte[] encoded = Base64.getEncoder().encode(field.getValue());
                    try {
                        output.writeBytes(encoded);
                    } finally {
                        Arrays.fill(encoded, (byte) 0);
                    }
                    output.write('\n');
                }
                if (output.size() != capacity)
                    throw new IllegalStateException("Native database frame size changed");
                return output.toByteArray();
            } finally {
                output.wipe();
            }
        } finally {
            owned.forEach(value -> Arrays.fill(value, (byte) 0));
        }
    }

    /** Parses bounded duplicate-free helper output. / 解析有界且无重复键的 helper 输出。
     * @param output helper stdout / helper 标准输出
     * @return exact decoded fields / 精确解码字段 */
    public static Map<String, String> fields(String output) {
        if (output.length() > 131072)
            throw new IllegalArgumentException("Native database receipt too large");
        var values = new LinkedHashMap<String, String>();
        for (String line : output.split("\n", -1)) {
            if (line.isEmpty())
                continue;
            int equals = line.indexOf('=');
            if (equals < 1 || !line.substring(0, equals).matches("[A-Za-z0-9.]{1,64}"))
                throw new IllegalArgumentException("Invalid native database receipt field");
            String key = line.substring(0, equals);
            byte[] decoded = Base64.getDecoder().decode(line.substring(equals + 1));
            try {
                if (values.putIfAbsent(key, new String(decoded, StandardCharsets.UTF_8)) != null)
                    throw new IllegalArgumentException("Duplicate native database receipt field");
            } finally {
                Arrays.fill(decoded, (byte) 0);
            }
        }
        return values;
    }

    /** Computes the exact lowercase SHA-256 of one original byte sequence. / 计算原字节序列的精确小写 SHA-256。
     * @param bytes original bytes / 原字节
     * @return lowercase digest / 小写摘要 */
    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }

    /** Mirrors the fixed helper's action-specific target digest over actual request fields. / 对实际请求字段镜像固定 helper 按动作计算的目标摘要。
     * @param action native mutation / 原生变更动作
     * @param fields actual non-secret request fields / 实际非秘密请求字段
     * @return exact helper target digest / 精确 helper 目标摘要 */
    public static String targetDigest(String action, Map<String, String> fields) {
        String[] required = switch (action) {
            case "install" -> new String[]{"engine", "package", "packageVersion", "engineVersion"};
            case "start", "resume" -> new String[]{"engine", "instanceId", "fingerprint"};
            case "prepare", "initialize" ->
                new String[]{"engine", "instanceId", "fingerprint", "applicationId", "database", "username"};
            default -> throw new IllegalArgumentException("Unsupported native database target action");
        };
        var ordered = new java.util.ArrayList<String>();
        for (String key : required) {
            String value = fields.get(key);
            if (value == null || value.isEmpty() || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Missing native database target field");
            ordered.add(value);
        }
        if ("install".equals(action))
            for (String key : new String[]{"instanceId", "fingerprint", "replacementApproved", "approvedServer"})
                ordered.add(fields.getOrDefault(key, ""));
        if ("initialize".equals(action))
            for (String key : new String[]{"sourceSha256", "existingApproved"})
                ordered.add(fields.getOrDefault(key, ""));
        if (ordered.stream().anyMatch(value -> value.indexOf('\0') >= 0))
            throw new IllegalArgumentException("Invalid native database target field");
        return digest(String.join("\0", ordered).getBytes(StandardCharsets.UTF_8));
    }
}
