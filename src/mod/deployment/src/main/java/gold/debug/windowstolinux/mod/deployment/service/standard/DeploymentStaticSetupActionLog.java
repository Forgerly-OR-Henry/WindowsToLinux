package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.io.IOException;
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

/** Bounded original static preparation identity; native database effects require their separate original receipt. / 有界原静态准备身份；原生数据库效果另须原操作回执核对。 */
public final class DeploymentStaticSetupActionLog {
    /** One owner-local marker separate from publication effect checkpoints. / 与发布效果检查点分开的所有者内部标记。 */
    public static final String KEY = "deployment-static-preparation";

    /** Exact persisted format. / 精确持久格式。 */
    private static final int VERSION = 1;

    /** Strict bounded JSON document. / 严格有界 JSON 文档。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Original explicit task occupancy. / 原显式任务占用。 */
    private final TaskCheckpoint checkpoint;

    /** Original immutable task identity. / 原不可变任务身份。 */
    private final UUID taskId;

    /** Exact selected platform server. / 精确选定的平台服务器。 */
    private final String serverId;

    /** Binds an already authenticated original checkpoint. / 绑定已经鉴权的原检查点。
     * @param checkpoint original handle / 原句柄
     * @param taskId original task ID / 原任务 ID
     * @param serverId exact server ID / 精确服务器 ID */
    private DeploymentStaticSetupActionLog(TaskCheckpoint checkpoint, UUID taskId, String serverId) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.taskId = Objects.requireNonNull(taskId);
        this.serverId = Objects.requireNonNull(serverId);
    }

    /** Persists original preparation intent before user selection or any source access. / 用户选材与任何源码访问之前持久原准备意图。
     * @param tasks original platform tasks / 原平台任务
     * @param task admitted publication task / 已准入发布任务
     * @param server exact selected server / 精确选定服务器
     * @return original held marker / 原占用标记
     * @throws ApiException if persistence cannot be confirmed / 无法确认持久化时 */
    public static DeploymentStaticSetupActionLog begin(TaskService tasks, TaskContext task, ServerId server)
        throws ApiException {
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(task);
        Objects.requireNonNull(server);
        var checkpoint = tasks.beginCheckpoint(task, KEY, encode(task.id(), server.id(), "PREPARING"));
        return new DeploymentStaticSetupActionLog(checkpoint, task.id(), server.id());
    }

    /** Reads only a prior marker; absence does not prove no effect. / 仅读取既有标记；缺失不证明无副作用。
     * @param tasks original platform tasks / 原平台任务
     * @param task original recovery context / 原恢复上下文
     * @return original marker or absence / 原标记或不存在
     * @throws ApiException if original read fails / 原读取失败时 */
    public static Optional<DeploymentStaticSetupActionLog> find(TaskService tasks, TaskContext task)
        throws ApiException {
        var checkpoint = tasks.findCheckpoint(task, KEY);
        if (checkpoint.isEmpty())
            return Optional.empty();
        var fields = decode(checkpoint.orElseThrow().snapshot().payload());
        if (!task.id().equals(UUID.fromString(fields.get("taskId"))))
            throw new IllegalArgumentException("Static preparation task identity differs");
        return Optional.of(
            new DeploymentStaticSetupActionLog(checkpoint.orElseThrow(), task.id(), fields.get("serverId")));
    }

    /** Marks an observed cancellation before local cleanup. / 本机清理前记录已观测取消。
     * @throws ApiException if the original CAS fails / 原比较更新失败时 */
    public void cancelled() throws ApiException {
        var before = checkpoint.snapshot();
        var fields = decode(before.payload());
        if (!fields.get("stage").equals("PREPARING"))
            throw new IllegalArgumentException("Static preparation stage differs");
        checkpoint.advance(before, encode(taskId, serverId, "CANCELLED"));
    }

    /** Reports whether cancellation was durably observed. / 报告取消是否已持久观测。
     * @return true only for original cancelled state / 仅原已取消状态为真
     * @throws ApiException when the original checkpoint cannot be read / 原检查点无法读取时 */
    public boolean wasCancelled() throws ApiException {
        return decode(checkpoint.snapshot().payload()).get("stage").equals("CANCELLED");
    }

    /** Releases only this marker after original local and database-effect cleanup or before formal execution. / 仅在原本机及数据库效果收尾后或正式执行前释放此标记。
     * @throws ApiException if release cannot be confirmed / 释放无法确认时 */
    public void close() throws ApiException {
        checkpoint.close();
    }

    /** Validates the original non-secret marker without accepting extensions. / 校验原非秘密标记，不接受扩展字段。
     * @param payload original JSON / 原 JSON
     * @return exact fields / 精确字段 */
    private static Map<String, String> decode(String payload) {
        try {
            var node = JSON.readTree(payload);
            if (node == null || !node.isObject() || node.size() != 4 || !node.path("formatVersion").isIntegralNumber()
                || !node.path("formatVersion").canConvertToInt() || node.path("formatVersion").asInt() != VERSION)
                throw new IllegalArgumentException("Invalid static preparation marker");
            var names = new java.util.HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(Set.of("formatVersion", "taskId", "serverId", "stage")))
                throw new IllegalArgumentException("Unknown static preparation marker field");
            var taskId = node.path("taskId").textValue();
            var serverId = node.path("serverId").textValue();
            var stage = node.path("stage").textValue();
            UUID.fromString(taskId);
            if (serverId == null || serverId.isBlank() || serverId.length() > 512 || serverId.indexOf('\0') >= 0
                || !Set.of("PREPARING", "CANCELLED").contains(stage))
                throw new IllegalArgumentException("Invalid static preparation identity");
            return Map.of("taskId", taskId, "serverId", serverId, "stage", stage);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid static preparation JSON", invalid);
        }
    }

    /** Encodes only the preparation identity and stage. / 仅编码准备身份与阶段。
     * @param taskId original task / 原任务
     * @param serverId exact server / 精确服务器
     * @param stage preparation state / 准备状态
     * @return bounded JSON object / 有界 JSON 对象 */
    private static String encode(UUID taskId, String serverId, String stage) {
        try {
            return JSON.writeValueAsString(Map.of("formatVersion", VERSION, "taskId", taskId.toString(), "serverId",
                serverId, "stage", stage));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Static marker encoding failed", impossible);
        }
    }
}
