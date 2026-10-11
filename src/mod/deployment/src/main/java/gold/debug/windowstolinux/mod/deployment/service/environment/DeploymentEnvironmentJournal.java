package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Strict original-task CAS evidence; dispatched effects cannot be recreated by recovery. / 严格原任务 CAS 依据，恢复不能重新创建已发送副作用。 */
final class DeploymentEnvironmentJournal {
    /** Owner-local original operation checkpoint. / 所有者内部原操作检查点。 */
    static final String KEY = "deployment.environment.v1";

    /** Strict bounded JSON codec. / 严格有界 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Original task checkpoint. / 原任务检查点。 */
    private final TaskCheckpoint checkpoint;

    /** Original authenticated task identity. / 原已认证任务身份。 */
    private final UUID taskId;
    private final gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout;

    /** Binds an original retained row. / 绑定原保留行。
     * @param checkpoint authentic original row / 真实原行
     * @param taskId original task UUID / 原任务 UUID */
    private DeploymentEnvironmentJournal(TaskCheckpoint checkpoint, UUID taskId,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        this.layout = java.util.Objects.requireNonNull(layout);
        this.checkpoint = checkpoint;
        this.taskId = taskId;
    }

    /** Persists original target before opening any connection. / 打开任何连接前持久保存原目标。
     * @param tasks original task service / 原任务服务
     * @param task authentic task / 真实任务
     * @param target frozen initial target / 冻结初始目标
     * @return retained original journal / 保留原日志
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    static DeploymentEnvironmentJournal begin(TaskService tasks, TaskContext task, RemoteTarget target) throws ApiException {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(tasks, task);
        var state = new DeploymentEnvironmentState(1, target, UUID.randomUUID(), DeploymentEnvironmentScripts.bodyDigest(layout),
            "INTENT", "none", Optional.empty(), layout);
        return new DeploymentEnvironmentJournal(tasks.beginCheckpoint(task, KEY, encode(state)), task.id(), layout);
    }

    /** Finds original evidence without manufacturing a replacement operation. / 查找原依据，不制造替代操作。
     * @param tasks original task service / 原任务服务
     * @param task authentic original recovery / 真实原恢复
     * @return original journal or absence / 原日志或不存在
     * @throws ApiException if original lookup fails / 原查找失败时 */
    static Optional<DeploymentEnvironmentJournal> find(TaskService tasks, TaskContext task) throws ApiException {
        var found = tasks.findCheckpoint(task, KEY);
        if (found.isEmpty()) return Optional.empty();
        var journal = new DeploymentEnvironmentJournal(found.orElseThrow(), task.id(),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(tasks, task));
        journal.read();
        return Optional.of(journal);
    }

    /** Reads and validates all original fields before remote access. / 远端访问前读取并核对全部原字段。
     * @return validated original state / 已核原状态
     * @throws ApiException if original row is unavailable / 原行不可用时 */
    DeploymentEnvironmentState read() throws ApiException {
        return decode(checkpoint.snapshot().payload(), taskId, layout);
    }

    /** Performs exact CAS advancement without replacing another writer's facts. / 精确 CAS 推进，不覆盖另一写入方事实。
     * @param before exact original state / 精确原状态
     * @param next next verified state / 后续已核状态
     * @throws ApiException if original persistence or CAS fails / 原持久化或 CAS 失败时 */
    void advance(DeploymentEnvironmentState before, DeploymentEnvironmentState next) throws ApiException {
        var snapshot = checkpoint.snapshot();
        if (!before.equals(decode(snapshot.payload(), taskId, layout)) || !before.operation().equals(next.operation())
            || !before.layout().equals(next.layout()) || !before.bodySha().equals(next.bodySha()) || !before.target().server().equals(next.target().server())
            || !before.target().taskId().equals(next.target().taskId()) || !before.target().host().equals(next.target().host())
            || before.target().port() != next.target().port() || !before.target().username().equals(next.target().username())
            || before.target().fingerprint().isPresent() && !before.target().fingerprint().equals(next.target().fingerprint()))
            throw new IllegalArgumentException("Original environment CAS facts differ");
        checkpoint.advance(snapshot, encode(next));
    }

    /** Releases only a proven unsent or verified terminal operation. / 仅释放已证明未发送或已核终态操作。
     * @throws ApiException if original physical occupancy release fails / 原实际占用释放失败时 */
    void close() throws ApiException {
        var snapshot = checkpoint.snapshot();
        if (snapshot.released()) return;
        if (!Set.of("INTENT", "OBSERVED", "VERIFIED", "FAILED").contains(read().stage()))
            throw new IllegalArgumentException("Original environment effect remains unresolved");
        checkpoint.close();
    }

