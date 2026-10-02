package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;

/** Journals an original management task before any remote effect and keeps uncertain phases. / 在远端副作用前记录原管理任务，并保留未知阶段。 */
public final class DeploymentManagementJournal {
    /** Strict original management evidence JSON without duplicate fields. / 无重复字段的严格原管理依据JSON。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Complete original component evidence byte bound. / 完整原组件依据字节界限。 */
    private static final int LIMIT = 1_048_576;

    /** Exact provider Schema and task authentication capabilities. / 精确提供方Schema及任务认证能力。 */
    private final ModContext context;

    /** Uses only the deployment-owned database and host-certified task. / 仅使用部署所属数据库和宿主认证任务。
     * @param context original Mod context / 原Mod上下文 */
    public DeploymentManagementJournal(ModContext context) {
        this.context = java.util.Objects.requireNonNull(context);
    }

    /** One authenticated immutable journal view. / 一项认证的不可变日志视图。
     * @param id operation identity / 操作身份
     * @param taskId original task / 原任务
     * @param ownerId caller Mod / 调用Mod
     * @param ownerVersion caller generation / 调用代次
     * @param applicationId application identity / 应用身份
     * @param server exact server / 精确服务器
     * @param action reviewed action / 已审阅动作
     * @param currentRevision expected current release / 预期当前发布
     * @param targetRevision optional target release / 可选目标发布
     * @param phase durable phase / 持久阶段
     * @param facts bounded operation facts / 有界操作事实 */
    public record Entry(UUID id, UUID taskId, String ownerId, String ownerVersion, String applicationId,
                        ServerRef server, String action, String currentRevision, Optional<String> targetRevision,
                        String phase,
                        JsonNode facts) {
    }

