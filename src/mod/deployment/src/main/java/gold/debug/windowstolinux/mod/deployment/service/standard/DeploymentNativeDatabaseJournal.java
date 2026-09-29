package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskService;

/** Original-task non-secret identity of one serial native database operation. / 一项串行原生数据库操作的原任务非秘密身份。 */
public final class DeploymentNativeDatabaseJournal {
    /** One original key survives uncertain dispatch and release. / 一项原键跨未知发送和释放保留。 */
    public static final String KEY = "deployment-native-database";

    /** Strict checkpoint codec. / 严格的检查点编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Exact persisted field set. / 精确的持久字段集合。 */
    private static final Set<String> FIELDS = Set.of("formatVersion", "taskId", "serverId", "operationId", "action",
            "targetDigest", "requestDigest", "target", "phase", "effectBoundary", "effectsObserved", "effectTargets");

    /** Exact original checkpoint. / 精确原检查点。 */
    private final TaskCheckpoint checkpoint;

    /** Original task identity. / 原任务身份。 */
    private final UUID taskId;

    /** Immutable non-secret recovery facts. / 不可变的非秘密恢复事实。
     * @param serverId exact server / 精确服务器
     * @param operationId original operation UUID / 原操作 UUID
     * @param action helper action / helper 动作
     * @param targetDigest digest of actual target fields / 实际目标字段摘要
     * @param requestDigest digest of the complete original frame / 原完整帧摘要
     * @param target bounded business target / 有界业务目标
     * @param phase original journal phase / 原日志阶段
     * @param effectBoundary observed helper boundary / 已观察 helper 效果边界
     * @param effectsObserved whether this task may have produced a native DB effect / 本任务是否可能产生原生数据库效果
     * @param effectTargets bounded targets of settled earlier effects / 已结清先前效果的有界目标 */
    public record Facts(String serverId, UUID operationId, String action, String targetDigest, String requestDigest,
            String target, String phase, String effectBoundary, boolean effectsObserved, List<String> effectTargets) {
        /** Requires a complete bounded original identity. / 要求完整有界原身份。
         * @param serverId exact server / 精确服务器
         * @param operationId original operation UUID / 原操作 UUID
         * @param action helper action / helper 动作
         * @param targetDigest target digest / 目标摘要
         * @param requestDigest request digest / 请求摘要
         * @param target business target / 业务目标
         * @param phase original phase / 原阶段
         * @param effectBoundary helper effect boundary / helper 效果边界
         * @param effectsObserved possible earlier effect / 先前可能效果
         * @param effectTargets settled earlier targets / 已结清先前目标 */
        public Facts {
            if (serverId == null || serverId.isBlank() || serverId.length() > 512
                    || serverId.chars().anyMatch(value -> value < 32) || operationId == null
                    || !Set.of("install", "start", "resume", "prepare", "initialize").contains(action)
                    || targetDigest == null || !targetDigest.matches("[0-9a-f]{64}") || requestDigest == null
                    || !requestDigest.matches("[0-9a-f]{64}") || target == null || target.isBlank()
                    || target.length() > 256 || !Set.of("INTENT", "DISPATCHED", "SUCCEEDED", "FAILED").contains(phase)
                    || !Set.of("NONE", "MAY_HAVE_RUN", "CONFIRMED").contains(effectBoundary)
                    || "INTENT".equals(phase) && !"NONE".equals(effectBoundary)
                    || "DISPATCHED".equals(phase) && !"MAY_HAVE_RUN".equals(effectBoundary)
                    || "SUCCEEDED".equals(phase) && !"CONFIRMED".equals(effectBoundary)
                    || "FAILED".equals(phase) && "CONFIRMED".equals(effectBoundary) || effectTargets == null
                    || effectTargets.size() > 64
                    || effectTargets.stream()
                            .anyMatch(value -> value == null || value.isBlank() || value.length() > 256)
                    || effectTargets.stream().mapToInt(String::length).sum() > 4096
                    || effectsObserved != !effectTargets.isEmpty())
                throw new IllegalArgumentException("Incomplete native database operation identity");
            effectTargets = List.copyOf(effectTargets);
        }
    }

