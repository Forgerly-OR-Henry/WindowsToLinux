package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Bounded original-task index of every planned database child operation. / 每项计划数据库子操作的有界原任务索引。 */
public final class DeploymentDatabasePlanActionLog {
    /** One original durable key. / 唯一原持久键。 */
    public static final String KEY = "deployment-database-plan";

    /** Strict bounded checkpoint codec. / 严格有界检查点编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Exact original checkpoint. / 精确原检查点。 */
    private final TaskCheckpoint checkpoint;

    /** Original task identity. / 原任务身份。 */
    private final UUID taskId;

    /** One planned component and database pair. / 一项计划组件与数据库对。
     * @param componentId exact component ID / 精确组件 ID
     * @param databaseId exact declared database ID / 精确声明数据库 ID */
    public record Entry(String componentId, String databaseId) {
        /** Validates the original bounded business identifiers. / 校验原有界业务标识。
         * @param componentId exact component ID / 精确组件 ID
         * @param databaseId exact database ID / 精确数据库 ID */
        public Entry {
            if (componentId == null || !componentId.matches("[a-z0-9][a-z0-9-]{0,62}") || databaseId == null
                || !databaseId.matches("[a-z][a-z0-9-]{0,31}"))
                throw new IllegalArgumentException("Invalid planned database identity");
        }