    /** Inserts or confirms the exact original intent. / 插入或核实精确原意图。
     * @param application application ID / 应用ID
     * @param server reviewed server / 已审阅服务器
     * @param action fixed action / 固定动作
     * @param currentRevision reviewed current identity / 已审阅当前身份
     * @param targetRevision optional target / 可选目标
     * @param facts complete bounded before facts / 完整有界前态事实
     * @param task original task / 原任务
     * @return original row / 原行
     * @throws ApiException if owner, intent or commit cannot be confirmed / 所有者、意图或提交无法确认时 */
    public Entry begin(String application, ServerRef server, String action, String currentRevision,
                       Optional<String> targetRevision, JsonNode facts, TaskContext task) throws ApiException {
        TaskSnapshot owner = context.tasks().inspect(task);
        context.servers().get(server, task);
        byte[] payload = bytes(facts);
        UUID id = UUID.nameUUIDFromBytes(("deployment-management-v1\n" + task.id()).getBytes(StandardCharsets.UTF_8));
        var existing = find(task);
        if (existing.isPresent()) {
            var value = existing.orElseThrow();
            if (!value.applicationId().equals(application) || !value.server().equals(server)
                || !value.action().equals(action) || !value.currentRevision().equals(currentRevision)
                || !value.targetRevision().equals(targetRevision)
                || value.phase().equals("REVIEWED") && !value.facts().equals(facts))
                throw incompatible();
            return value;
        }
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var insert = connection.prepareStatement("INSERT INTO deployment_management_operation("
                 + "operation_id,platform_task_id,owner_mod_id,owner_mod_version,application_id,"
                 + "server_profile_id,action,current_release_sha256,target_release_sha256,phase,"
                 + "component_state,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'REVIEWED',?,?)")) {
            insert.setObject(1, id);
            insert.setObject(2, owner.id());
            insert.setString(3, owner.ownerId());
            insert.setString(4, owner.ownerVersion());
            insert.setString(5, application);
            insert.setString(6, server.id());
            insert.setString(7, action);
            insert.setString(8, currentRevision);
            insert.setString(9, targetRevision.orElse(null));
            insert.setBytes(10, payload);
            insert.setLong(11, Instant.now().toEpochMilli());
            insert.executeUpdate();
            return new Entry(id, owner.id(), owner.ownerId(), owner.ownerVersion(), application, server, action,
                currentRevision, targetRevision, "REVIEWED", facts.deepCopy());
        } catch (SQLException failure) {
            throw unknown("DEPLOYMENT_MANAGEMENT_BEGIN_UNKNOWN");
        }
    }

    /** Reads only a row owned by the exact currently authenticated original task. / 仅读取当前认证原任务所属的行。
     * @param task original task / 原任务
     * @return one original operation / 一项原操作
     * @throws ApiException if authorization or data cannot be checked / 授权或数据无法核实时 */
    public Optional<Entry> find(TaskContext task) throws ApiException {
        TaskSnapshot owner = context.tasks().inspect(task);
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var query = connection
                 .prepareStatement("SELECT * FROM deployment_management_operation WHERE platform_task_id=?")) {
            query.setObject(1, owner.id());
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                if (!owner.ownerId().equals(rows.getString("owner_mod_id"))
                    || !owner.ownerVersion().equals(rows.getString("owner_mod_version")))
                    throw denied();
                var entry = new Entry(rows.getObject("operation_id", UUID.class), owner.id(), owner.ownerId(),
                    owner.ownerVersion(), rows.getString("application_id"),
                    new ServerRef(rows.getString("server_profile_id")), rows.getString("action"),
                    rows.getString("current_release_sha256"),
                    Optional.ofNullable(rows.getString("target_release_sha256")), rows.getString("phase"),
                    parse(rows.getBytes("component_state")));
                if (rows.next())
                    throw incompatible();
                context.servers().get(entry.server(), task);
                return Optional.of(entry);
            }
        } catch (SQLException | IOException failure) {
            throw unknown("DEPLOYMENT_MANAGEMENT_READ_UNKNOWN");
        }
    }

    /** Advances exactly one durable phase before or after a verified step. / 在核实步骤前后仅推进一个持久阶段。
     * @param entry authenticated original row / 已认证原行
     * @param phase next phase / 下一阶段
     * @param facts full updated facts / 更新后的完整事实
     * @param task original task / 原任务
     * @return new view / 新视图
     * @throws ApiException if compare-and-set or commit is unknown / 比较更新或提交未知时 */
    public Entry advance(Entry entry, String phase, JsonNode facts, TaskContext task) throws ApiException {
        TaskSnapshot owner = context.tasks().inspect(task);
        if (!owner.id().equals(entry.taskId()) || !owner.ownerId().equals(entry.ownerId())
            || !owner.ownerVersion().equals(entry.ownerVersion()))
            throw denied();
        context.servers().get(entry.server(), task);
        byte[] payload = bytes(facts);
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var update = connection.prepareStatement("UPDATE deployment_management_operation SET phase=?,"
                 + "component_state=?,updated_at=? WHERE operation_id=? AND platform_task_id=? AND "
                 + "owner_mod_id=? AND owner_mod_version=? AND phase=?")) {
            update.setString(1, phase);
            update.setBytes(2, payload);
            update.setLong(3, Instant.now().toEpochMilli());
            update.setObject(4, entry.id());
            update.setObject(5, owner.id());
            update.setString(6, owner.ownerId());
            update.setString(7, owner.ownerVersion());
            update.setString(8, entry.phase());
            if (update.executeUpdate() != 1)
                throw incompatible();
            return new Entry(entry.id(), entry.taskId(), entry.ownerId(), entry.ownerVersion(), entry.applicationId(),
                entry.server(), entry.action(), entry.currentRevision(), entry.targetRevision(), phase,
                facts.deepCopy());
        } catch (SQLException failure) {
            throw unknown("DEPLOYMENT_MANAGEMENT_COMMIT_UNKNOWN");
        }
    }

    /** Starts a bounded mutable fact document. / 开始一份有界可变事实文档。
     * @return new format-one document / 新格式一文档 */
    public static ObjectNode facts() {
        return JSON.createObjectNode().put("formatVersion", 1);
    }

    /** Copies stored facts before an update. / 更新前复制持久事实。
     * @param entry original row / 原行
     * @return mutable copy / 可变副本 */
    public static ObjectNode copy(Entry entry) {
        return entry.facts().deepCopy();
    }

    /** Serializes exact bounded JSON. / 序列化精确有界JSON。
     * @param value complete original facts / 完整原事实
     * @return complete bounded JSON bytes / 完整有界JSON字节 */
    private static byte[] bytes(JsonNode value) {
        try {
            byte[] binary = JSON.writeValueAsBytes(value);
            if (!value.isObject() || binary.length > LIMIT || value.path("formatVersion").asInt() != 1)
                throw new IllegalArgumentException("Invalid management fact format");
            return binary;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid management facts", invalid);
        }
    }

    /** Parses one complete document without duplicate keys or trailing data. / 无重复键或尾随数据地解析完整文档。
     * @param binary bounded original stored facts / 有界原存储事实
     * @return complete original facts / 完整原事实
     * @throws IOException if original facts are absent, malformed or oversized / 原事实缺失、畸形或过大时 */
    private static JsonNode parse(byte[] binary) throws IOException {
        if (binary == null || binary.length > LIMIT)
            throw new IOException("Management facts invalid");
        try (var parser = JSON.createParser(binary)) {
            JsonNode value = JSON.readTree(parser);
            if (parser.nextToken() != null || value == null || !value.isObject()
                || value.path("formatVersion").asInt() != 1)
                throw new IOException("Management facts invalid");
            return value;
        }
    }

    /** Rejects access to another original caller. / 拒绝访问另一原调用者。
     * @return bounded original authorization failure / 有界原授权失败 */
    private static ApiException denied() {
        return problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_MANAGEMENT_OWNER");
    }

    /** Rejects changed original intent or phase. / 拒绝变更的原意图或阶段。
     * @return bounded original identity conflict / 有界原身份冲突 */
    private static ApiException incompatible() {
        return problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_MANAGEMENT_CHANGED");
    }

    /** Retains an uncertain journal outcome. / 保留未知日志结果。
     * @param event stable uncertainty identity / 稳定未知身份
     * @return bounded original uncertainty / 有界原未知 */
    private static ApiException unknown(String event) {
        return problem(ProblemCode.UNKNOWN, event);
    }

    /** Creates one bounded nonsecret problem. / 创建一项有界无秘密问题。
     * @param code precise original classification / 精确原分类
     * @param event stable original evidence failure / 稳定原依据失败
     * @return bounded original evidence exception / 有界原依据异常 */
    private static ApiException problem(ProblemCode code, String event) {
        return new ApiException(new Problem(code, event, "Management operation requires original-task review", Map.of(),
            Optional.empty()));
    }
}
