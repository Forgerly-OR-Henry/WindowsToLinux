package gold.debug.windowstolinux.mod.deployment.service.core.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;

/** Journals an original management task before any remote effect and keeps uncertain phases. / 在远端副作用前记录原管理任务，并保留未知阶段。 */
public class DeploymentManagementActionLog {
    /** Strict original management evidence JSON without duplicate fields. / 无重复字段的严格原管理依据JSON。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Complete original component evidence byte bound. / 完整原组件依据字节界限。 */
    private static final int LIMIT = 1_048_576;

    /** Exact provider Schema and task authentication capabilities. / 精确提供方Schema及任务认证能力。 */
    private final ModContext context;

    /** Uses only the deployment-owned database and host-certified task. / 仅使用部署所属数据库和宿主认证任务。
     * @param context original Mod context / 原Mod上下文 */
    public DeploymentManagementActionLog(ModContext context) {
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
                        ServerId server, String action, String currentRevision, Optional<String> targetRevision,
                        String phase,
                        JsonNode facts) {
    }

    /** Captured independent management review; never an approval or a reusable grant. */
    public record ReviewPolicy(String configurationRevision) {
        public ReviewPolicy {
            if (configurationRevision == null || !configurationRevision.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid management model revision");
        }
    }

    public static Optional<ReviewPolicy> review(Entry entry) {
        return version(entry.facts()) == 1 ? Optional.empty()
            : Optional.of(new ReviewPolicy(entry.facts().path("review").path("configurationRevision").textValue()));
    }

    public static Optional<String> selected(Entry entry) {
        return version(entry.facts()) == 1 || entry.facts().path("selectedComponentId").isNull()
            ? Optional.empty() : Optional.of(entry.facts().path("selectedComponentId").textValue());
    }