        /** Computes only the compact checkpoint address, never a business identity. / 仅计算紧凑检查点地址，绝不充作业务身份。
         * @return first 128 SHA-256 bits as lowercase hex / SHA-256 前128位小写十六进制 */
        public String key() {
            try {
                byte[] source = (componentId + "\0" + databaseId).getBytes(StandardCharsets.UTF_8);
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source), 0, 16);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
            }
        }
    }

    /** Non-secret fixed source and child addresses. / 不含秘密的固定源码与子项地址。
     * @param serverId exact admitted server / 精确已准入服务器
     * @param manifestSha256 frozen source manifest digest / 冻结源码清单摘要
     * @param keys sorted unique child addresses / 排序且唯一的子项地址 */
    public record Facts(String serverId, String manifestSha256, List<String> keys) {
        /** Validates the full bounded plan before the first database effect. / 首个数据库效果前校验完整有界计划。
         * @param serverId exact server / 精确服务器
         * @param manifestSha256 frozen manifest SHA-256 / 冻结清单 SHA-256
         * @param keys sorted unique addresses / 排序且唯一的地址 */
        public Facts {
            Objects.requireNonNull(serverId);
            Objects.requireNonNull(manifestSha256);
            keys = List.copyOf(keys);
            if (serverId.isBlank() || serverId.length() > 512 || serverId.chars().anyMatch(Character::isISOControl)
                || !manifestSha256.matches("[0-9a-f]{64}") || keys.size() > 256)
                throw new IllegalArgumentException("Invalid database plan facts");
            String previous = "";
            for (String key : keys) {
                if (key == null || !key.matches("[0-9a-f]{32}") || previous.compareTo(key) >= 0)
                    throw new IllegalArgumentException("Database plan keys must be sorted and unique");
                previous = key;
            }
        }
    }

    /** Wraps one original checkpoint. / 包装一个原检查点。
     * @param checkpoint original checkpoint / 原检查点
     * @param taskId original task ID / 原任务 ID */
    private DeploymentDatabasePlanActionLog(TaskCheckpoint checkpoint, UUID taskId) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.taskId = Objects.requireNonNull(taskId);
    }

    /** Persists the entire child index before any listed database effect. / 任何列入数据库效果前持久保存完整子项索引。
     * @param tasks original task service / 原任务服务
     * @param task admitted original task / 已准入原任务
     * @param server exact server / 精确服务器
     * @param manifestSha256 frozen manifest digest / 冻结清单摘要
     * @param entries all locally validated component/database pairs / 已在本地验证的所有组件数据库对
     * @return original plan journal / 原计划日志
     * @throws ApiException if original persistence fails / 原持久化失败时 */
    public static DeploymentDatabasePlanActionLog begin(TaskService tasks, TaskContext task, ServerId server,
                                                        String manifestSha256, List<Entry> entries) throws ApiException {
        if (entries.size() > 256 || new HashSet<>(entries).size() != entries.size())
            throw new IllegalArgumentException("Duplicate or excessive planned database target");
        var keys = entries.stream().map(Entry::key).sorted().toList();
        var facts = new Facts(server.id(), manifestSha256, keys);
        String payload = encode(task.id(), facts);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 12_000)
            throw new IllegalArgumentException("Database plan exceeds platform checkpoint limit");
        return new DeploymentDatabasePlanActionLog(tasks.beginCheckpoint(task, KEY, payload), task.id());
    }

    /** Finds only the original plan, including a released checkpoint. / 仅读取原计划，包括已释放的检查点。
     * @param tasks original task service / 原任务服务
     * @param task original task / 原任务
     * @return original plan or genuine absence / 原计划或真实不存在
     * @throws ApiException if lookup fails / 查找失败时 */
    public static Optional<DeploymentDatabasePlanActionLog> find(TaskService tasks, TaskContext task)
        throws ApiException {
        var found = tasks.findCheckpoint(task, KEY);
        if (found.isEmpty())
            return Optional.empty();
        var plan = new DeploymentDatabasePlanActionLog(found.orElseThrow(), task.id());
        plan.facts();
        return Optional.of(plan);
    }

    /** Reads exact original non-secret facts. / 读取精确原非秘密事实。
     * @return persisted plan / 已持久计划
     * @throws ApiException if snapshot lookup fails / 快照查找失败时 */
    public Facts facts() throws ApiException {
        return decode(checkpoint.snapshot().payload(), taskId);
    }

    /** Releases the plan after every child has been reconciled and closed. / 全部子项核对并释放后才释放计划。
     * @throws ApiException if release fails / 释放失败时 */
    public void close() throws ApiException {
        checkpoint.close();
    }

    /** Encodes the exact minimal bounded index. / 编码精确最小有界索引。
     * @param taskId original task / 原任务
     * @param facts plan facts / 计划事实
     * @return JSON payload / JSON 载荷 */
    private static String encode(UUID taskId, Facts facts) {
        try {
            return JSON.writeValueAsString(
                new Payload(1, taskId.toString(), facts.serverId(), facts.manifestSha256(), facts.keys()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Database plan encoding failed", impossible);
        }
    }

    /** Decodes without treating damaged or conflicting evidence as absence. / 解码时不把损坏或冲突证据当作不存在。
     * @param payload original JSON / 原 JSON
     * @param taskId original task / 原任务
     * @return verified plan / 已核实计划 */
    private static Facts decode(String payload, UUID taskId) {
        try {
            var node = JSON.readTree(payload);
            if (node == null || !node.isObject() || node.size() != 5 || !node.path("formatVersion").isInt()
                || node.path("formatVersion").asInt() != 1
                || !taskId.toString().equals(node.path("taskId").textValue()) || !node.path("keys").isArray())
                throw new IllegalArgumentException("Invalid database plan checkpoint");
            var names = new HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(java.util.Set.of("formatVersion", "taskId", "serverId", "manifestSha256", "keys")))
                throw new IllegalArgumentException("Invalid database plan fields");
            var keys = new ArrayList<String>();
            node.path("keys").forEach(value -> keys.add(value.textValue()));
            var facts = new Facts(node.path("serverId").textValue(), node.path("manifestSha256").textValue(), keys);
            if (payload.getBytes(StandardCharsets.UTF_8).length > 12_000)
                throw new IllegalArgumentException("Database plan checkpoint exceeds limit");
            return facts;
        } catch (IOException | NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid database plan checkpoint", invalid);
        }
    }

    /** Minimal stored JSON shape. / 最小持久 JSON 形状。
     * @param formatVersion format version / 格式版本
     * @param taskId original task / 原任务
     * @param serverId exact server / 精确服务器
     * @param manifestSha256 frozen manifest digest / 冻结清单摘要
     * @param keys planned child addresses / 计划子项地址 */
    private record Payload(int formatVersion, String taskId, String serverId, String manifestSha256,
                           List<String> keys) {
    }
}
