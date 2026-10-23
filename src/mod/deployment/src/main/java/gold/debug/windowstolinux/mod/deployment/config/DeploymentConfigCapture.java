package gold.debug.windowstolinux.mod.deployment.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Retains independent configuration evidence on the same original task without changing its operation journal. / 在同一原任务保留独立配置依据，不改变操作日志。
 */
public final class DeploymentConfigCapture {
    /** Released configuration evidence remains available until task archival. / 已释放的配置依据保留至任务归档。 */
    private static final String KEY = "deployment.config.environment.v1";

    private static final String REMOTE_LAYOUT_KEY = "deployment.config.remote-layout.v1";

    /** Exact non-secret evidence codec. / 精确非秘密依据编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Prevents construction. / 禁止构造。 */
    private DeploymentConfigCapture() {
    }

    /** Saves and releases metadata before the original environment operation begins. / 原环境操作开始前保存并释放元数据。
     * @param tasks original task service / 原任务服务
     * @param task authentic original task / 真实原任务
     * @param timing initial captured timing / 初始捕获时序
     * @throws ApiException if durable capture or release is unconfirmed / 持久捕获或释放未确认时 */
    public static void environment(TaskService tasks, TaskContext task, DeploymentConfig.Environment timing) throws ApiException {
        String payload;
        try {
            payload = JSON.writeValueAsString(Map.of("formatVersion", 1, "task", task.id().toString(),
                "installSeconds", timing.installSeconds(), "checkSeconds", timing.checkSeconds(),
                "rebootSeconds", timing.rebootSeconds(), "pollSeconds", timing.pollSeconds()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Environment configuration encoding failed", impossible);
        }
        try (var checkpoint = tasks.beginCheckpoint(task, KEY, payload)) {
            if (!payload.equals(checkpoint.snapshot().payload()))
                throw new IllegalStateException("Original environment configuration capture differs");
        }
    }

    /** Requires the original captured timing for current-state recovery. / 新版状态恢复须具有原捕获时序。
     * @param tasks original task service / 原任务服务
     * @param task authentic original recovery task / 真实原恢复任务
     * @return captured timing independent of the current YAML / 独立于当前 YAML 的捕获时序
     * @throws ApiException if durable evidence cannot be read / 持久依据不可读时
     * @throws IllegalArgumentException if original evidence is malformed / 原依据非法时 */
    public static DeploymentConfig.Environment environment(TaskService tasks, TaskContext task) throws ApiException {
        var checkpoint = tasks.findCheckpoint(task, KEY);
        if (checkpoint.isEmpty()) throw new IllegalArgumentException("Original environment configuration checkpoint is required");
        try {
            String payload = checkpoint.orElseThrow().snapshot().payload();
            if (payload.length() > 4096)
                throw new IllegalArgumentException("Environment configuration evidence exceeds limits");
            var node = JSON.readTree(payload);
            if (node == null || !node.isObject())
                throw new IllegalArgumentException("Original environment configuration evidence must be an object");
            var names = new java.util.HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!node.isObject() || !names.equals(Set.of("formatVersion", "task", "installSeconds", "checkSeconds", "rebootSeconds", "pollSeconds"))
                || !node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                || node.path("formatVersion").intValue() != 1 || !node.path("task").isTextual()
                || !task.id().toString().equals(node.path("task").textValue()))
                throw new IllegalArgumentException("Original environment configuration identity differs");
            for (String name : Set.of("installSeconds", "checkSeconds", "rebootSeconds", "pollSeconds")) {
                if (!node.path(name).isIntegralNumber() || !node.path(name).canConvertToInt())
                    throw new IllegalArgumentException("Original environment configuration timing is invalid");
            }
            return new DeploymentConfig.Environment(node.path("installSeconds").intValue(), node.path("checkSeconds").intValue(),
                node.path("rebootSeconds").intValue(), node.path("pollSeconds").intValue());
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Original environment configuration evidence is invalid", invalid);
        }
    }

    /** Captures and releases one original layout before effects; an existing key cannot be replaced. / 效果前捕获并释放一份原布局，既有键不可替换。
     * @param tasks authentic task service / 真实任务服务
     * @param task original executing task / 原执行任务
     * @param layout explicitly selected immutable layout / 显式选择的不可变布局
     * @throws ApiException if capture or release cannot be confirmed / 捕获或释放无法确认时
     * @throws IllegalArgumentException if the original key already exists / 原键已存在时 */
    public static void remoteLayout(TaskService tasks, TaskContext task, DeploymentRemoteLayout layout) throws ApiException {
        if (tasks.findCheckpoint(task, REMOTE_LAYOUT_KEY).isPresent())
            throw new IllegalArgumentException("Original remote layout configuration checkpoint already exists");
        String payload;
        try {
            var node = JSON.createObjectNode().put("formatVersion", 1).put("task", task.id().toString());
            node.set("layout", JSON.readTree(layout.canonical()));
            node.put("layoutSha256", layout.digest());
            payload = JSON.writeValueAsString(node);
        } catch (IOException impossible) {
            throw new IllegalStateException("Remote layout configuration encoding failed", impossible);
        }
        try (var checkpoint = tasks.beginCheckpoint(task, REMOTE_LAYOUT_KEY, payload)) {
            if (!payload.equals(checkpoint.snapshot().payload()))
                throw new IllegalStateException("Original remote layout configuration capture differs");
        }
    }

    /** Requires original evidence during recovery; legacy choice belongs to the versioned caller. / 恢复须具有原依据，旧版选择由分版本调用方负责。
     * @param tasks authentic task service / 真实任务服务
     * @param task original executing or recovery task / 原执行或恢复任务
     * @return exact captured layout independent of current configuration / 独立于当前配置的精确捕获布局
     * @throws ApiException if durable evidence cannot be read / 持久依据不可读时
     * @throws IllegalArgumentException if evidence is absent or invalid / 依据缺失或非法时 */
    public static DeploymentRemoteLayout remoteLayout(TaskService tasks, TaskContext task) throws ApiException {
        var checkpoint = tasks.findCheckpoint(task, REMOTE_LAYOUT_KEY);
        if (checkpoint.isEmpty())
            throw new IllegalArgumentException("Original remote layout configuration checkpoint is required");
        return decodeRemoteLayout(task.id(), checkpoint.orElseThrow().snapshot().payload());
    }

    /** Historical tasks without the dedicated layout checkpoint used the literal original roots. */
    public static DeploymentRemoteLayout originalTaskLayout(TaskService tasks, TaskContext task) throws ApiException {
        var checkpoint = tasks.findCheckpoint(task, REMOTE_LAYOUT_KEY);
        return checkpoint.isEmpty() ? DeploymentRemoteLayout.original()
            : decodeRemoteLayout(task.id(), checkpoint.orElseThrow().snapshot().payload());
    }

    static DeploymentRemoteLayout decodeRemoteLayout(UUID originalTask, String payload) {
        if (originalTask == null)
            throw new IllegalArgumentException("Original remote layout task identity is required");
        if (payload == null || payload.length() > 4096 || payload.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("Remote layout configuration evidence is missing or exceeds limits");
        try {
            var node = JSON.readTree(payload);
            if (node == null || !node.isObject())
                throw new IllegalArgumentException("Original remote layout configuration evidence must be an object");
            var names = new java.util.HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(Set.of("formatVersion", "task", "layout", "layoutSha256"))
                || !node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                || node.path("formatVersion").intValue() != 1 || !node.path("task").isTextual()
                || !originalTask.toString().equals(node.path("task").textValue())
                || !node.path("layout").isObject() || !node.path("layoutSha256").isTextual())
                throw new IllegalArgumentException("Original remote layout configuration identity differs");
            var layout = DeploymentRemoteLayout.decode(node.path("layout").toString());
            if (!layout.digest().equals(node.path("layoutSha256").textValue()))
                throw new IllegalArgumentException("Original remote layout configuration digest differs");
            return layout;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Original remote layout configuration evidence is invalid", invalid);
        }
    }
}
