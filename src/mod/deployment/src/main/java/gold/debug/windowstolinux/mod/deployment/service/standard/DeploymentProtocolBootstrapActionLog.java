package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;

/** Original-task identity and effect phase of the shared helper bootstrap. / 共用 helper 首次安装的原任务身份与效果阶段。 */
public final class DeploymentProtocolBootstrapActionLog {
    /** One installation per original publication task. / 每项原发布任务至多一项安装。 */
    public static final String KEY = "deployment-helper-bootstrap";

    /** Strict bounded JSON codec. / 严格有界 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Exact persisted fields. / 精确持久字段。 */
    private static final Set<String> FIELDS = Set.of("formatVersion", "taskId", "serverId", "operationId",
        "serverDigest", "bodyDigest", "requestDigest", "helperDigest", "phase");

    /** Original checkpoint. / 原检查点。 */
    private final TaskCheckpoint checkpoint;

    /** Original task identity. / 原任务身份。 */
    private final UUID taskId;

    /** Immutable non-secret bootstrap evidence. / 不可变非秘密安装依据。
     * @param serverId exact admitted server / 精确已准入服务器
     * @param operationId original installation UUID / 原安装 UUID
     * @param serverDigest digest of exact server identity / 精确服务器身份摘要
     * @param bodyDigest digest of unchanged installer body / 未修改安装主体摘要
     * @param requestDigest digest of complete reviewed command input / 完整审核命令输入摘要
     * @param helperDigest expected managed helper digest / 预期受管 helper 摘要
     * @param phase original installation phase / 原安装阶段 */
    public record Facts(String serverId, UUID operationId, String serverDigest, String bodyDigest, String requestDigest,
                        String helperDigest, String phase) {
        /** Validates the complete original identity. / 校验完整原身份。
         * @param serverId exact admitted server / 精确已准入服务器
         * @param operationId original installation UUID / 原安装 UUID
         * @param serverDigest exact server digest / 精确服务器摘要
         * @param bodyDigest installer body digest / 安装主体摘要
         * @param requestDigest complete request digest / 完整请求摘要
         * @param helperDigest expected helper digest / 预期 helper 摘要
         * @param phase original phase / 原阶段 */
        public Facts {
            if (serverId == null || serverId.isBlank() || serverId.length() > 512
                || serverId.chars().anyMatch(value -> value < 32) || operationId == null || !digest(serverDigest)
                || !digest(bodyDigest) || !digest(requestDigest)
                || !DeploymentManagedHelperBundle.EXPECTED_SHA256.equals(helperDigest)
                || !Set.of("INTENT", "DISPATCHED", "SUCCEEDED", "FAILED").contains(phase))
                throw new IllegalArgumentException("Invalid original helper installation identity");
        }
    }