    public static ObjectNode facts(ReviewPolicy review, Optional<String> selected) {
        var value = JSON.createObjectNode().put("formatVersion", 2)
            .put("scope", selected.isPresent() ? "SINGLE_COMPONENT" : "WHOLE_APPLICATION");
        if (selected.isPresent()) value.put("selectedComponentId", selected.orElseThrow());
        else value.putNull("selectedComponentId");
        value.putObject("review").put("approval", "AUTOMATIC").put("rememberAuthorization", false)
            .put("configurationRevision", review.configurationRevision());
        return value;
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
    public Entry begin(String application, ServerId server, String action, String currentRevision,
                       Optional<String> targetRevision, JsonNode facts, TaskContext task) throws ApiException {
        TaskSnapshot owner = context.tasks().inspect(task);
        context.servers().get(server, task);
        byte[] payload = bytes(facts);
        validateIntent(server, action, targetRevision, facts, task);
        UUID id = UUID.nameUUIDFromBytes(("deployment-management-v1\n" + task.id()).getBytes(StandardCharsets.UTF_8));
        var existing = find(task);
        if (existing.isPresent()) {
            var value = existing.orElseThrow();
            if (!value.applicationId().equals(application) || !value.server().equals(server)
                || !value.action().equals(action) || !value.currentRevision().equals(currentRevision)
                || !value.targetRevision().equals(targetRevision)
                || version(value.facts()) != version(facts)
                || version(facts) == 2 && !immutable(value.facts()).equals(immutable(facts))
                || value.phase().equals("REVIEWED") && !value.facts().equals(facts))
                throw incompatible();
            return value;
        }
        DeploymentSchemaIdentity.requireIdentity(context.db().identity());
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
     * @throws ApiException if rememberAuthorization or data cannot be checked / 授权或数据无法核实时 */
    public Optional<Entry> find(TaskContext task) throws ApiException {
        TaskSnapshot owner = context.tasks().inspect(task);
        DeploymentSchemaIdentity.requireIdentity(context.db().identity());
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
                    new ServerId(rows.getString("server_profile_id")), rows.getString("action"),
                    rows.getString("current_release_sha256"),
                    Optional.ofNullable(rows.getString("target_release_sha256")), rows.getString("phase"),
                    parse(rows.getBytes("component_state")));
                if (rows.next())
                    throw incompatible();
                validateIntent(entry.server(), entry.action(), entry.targetRevision(), entry.facts(), task);
                context.servers().get(entry.server(), task);
                return Optional.of(entry);
            }
        } catch (SQLException | IOException | IllegalArgumentException failure) {
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
        validateIntent(entry.server(), entry.action(), entry.targetRevision(), facts, task);
        if (entry.facts().has("lifecycleCleanup")) {
            var before = copy(entry); before.remove("lifecycleCleanup");
            var after = (ObjectNode) facts.deepCopy(); after.remove("lifecycleCleanup");
            if (!before.equals(after)) throw incompatible();
            DeploymentLifecycleCleanupLog.requireRetained(entry, facts);
        }
        if (version(entry.facts()) != version(facts)
            || version(facts) == 2 && !immutable(entry.facts()).equals(immutable(facts)))
            throw incompatible();
        if (version(facts) == 2) {
            var stored = find(task).orElseThrow(DeploymentManagementActionLog::incompatible);
            if (!stored.id().equals(entry.id()) || !stored.applicationId().equals(entry.applicationId())
                || !stored.server().equals(entry.server()) || !stored.action().equals(entry.action())
                || !stored.currentRevision().equals(entry.currentRevision()) || !stored.targetRevision().equals(entry.targetRevision())
                || !stored.phase().equals(entry.phase()) || !stored.facts().equals(entry.facts())
                || !Set.of("REVIEWED", "EFFECTING", "UNKNOWN", "FINISHED").contains(phase))
                throw incompatible();
            requireProgress(entry, phase, facts);
        }
        DeploymentSchemaIdentity.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var update = connection.prepareStatement("UPDATE deployment_management_operation SET phase=?,"
                 + "component_state=?,updated_at=? WHERE operation_id=? AND platform_task_id=? AND "
                 + "owner_mod_id=? AND owner_mod_version=? AND phase=?"
                 + (version(facts) == 2 ? " AND application_id=? AND server_profile_id=? AND action=?"
                     + " AND current_release_sha256=? AND target_release_sha256 IS NULL AND component_state=?" : " AND component_state=?"))) {
            update.setString(1, phase);
            update.setBytes(2, payload);
            update.setLong(3, Instant.now().toEpochMilli());
            update.setObject(4, entry.id());
            update.setObject(5, owner.id());
            update.setString(6, owner.ownerId());
            update.setString(7, owner.ownerVersion());
            update.setString(8, entry.phase());
            {
                byte[] originalPayload;
                try (var query = connection.prepareStatement("SELECT component_state FROM deployment_management_operation"
                    + " WHERE operation_id=? AND platform_task_id=? AND owner_mod_id=? AND owner_mod_version=? AND phase=?")) {
                    query.setObject(1, entry.id()); query.setObject(2, owner.id()); query.setString(3, owner.ownerId());
                    query.setString(4, owner.ownerVersion()); query.setString(5, entry.phase());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) throw incompatible();
                        originalPayload = rows.getBytes(1);
                        if (!parse(originalPayload).equals(entry.facts()) || rows.next()) throw incompatible();
                    }
                }
                if (version(facts) == 2) {
                    update.setString(9, entry.applicationId()); update.setString(10, entry.server().id());
                    update.setString(11, entry.action()); update.setString(12, entry.currentRevision());
                    update.setBytes(13, originalPayload);
                } else update.setBytes(9, originalPayload);
            }
            if (update.executeUpdate() != 1)
                throw incompatible();
            return new Entry(entry.id(), entry.taskId(), entry.ownerId(), entry.ownerVersion(), entry.applicationId(),
                entry.server(), entry.action(), entry.currentRevision(), entry.targetRevision(), phase,
                facts.deepCopy());
        } catch (SQLException | IOException failure) {
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

    /** Records the actual authenticated original target without credentials. / 不含凭据地记录实际已认证原目标。
     * @param facts original mutable before facts / 原可变前态事实
     * @param target actual original task-bound endpoint and wire fingerprint / 实际原任务绑定端点与wire指纹 */
    public static void target(ObjectNode facts, RemoteTarget target) {
        var value = facts.putObject("target");
        value.put("server", target.server().id()).put("taskId", target.taskId().toString())
            .put("host", target.host()).put("port", target.port()).put("username", target.username())
            .put("fingerprint", target.fingerprint().orElseThrow());
    }

    /** Reads only a complete original connection expectation; old missing facts remain unknown. / 仅读取完整原连接预期；旧缺失事实保持未知。
     * @param entry original authenticated operation / 原已认证操作
     * @param task authentic original task or recovery / 真实原任务或恢复
     * @return fixed original endpoint and authenticated wire key / 固定原端点与已认证wire密钥
     * @throws ApiException if original facts or identity cannot be confirmed / 原事实或身份无法确认时 */
    public static RemoteTarget target(Entry entry, TaskContext task) throws ApiException {
        var value = entry.facts().path("target");
        try {
            for (String key : java.util.List.of("server", "taskId", "host", "username", "fingerprint"))
                if (!value.path(key).isTextual())
                    throw new IllegalArgumentException("Original target fields unavailable");
            if (!entry.taskId().equals(task.id()) || !value.isObject() || !value.path("port").isInt()
                || !entry.server().id().equals(value.path("server").asText())
                || !task.id().toString().equals(value.path("taskId").asText()))
                throw new IllegalArgumentException("Original target identity unavailable");
            return new RemoteTarget(entry.server(), task.id(), value.path("host").asText(), value.path("port").intValue(),
                value.path("username").asText(), Optional.of(value.path("fingerprint").asText()));
        } catch (IllegalArgumentException invalid) {
            throw unknown("DEPLOYMENT_LIFECYCLE_ORIGINAL_TARGET_UNCONFIRMED");
        }
    }

    /** Serializes exact bounded JSON. / 序列化精确有界JSON。
     * @param value complete original facts / 完整原事实
     * @return complete bounded JSON bytes / 完整有界JSON字节 */
    private static byte[] bytes(JsonNode value) {
        try {
            byte[] binary = JSON.writeValueAsBytes(value);
            if (binary.length > LIMIT)
                throw new IllegalArgumentException("Invalid management fact format");
            validate(value);
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
            if (parser.nextToken() != null || value == null)
                throw new IOException("Management facts invalid");
            try { validate(value); }
            catch (IllegalArgumentException invalid) { throw new IOException("Management facts invalid", invalid); }
            return value;
        }
    }

    private static int version(JsonNode value) {
        if (!value.isObject() || !value.path("formatVersion").isInt()
            || !Set.of(1, 2).contains(value.path("formatVersion").intValue()))
            throw new IllegalArgumentException("Invalid management fact version");
        return value.path("formatVersion").intValue();
    }

    private static void fields(JsonNode value, Set<String> required, Set<String> optional) {
        if (!value.isObject()) throw new IllegalArgumentException("Management object required");
        var names = new java.util.HashSet<String>();
        value.fieldNames().forEachRemaining(names::add);
        if (!names.containsAll(required) || names.stream().anyMatch(key -> !required.contains(key) && !optional.contains(key)))
            throw new IllegalArgumentException("Management fields differ");
    }

    private static String text(JsonNode value, String key) {
        var field = value.path(key);
        if (!field.isTextual() || field.textValue().isBlank())
            throw new IllegalArgumentException("Management text missing");
        return field.textValue();
    }

    private static void digest(JsonNode value, String key) {
        if (!text(value, key).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Management digest invalid");
    }

    private static void validate(JsonNode value) {
        if (version(value) == 1) return;
        fields(value, Set.of("formatVersion", "scope", "selectedComponentId", "review", "applicationOwnershipSha256",
            "target", "before", "components"), Set.of("originalTargetUnconfirmed", "finalObservationOutcome", "final",
            "reconciledReadOnly", "partialStopVerified", "interrupted", "currentReleaseUnconfirmed", "effectOutcome", "lifecycleCleanup"));
        String scope = text(value, "scope");
        if (!Set.of("WHOLE_APPLICATION", "SINGLE_COMPONENT").contains(scope)
            || scope.equals("WHOLE_APPLICATION") != value.path("selectedComponentId").isNull())
            throw new IllegalArgumentException("Management scope invalid");
        var review = value.path("review");
        fields(review, Set.of("approval", "rememberAuthorization", "configurationRevision"), Set.of());
        if (!text(review, "approval").equals("AUTOMATIC") || !review.path("rememberAuthorization").isBoolean()
            || review.path("rememberAuthorization").booleanValue())
            throw new IllegalArgumentException("Management review invalid");
        digest(review, "configurationRevision");
        digest(value, "applicationOwnershipSha256");
        gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState.valueOf(text(value, "before"));
        var target = value.path("target");
        fields(target, Set.of("server", "taskId", "host", "port", "username", "fingerprint"), Set.of());
        if (!target.path("port").isInt()) throw new IllegalArgumentException("Management target port invalid");
        new gold.debug.windowstolinux.appapi.linux.session.RemoteTarget(new ServerId(text(target, "server")),
            UUID.fromString(text(target, "taskId")), text(target, "host"), target.path("port").intValue(),
            text(target, "username"), Optional.of(text(target, "fingerprint")));
        var components = value.path("components");
        if (!components.isObject() || components.isEmpty() || components.size() > 1000)
            throw new IllegalArgumentException("Management components invalid");
        var names = components.fieldNames();
        var units = new java.util.HashSet<String>();
        while (names.hasNext()) {
            String id = names.next();
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("Management component invalid");
            var part = components.path(id);
            fields(part, Set.of("unitId", "unitOwnershipSha256", "systemdUnit", "releaseRoot", "releaseSha256",
                "beforeRaw", "before", "beforeAutostart", "attempted", "verified", "verb"), Set.of());
            if (!text(part, "unitId").matches("[a-z0-9][a-z0-9-]{0,62}") || !units.add(text(part, "unitId")))
                throw new IllegalArgumentException("Management unit invalid");
            text(part, "systemdUnit"); text(part, "releaseRoot");
            digest(part, "unitOwnershipSha256"); digest(part, "releaseSha256");
            gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentRemoteRuntimeObservation.StateType.valueOf(text(part, "beforeRaw"));
            gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState.valueOf(text(part, "before"));
            gold.debug.windowstolinux.mod.deployment.api.ApplicationAutostartState.valueOf(text(part, "beforeAutostart"));
            if (!part.path("attempted").isBoolean() || !part.path("verified").isBoolean()
                || part.path("verified").booleanValue() && !part.path("attempted").booleanValue()
                || part.path("attempted").booleanValue() == part.path("verb").isNull())
                throw new IllegalArgumentException("Management progress invalid");
            if (part.path("attempted").booleanValue()
                && !Set.of("start", "stop", "enable", "disable").contains(text(part, "verb")))
                throw new IllegalArgumentException("Management verb invalid");
            if (scope.equals("SINGLE_COMPONENT") && !id.equals(value.path("selectedComponentId").textValue())
                && (part.path("attempted").booleanValue() || part.path("verified").booleanValue() || !part.path("verb").isNull()))
                throw new IllegalArgumentException("Unselected management effect");
        }
        if (scope.equals("SINGLE_COMPONENT") && (!value.path("selectedComponentId").isTextual()
            || !components.has(text(value, "selectedComponentId"))))
            throw new IllegalArgumentException("Management selection missing");
        for (String key : Set.of("originalTargetUnconfirmed", "reconciledReadOnly", "partialStopVerified", "interrupted", "currentReleaseUnconfirmed"))
            if (value.has(key) && !value.path(key).isBoolean()) throw new IllegalArgumentException("Management flag invalid");
        for (String key : Set.of("effectOutcome", "finalObservationOutcome"))
            if (value.has(key)) gold.debug.windowstolinux.appapi.task.result.TaskResult.valueOf(text(value, key));
        if (value.has("final")) gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState.valueOf(text(value, "final"));
    }

    static void validateLifecycleEntry(Entry entry, TaskContext task) {
        validate(entry.facts());
        validateIntent(entry.server(), entry.action(), entry.targetRevision(), entry.facts(), task);
    }

    static void requireProgress(Entry entry, String phase, JsonNode facts) throws ApiException {
        if (!Set.of("REVIEWED", "EFFECTING", "UNKNOWN", "FINISHED").contains(phase)
            || version(facts) != 2 || !immutable(entry.facts()).equals(immutable(facts))) throw incompatible();
        var names = entry.facts().path("components").fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            var before = entry.facts().path("components").path(name);
            var after = facts.path("components").path(name);
            boolean nextRestartPhase = entry.action().equals("LIFECYCLE_RESTART")
                && before.path("verb").asText().equals("stop") && after.path("verb").asText().equals("start");
            if (before.path("attempted").booleanValue() && !after.path("attempted").booleanValue()
                || before.path("verified").booleanValue() && !after.path("verified").booleanValue() && !nextRestartPhase
                || before.path("verb").isTextual() && !before.path("verb").equals(after.path("verb")) && !nextRestartPhase)
                throw incompatible();
        }
    }

    private static void validateIntent(ServerId server, String action, Optional<String> targetRevision,
                                       JsonNode facts, TaskContext task) {
        if (version(facts) == 1) return;
        if (!targetRevision.isEmpty() || !action.startsWith("LIFECYCLE_")
            || !server.id().equals(facts.path("target").path("server").textValue())
            || !task.id().toString().equals(facts.path("target").path("taskId").textValue()))
            throw new IllegalArgumentException("Management original intent differs");
        var lifecycle = gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction.valueOf(action.substring(10));
        if (facts.path("scope").textValue().equals("SINGLE_COMPONENT")
            && lifecycle != gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction.START
            && lifecycle != gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction.STOP)
            throw new IllegalArgumentException("Unsupported component action");
        for (var part : facts.path("components"))
            if (part.path("attempted").booleanValue() && !(lifecycle == gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction.RESTART
                ? Set.of("stop", "start").contains(part.path("verb").textValue()) : lifecycle.verb().equals(part.path("verb").textValue())))
                throw new IllegalArgumentException("Management action verb differs");
    }

    static JsonNode immutable(JsonNode value) {
        var original = value.deepCopy();
        var result = (ObjectNode) original;
        result.remove(java.util.List.of("originalTargetUnconfirmed", "finalObservationOutcome", "final", "reconciledReadOnly",
            "partialStopVerified", "interrupted", "currentReleaseUnconfirmed", "effectOutcome", "lifecycleCleanup"));
        result.path("components").forEach(part -> ((ObjectNode) part).remove(java.util.List.of("attempted", "verified", "verb")));
        return result;
    }

    /** Rejects access to another original caller. / 拒绝访问另一原调用者。
     * @return bounded original rememberAuthorization failure / 有界原授权失败 */
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