    /** Wraps an original checkpoint. / 包装原检查点。
     * @param checkpoint original checkpoint / 原检查点
     * @param taskId original task identity / 原任务身份 */
    private DeploymentNativeDatabaseJournal(TaskCheckpoint checkpoint, UUID taskId) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.taskId = Objects.requireNonNull(taskId);
    }

    /** Persists the exact first operation before any remote dispatch. / 任何远端发送前持久保存第一项精确操作。
     * @param tasks original task service / 原任务服务
     * @param task admitted task / 已准入任务
     * @param server admitted server / 已准入服务器
     * @param operationId fixed operation identity / 固定操作身份
     * @param action helper action / helper 动作
     * @param targetDigest actual target digest / 实际目标摘要
     * @param requestDigest complete frame digest / 完整帧摘要
     * @param target business target / 业务目标
     * @return retained journal / 保留的日志
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    public static DeploymentNativeDatabaseJournal begin(TaskService tasks, TaskContext task, ServerRef server,
            UUID operationId, String action, String targetDigest, String requestDigest, String target)
            throws ApiException {
        return begin(tasks, task, server, KEY, operationId, action, targetDigest, requestDigest, target);
    }

    /** Persists one indexed database's first operation before any remote dispatch. / 远端发送前持久保存一项已索引数据库的首操作。
     * @param tasks original task service / 原任务服务
     * @param task admitted task / 已准入任务
     * @param server admitted server / 已准入服务器
     * @param key durable per-database key from the original plan / 原计划中的每库持久键
     * @param operationId fixed operation identity / 固定操作身份
     * @param action helper action / helper 动作
     * @param targetDigest actual target digest / 实际目标摘要
     * @param requestDigest complete frame digest / 完整帧摘要
     * @param target business target / 业务目标
     * @return retained journal / 保留的日志
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    public static DeploymentNativeDatabaseJournal begin(TaskService tasks, TaskContext task, ServerRef server,
            String key, UUID operationId, String action, String targetDigest, String requestDigest, String target)
            throws ApiException {
        checkedKey(key);
        var facts = new Facts(server.id(), operationId, action, targetDigest, requestDigest, target, "INTENT", "NONE",
                false, List.of());
        requireTerminalCapacity(task.id(), facts);
        return new DeploymentNativeDatabaseJournal(tasks.beginCheckpoint(task, key, encode(task.id(), facts)),
                task.id());
    }

    /** Finds only the exact original task checkpoint. / 仅读取精确原任务检查点。
     * @param tasks original task service / 原任务服务
     * @param task original task / 原任务
     * @return original journal or absence / 原日志或不存在
     * @throws ApiException if reading fails / 读取失败时 */
    public static Optional<DeploymentNativeDatabaseJournal> find(TaskService tasks, TaskContext task)
            throws ApiException {
        return find(tasks, task, KEY);
    }

    /** Finds one indexed database's exact original checkpoint. / 查找一项已索引数据库的精确原检查点。
     * @param tasks original task service / 原任务服务
     * @param task original task / 原任务
     * @param key durable per-database key / 每库持久键
     * @return original journal or genuine absence / 原日志或真实不存在
     * @throws ApiException if reading fails / 读取失败时 */
    public static Optional<DeploymentNativeDatabaseJournal> find(TaskService tasks, TaskContext task, String key)
            throws ApiException {
        checkedKey(key);
        var found = tasks.findCheckpoint(task, key);
        if (found.isEmpty())
            return Optional.empty();
        var journal = new DeploymentNativeDatabaseJournal(found.orElseThrow(), task.id());
        journal.facts();
        return Optional.of(journal);
    }

    /** Rejects unplanned and malformed journal keys. / 拒绝未规划及畸形日志键。
     * @param key candidate key / 候选键 */
    private static void checkedKey(String key) {
        if (!KEY.equals(key) && (key == null || !key.matches("deployment-native-database\\.[0-9a-f]{32}")))
            throw new IllegalArgumentException("Invalid native database journal key");
    }

    /** Reads the exact persisted identity. / 读取精确持久身份。
     * @return original facts / 原事实
     * @throws ApiException if the checkpoint cannot be read / 无法读取检查点时 */
    public Facts facts() throws ApiException {
        return decode(checkpoint.snapshot().payload(), taskId);
    }

    /** Records that sending may begin, immediately before execute. / 在执行前紧邻记录可能开始发送。
     * @throws ApiException if original CAS fails / 原比较更新失败时 */
    public void dispatched() throws ApiException {
        var before = checkpoint.snapshot();
        var facts = decode(before.payload(), taskId);
        if (!"INTENT".equals(facts.phase()))
            throw new IllegalArgumentException("Native database operation was already dispatched");
        advance(before,
                new Facts(facts.serverId(), facts.operationId(), facts.action(), facts.targetDigest(),
                        facts.requestDigest(), facts.target(), "DISPATCHED", "MAY_HAVE_RUN", facts.effectsObserved(),
                        facts.effectTargets()));
    }

    /** Saves only a helper-confirmed terminal receipt of this original operation. / 仅保存此原操作由 helper 确认的终态回执。
     * @param phase confirmed SUCCEEDED or FAILED / 已确认成功或失败
     * @param boundary exact helper effect boundary / 精确 helper 效果边界
     * @throws ApiException if original CAS fails / 原比较更新失败时 */
    public void terminal(String phase, String boundary) throws ApiException {
        var before = checkpoint.snapshot();
        var facts = decode(before.payload(), taskId);
        if (!Set.of("SUCCEEDED", "FAILED").contains(phase) || !"DISPATCHED".equals(facts.phase())
                || "CONFIRMED".equals(boundary) != "SUCCEEDED".equals(phase))
            throw new IllegalArgumentException("Native database terminal evidence differs");
        var targets = new ArrayList<>(facts.effectTargets());
        if (!"NONE".equals(boundary) && !targets.contains(facts.target()))
            targets.add(facts.target());
        advance(before, new Facts(facts.serverId(), facts.operationId(), facts.action(), facts.targetDigest(),
                facts.requestDigest(), facts.target(), phase, boundary, !targets.isEmpty(), targets));
    }

    /** Begins the next serial operation only after the previous exact terminal was saved. / 仅在前项精确终态已保存后开始下一串行操作。
     * @param operationId next fixed UUID / 下一固定 UUID
     * @param action next helper action / 下一 helper 动作
     * @param targetDigest digest of next actual target / 下一实际目标摘要
     * @param requestDigest next complete frame digest / 下一完整帧摘要
     * @param target bounded next business target / 下一有界业务目标
     * @throws ApiException if original CAS fails / 原比较更新失败时 */
    public void next(UUID operationId, String action, String targetDigest, String requestDigest, String target)
            throws ApiException {
        var before = checkpoint.snapshot();
        var prior = decode(before.payload(), taskId);
        if (!Set.of("SUCCEEDED", "FAILED").contains(prior.phase()))
            throw new IllegalArgumentException("Previous native database operation remains unresolved");
        var next = new Facts(prior.serverId(), operationId, action, targetDigest, requestDigest, target, "INTENT",
                "NONE", prior.effectsObserved(), prior.effectTargets());
        requireTerminalCapacity(taskId, next);
        advance(before, next);
    }

    /** Releases only settled or provably unsent occupancy. / 仅释放已结清或可证明未发送的占用。
     * @throws ApiException if release fails / 释放失败时 */
    public void close() throws ApiException {
        if (!Set.of("INTENT", "SUCCEEDED", "FAILED").contains(facts().phase()))
            throw new IllegalArgumentException("Native database operation is unresolved");
        checkpoint.close();
    }

    /** Atomically advances original facts. / 原子推进原事实。
     * @param before expected snapshot / 预期快照
     * @param facts next facts / 下一事实
     * @throws ApiException if comparison or persistence fails / 比较或持久化失败时 */
    private void advance(TaskCheckpoint.Snapshot before, Facts facts) throws ApiException {
        checkpoint.advance(before, encode(taskId, facts));
    }

    /** Reserves the full persisted terminal payload before the next possible effect. / 下一可能效果前预留完整终态持久载荷。
     * @param taskId original task / 原任务
     * @param facts unsent next operation / 未发送的下一操作 */
    private static void requireTerminalCapacity(UUID taskId, Facts facts) {
        var targets = new ArrayList<>(facts.effectTargets());
        if (!targets.contains(facts.target()))
            targets.add(facts.target());
        var terminal = new Facts(facts.serverId(), facts.operationId(), facts.action(), facts.targetDigest(),
                facts.requestDigest(), facts.target(), "SUCCEEDED", "CONFIRMED", true, targets);
        if (encode(taskId, terminal).getBytes(StandardCharsets.UTF_8).length > 12_000)
            throw new IllegalArgumentException("Native database terminal checkpoint exceeds platform limit");
    }

    /** Encodes bounded non-secret facts. / 编码有界非秘密事实。
     * @param taskId original task identity / 原任务身份
     * @param facts original facts / 原事实
     * @return checkpoint payload / 检查点载荷 */
    private static String encode(UUID taskId, Facts facts) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("formatVersion", 1);
        fields.put("taskId", taskId.toString());
        fields.put("serverId", facts.serverId());
        fields.put("operationId", facts.operationId().toString());
        fields.put("action", facts.action());
        fields.put("targetDigest", facts.targetDigest());
        fields.put("requestDigest", facts.requestDigest());
        fields.put("target", facts.target());
        fields.put("phase", facts.phase());
        fields.put("effectBoundary", facts.effectBoundary());
        fields.put("effectsObserved", facts.effectsObserved());
        fields.put("effectTargets", facts.effectTargets());
        try {
            return JSON.writeValueAsString(fields);
        } catch (IOException impossible) {
            throw new IllegalStateException("Native database checkpoint encoding failed", impossible);
        }
    }

    /** Decodes and checks exact original facts. / 解码并核对精确原事实。
     * @param payload stored payload / 已存载荷
     * @param taskId original task identity / 原任务身份
     * @return original facts / 原事实 */
    private static Facts decode(String payload, UUID taskId) {
        try {
            var node = JSON.readTree(payload);
            if (node == null || !node.isObject() || node.size() != FIELDS.size()
                    || !node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                    || node.path("formatVersion").asInt() != 1
                    || !taskId.toString().equals(node.path("taskId").textValue())
                    || !node.path("effectsObserved").isBoolean() || !node.path("effectTargets").isArray())
                throw new IllegalArgumentException("Invalid native database checkpoint");
            var names = new java.util.HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(FIELDS))
                throw new IllegalArgumentException("Unknown native database checkpoint field");
            var targets = new ArrayList<String>();
            node.path("effectTargets").forEach(value -> targets.add(value.textValue()));
            return new Facts(node.path("serverId").textValue(), UUID.fromString(node.path("operationId").textValue()),
                    node.path("action").textValue(), node.path("targetDigest").textValue(),
                    node.path("requestDigest").textValue(), node.path("target").textValue(),
                    node.path("phase").textValue(), node.path("effectBoundary").textValue(),
                    node.path("effectsObserved").booleanValue(), targets);
        } catch (IOException | NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid native database checkpoint", invalid);
        }
    }
}