    /** Determines whether cancellation can retain known facts without leaving an attempted security effect unresolved. / 判断取消能否保留已知事实且不留下未决的已尝试安全副作用。
     * @return true only for known original receipts and a complete or inapplicable security phase / 仅原已知回执且安全阶段完成或不适用时为真
     * @throws ApiException if the original row cannot be read / 原行无法读取时 */
    boolean cancellationSettled() throws ApiException {
        var state = read();
        if (!Set.of("SELINUX_COMPLETE", "PACKAGES_INSTALLED", "HELPER_INSTALLED", "VERIFIED", "FAILED").contains(state.stage()))
            return false;
        var observed = state.observation().orElseThrow();
        return observed.selinuxPhase().equals("NOT_APPLICABLE")
            || observed.selinuxPhase().equals("COMPLETE") && observed.security().equals("Enforcing");
    }

    /** Releases the original cancelled operation after its attempted effects are known and its connection actually closed. / 已尝试副作用已知且连接实际关闭后释放原取消操作。
     * @throws ApiException if original evidence or release fails / 原依据或释放失败时 */
    void closeCancelled() throws ApiException {
        if (checkpoint.snapshot().released()) return;
        if (!cancellationSettled())
            throw new IllegalArgumentException("Cancelled original environment effect remains unresolved");
        checkpoint.close();
    }

    /** Encodes only bounded original non-secret values. / 仅编码有界原非秘密值。
     * @param state original state / 原状态
     * @return exact JSON object / 精确 JSON 对象 */
    private static String encode(DeploymentEnvironmentState state) {
        var target = state.target();
        var endpoint = new LinkedHashMap<String, Object>();
        endpoint.put("server", target.server().id());
        endpoint.put("task", target.taskId().toString());
        endpoint.put("host", target.host());
        endpoint.put("port", target.port());
        endpoint.put("username", target.username());
        endpoint.put("fingerprint", target.fingerprint().orElse(null));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("formatVersion", state.formatVersion());
        fields.put("target", endpoint);
        fields.put("operation", state.operation().toString());
        fields.put("bodySha", state.bodySha());
        fields.put("stage", state.stage());
        fields.put("requestSha", state.requestSha());
        fields.put("observation", state.observation().orElse(null));
        try {
            return JSON.writeValueAsString(fields);
        } catch (IOException impossible) {
            throw new IllegalStateException("Environment encoding failed", impossible);
        }
    }

    /** Rejects unknown fields, substituted task or changed fixed implementation. / 拒绝未知字段、替代任务或改变的固定实现。
     * @param payload original JSON / 原 JSON
     * @param taskId authenticated original task / 已认证原任务
     * @return exact decoded original state / 精确解码原状态 */
    private static DeploymentEnvironmentState decode(String payload, UUID taskId,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        try {
            var root = JSON.readTree(payload);
            keys(root, Set.of("formatVersion", "target", "operation", "bodySha", "stage", "requestSha", "observation"));
            var target = root.path("target");
            keys(target, Set.of("server", "task", "host", "port", "username", "fingerprint"));
            if (!root.path("formatVersion").isIntegralNumber() || !root.path("formatVersion").canConvertToInt()
                || root.path("formatVersion").intValue() != 1
                || !target.path("port").isIntegralNumber() || !target.path("port").canConvertToInt()
                || !taskId.toString().equals(text(target, "task"))
                || !DeploymentEnvironmentScripts.bodyDigest(layout).equals(text(root, "bodySha")))
                throw new IllegalArgumentException("Original environment task or implementation differs");
            var endpoint = new RemoteTarget(new ServerId(text(target, "server")), taskId, text(target, "host"),
                target.path("port").intValue(), text(target, "username"), target.path("fingerprint").isNull()
                ? Optional.empty() : Optional.of(text(target, "fingerprint")));
            var observation = root.path("observation").isNull() ? Optional.<DeploymentEnvironmentObservation>empty()
                : Optional.of(JSON.treeToValue(root.path("observation"), DeploymentEnvironmentObservation.class));
            return new DeploymentEnvironmentState(1, endpoint, UUID.fromString(text(root, "operation")),
                text(root, "bodySha"), text(root, "stage"), text(root, "requestSha"), observation, layout);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid original environment JSON", invalid);
        }
    }

    /** Requires an exact JSON object field set. / 要求精确 JSON 对象字段集合。
     * @param node original object / 原对象
     * @param expected exact fields / 精确字段 */
    private static void keys(JsonNode node, Set<String> expected) {
        var names = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        if (!node.isObject() || !names.equals(expected))
            throw new IllegalArgumentException("Unknown environment checkpoint field");
    }

    /** Reads strict text without coercion. / 不进行类型转换地读取严格文本。
     * @param node object / 对象
     * @param key exact field / 精确字段
     * @return text / 文本 */
    private static String text(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw new IllegalArgumentException("Non-text environment checkpoint field");
        return node.path(key).textValue();
    }
}