    /** Wraps a retained original checkpoint. / 包装留存的原检查点。
     * @param checkpoint original checkpoint / 原检查点
     * @param taskId original task / 原任务 */
    private DeploymentProtocolBootstrapActionLog(TaskCheckpoint checkpoint, UUID taskId) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.taskId = Objects.requireNonNull(taskId);
    }

    /** Saves complete installer identity before any remote effect. / 远端副作用前保存完整安装身份。
     * @param tasks original checkpoint service / 原检查点服务
     * @param task admitted task / 已准入任务
     * @param server admitted server / 已准入服务器
     * @param operationId fixed installation UUID / 固定安装 UUID
     * @param serverDigest exact server digest / 精确服务器摘要
     * @param bodyDigest unchanged installer digest / 未修改安装程序摘要
     * @param requestDigest full command input digest / 完整命令输入摘要
     * @return original installation journal / 原安装日志
     * @throws ApiException if persistence fails / 持久化失败时 */
    public static DeploymentProtocolBootstrapActionLog begin(TaskService tasks, TaskContext task, ServerId server,
                                                             UUID operationId, String serverDigest, String bodyDigest, String requestDigest) throws ApiException {
        var facts = new Facts(server.id(), operationId, serverDigest, bodyDigest, requestDigest,
            DeploymentManagedHelperBundle.EXPECTED_SHA256, "INTENT");
        return new DeploymentProtocolBootstrapActionLog(tasks.beginCheckpoint(task, KEY, encode(task.id(), facts)),
            task.id());
    }

    /** Finds only the original task's retained installation evidence. / 仅读取原任务留存的安装依据。
     * @param tasks original checkpoint service / 原检查点服务
     * @param task original task / 原任务
     * @return retained journal or absence / 留存日志或不存在
     * @throws ApiException if the original row cannot be read / 原行无法读取时 */
    public static Optional<DeploymentProtocolBootstrapActionLog> find(TaskService tasks, TaskContext task)
        throws ApiException {
        var found = tasks.findCheckpoint(task, KEY);
        if (found.isEmpty())
            return Optional.empty();
        var journal = new DeploymentProtocolBootstrapActionLog(found.orElseThrow(), task.id());
        journal.facts();
        return Optional.of(journal);
    }

    /** Reads exact original persisted facts. / 读取精确原持久事实。
     * @return retained facts / 留存事实
     * @throws ApiException if the checkpoint cannot be read / 检查点无法读取时 */
    public Facts facts() throws ApiException {
        return decode(checkpoint.snapshot().payload(), taskId);
    }

    /** Marks a possibly sent installation immediately before remote execute. / 远端执行紧前标记可能已发送的安装。
     * @throws ApiException if the original comparison fails / 原比较更新失败时 */
    public void dispatched() throws ApiException {
        advance("INTENT", "DISPATCHED");
    }

    /** Persists only an exact known terminal outcome. / 仅持久保存精确已知的终态。
     * @param success whether the original install succeeded / 原安装是否成功
     * @throws ApiException if the original comparison fails / 原比较更新失败时 */
    public void terminal(boolean success) throws ApiException {
        advance("DISPATCHED", success ? "SUCCEEDED" : "FAILED");
    }

    /** Releases only proven unsent or terminal installation occupancy. / 仅释放确知未发送或已终结的安装占用。
     * @throws ApiException if release fails / 释放失败时 */
    public void close() throws ApiException {
        if (checkpoint.snapshot().released())
            return;
        if ("DISPATCHED".equals(facts().phase()))
            throw new IllegalArgumentException("Original helper installation remains uncertain");
        checkpoint.close();
    }

    /** Advances one expected original phase. / 推进一项预期原阶段。
     * @param expected exact prior phase / 精确先前阶段
     * @param next next phase / 下一阶段
     * @throws ApiException if original CAS fails / 原比较更新失败时 */
    private void advance(String expected, String next) throws ApiException {
        var before = checkpoint.snapshot();
        var facts = decode(before.payload(), taskId);
        if (!expected.equals(facts.phase()))
            throw new IllegalArgumentException("Original helper installation phase differs");
        checkpoint.advance(before, encode(taskId, new Facts(facts.serverId(), facts.operationId(), facts.serverDigest(),
            facts.bodyDigest(), facts.requestDigest(), facts.helperDigest(), next)));
    }

    /** Encodes bounded original facts. / 编码有界原事实。
     * @param taskId original task / 原任务
     * @param facts non-secret facts / 非秘密事实
     * @return checkpoint payload / 检查点载荷 */
    private static String encode(UUID taskId, Facts facts) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("formatVersion", 1);
        fields.put("taskId", taskId.toString());
        fields.put("serverId", facts.serverId());
        fields.put("operationId", facts.operationId().toString());
        fields.put("serverDigest", facts.serverDigest());
        fields.put("bodyDigest", facts.bodyDigest());
        fields.put("requestDigest", facts.requestDigest());
        fields.put("helperDigest", facts.helperDigest());
        fields.put("phase", facts.phase());
        try {
            String value = JSON.writeValueAsString(fields);
            if (value.getBytes(StandardCharsets.UTF_8).length > 12_000)
                throw new IllegalArgumentException("Original helper installation checkpoint too large");
            return value;
        } catch (IOException impossible) {
            throw new IllegalStateException("Original helper installation encoding failed", impossible);
        }
    }

    /** Decodes only the original task and fixed schema. / 仅解码原任务及固定模式。
     * @param payload stored JSON / 已存 JSON
     * @param taskId original task / 原任务
     * @return exact non-secret facts / 精确非秘密事实 */
    private static Facts decode(String payload, UUID taskId) {
        try {
            var node = JSON.readTree(payload);
            if (node == null || !node.isObject() || node.size() != FIELDS.size()
                || !node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                || node.path("formatVersion").asInt() != 1
                || !taskId.toString().equals(node.path("taskId").textValue()))
                throw new IllegalArgumentException("Invalid original helper installation checkpoint");
            var names = new java.util.HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(FIELDS))
                throw new IllegalArgumentException("Unknown original helper installation checkpoint field");
            return new Facts(node.path("serverId").textValue(), UUID.fromString(node.path("operationId").textValue()),
                node.path("serverDigest").textValue(), node.path("bodyDigest").textValue(),
                node.path("requestDigest").textValue(), node.path("helperDigest").textValue(),
                node.path("phase").textValue());
        } catch (IOException | NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid original helper installation checkpoint", invalid);
        }
    }

    /** Checks one lowercase SHA-256 field. / 检查小写 SHA-256 字段。
     * @param value candidate digest / 候选摘要
     * @return whether it is exact / 是否精确 */
    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
