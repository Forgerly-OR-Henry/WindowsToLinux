package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;

/** Stores original caller ownership, review and recovery facts in deployment's own Schema. / 在部署自身Schema中保存原调用归属、审阅与恢复事实。 */
public final class DeploymentHandoffLedger {
    /** Strict bounded original request and recovery JSON. / 严格有界原请求及恢复JSON。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Complete archive framing bound including encoded runtime bytes. / 含编码运行字节的完整归档信封界限。 */
    private static final int LIMIT = 24 * 1_048_576;

    /** Exact provider capabilities used within the original call. / 在原调用内使用的精确提供方能力。 */
    private final ModContext context;

    /** Binds exact deployment data and host-verified task scope. / 绑定精确部署数据与宿主核实任务范围。
     * @param context active deployment context / 活动部署上下文 */
    public DeploymentHandoffLedger(ModContext context) {
        this.context = java.util.Objects.requireNonNull(context);
    }

    /** Original durable handoff plus its bounded private facts. / 原持久交接及有界私有事实。
     * @param publicValue public handoff / 公开交接
     * @param ownerVersion original caller version / 原调用版本
     * @param taskId original task / 原任务
     * @param target optional target / 可选目标
     * @param request immutable reviewed request / 不可变已审阅请求
     * @param recovery original recovery facts / 原恢复事实
     * @param result optional operation result / 可选操作结果 */
    public record Entry(DeploymentHandoff publicValue, String ownerVersion, UUID taskId, Optional<ServerId> target,
                        JsonNode request, JsonNode recovery, Optional<JsonNode> result) {
    }

    /** Creates one deterministic collection review with no remote effect. / 创建一次确定身份且无远端副作用的采集审阅。
     * @param application exact source / 精确来源
     * @param revision reviewed release / 已审阅发布
     * @param bindingIds selected existing bindings / 所选既有绑定
     * @param task authentic original task / 真实原任务
     * @return retained handoff / 留存交接
     * @throws ApiException if original identity or commit is unavailable / 原身份或提交不可用时 */
    public DeploymentHandoff reviewCollection(DeploymentApplicationId application, String revision,
                                              List<String> bindingIds, TaskContext task) throws ApiException {
        TaskSnapshot caller = caller(task);
        var request = JSON.createObjectNode().put("formatVersion", 1).put("kind", "COLLECTION").put("ownerVersion",
            caller.ownerVersion());
        var selected = request.putArray("bindings");
        bindingIds.stream().sorted().forEach(selected::add);
        var recovery = JSON.createObjectNode().put("formatVersion", 1).put("before", "UNKNOWN").put("changed", false)
            .put("operation", "backup-" + token(task, application, "collection"));
        return create(application, revision, Optional.empty(), request, recovery, task);
    }

    /** Creates a target preparation intent before any upload or remote candidate effect. / 在上传或远端候选副作用前创建目标准备意图。
     * @param request exact reviewed archive and target / 精确已审阅归档与目标
     * @param task authentic original task / 真实原任务
     * @return retained handoff / 留存交接
     * @throws ApiException if original identity or commit is unavailable / 原身份或提交不可用时 */
    public DeploymentHandoff beginRestore(DeploymentRestoreRequest request, TaskContext task) throws ApiException {
        var archived = request.archivedApplication();
        var body = restoreRequest(request, task);
        var recovery = JSON.createObjectNode().put("formatVersion", 1).put("before", "UNKNOWN").put("changed", false);
        return create(archived.application(), archived.revisionSha256(), Optional.of(request.target()), body, recovery,
            task);
    }

    /** Reads only the original restore intent after an uncertain call, without reopening files or creating candidates. / 未知调用后仅读取原恢复意图，不重新打开文件或创建候选。
     * @param request exact original immutable request / 精确原不可变请求
     * @param task authentic original task / 真实原任务
     * @return original handoff or absence / 原交接或不存在
     * @throws ApiException if original request or caller differs / 原请求或调用者不符时 */
    public Optional<Entry> findRestore(DeploymentRestoreRequest request, TaskContext task) throws ApiException {
        var archived = request.archivedApplication();
        var body = restoreRequest(request, task);
        var existing = find(identity(caller(task), "RESTORE", archived.application()), task);
        if (existing.isPresent())
            requireRequest(existing.orElseThrow(), archived.application(), archived.revisionSha256(),
                Optional.of(request.target()), body);
        return existing;
    }

    /** Encodes the complete immutable original restore request. / 编码完整不可变原恢复请求。
     * @param request complete original archived input and target review / 完整原归档输入及目标审阅
     * @param task host-certified original caller task / 宿主认证原调用方任务
     * @return complete immutable original request facts / 完整不可变原请求事实
     * @throws ApiException if canonical archive or original authorization differs / 规范归档或原授权不符时 */
    private ObjectNode restoreRequest(DeploymentRestoreRequest request, TaskContext task) throws ApiException {
        var archived = request.archivedApplication();
        try {
            DeploymentArchivedRuntimeCodec.verify(archived, new DeploymentReleaseStore(context.db()));
        } catch (IOException invalid) {
            throw invalid("DEPLOYMENT_RESTORE_ARCHIVE_INVALID");
        }
        TaskSnapshot caller = caller(task);
        var body = JSON.createObjectNode().put("formatVersion", 1).put("kind", "RESTORE")
            .put("ownerVersion", caller.ownerVersion()).put("archiveSha256", request.archiveSha256())
            .put("targetReviewSha256", request.reviewedTargetSha256())
            .put("archivedOwnershipSha256", archived.ownershipSha256());
        var snapshot = body.putObject("archivedSnapshot").put("applicationId", archived.application().id())
            .put("serverId", archived.application().server().id()).put("displayName", archived.displayName())
            .put("revisionSha256", archived.revisionSha256()).put("ownershipSha256", archived.ownershipSha256())
            .put("state", archived.state().name()).put("observedAt", archived.observedAt().toString())
            .put("runtimeVersion", archived.runtime().formatVersion())
            .put("runtimeBase64", Base64.getEncoder().encodeToString(archived.runtime().payload()))
            .put("healthPolicySha256", archived.healthPolicySha256());
        var bindings = snapshot.putArray("bindings");
        for (var binding : archived.bindings())
            bindings.addObject().put("id", binding.id()).put("componentId", binding.componentId())
                .put("kind", binding.kind()).put("path", binding.path()).put("engine", binding.engine())
                .put("needsQuiescence", binding.needsQuiescence())
                .put("secretReferenceId", binding.secretReferenceId().orElse(null));
        var files = body.putArray("files");
        for (var file : request.files())
            files.addObject().put("bindingId", file.bindingId()).put("path", file.verifiedLocalFile().toString())
                .put("size", file.byteCount()).put("sha256", file.sha256());
        return body;
    }

    /** Reads a handoff only through its original host-authenticated caller and task. / 仅通过原宿主认证的调用者及任务读取交接。
     * @param id original handoff / 原交接
     * @param task authentic original task / 真实原任务
     * @return original row or absence / 原行或不存在
     * @throws ApiException if access or stored facts cannot be confirmed / 访问或存储事实无法确认时 */
    public Optional<Entry> find(UUID id, TaskContext task) throws ApiException {
        TaskSnapshot caller = caller(task);
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement("SELECT * FROM deployment_handoff WHERE handoff_id=?")) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                if (!caller.id().equals(rows.getObject("platform_task_id", UUID.class))
                    || !caller.ownerId().equals(rows.getString("owner_backup_mod")))
                    throw denied();
                var request = parse(rows.getBinaryStream("request_payload"));
                if (!caller.ownerVersion().equals(request.path("ownerVersion").asText()))
                    throw denied();
                var recovery = parse(rows.getBinaryStream("recovery_payload"));
                String targetId = rows.getString("target_server_id");
                var target = targetId == null ? Optional.<ServerId>empty() : Optional.of(new ServerId(targetId));
                var application = new DeploymentApplicationId(rows.getString("application_id"),
                    new ServerId(rows.getString("source_server_id")));
                if (request.path("kind").asText().equals("COLLECTION"))
                    context.servers().get(application.server(), task);
                else if (request.path("kind").asText().equals("RESTORE") && target.isPresent())
                    context.servers().get(target.orElseThrow(), task);
                else
                    throw invalid("DEPLOYMENT_HANDOFF_KIND_INVALID");
                var publicValue = new DeploymentHandoff(id, DeploymentHandoffState.valueOf(rows.getString("state")),
                    application, rows.getString("revision_sha256"), blockers(recovery));
                var result = rows.getBinaryStream("result_payload");
                return Optional.of(new Entry(publicValue, caller.ownerVersion(), caller.id(), target, request, recovery,
                    result == null ? Optional.empty() : Optional.of(parse(result))));
            }
        } catch (SQLException | IOException | IllegalArgumentException failure) {
            throw invalid("DEPLOYMENT_HANDOFF_READ_FAILED");
        }
    }

    /** Moves only the original expected phase and retains exact recovery facts. / 仅推进原预期阶段并保留精确恢复事实。
     * @param entry original authorized row / 原已授权行
     * @param expected required previous phase / 必需前一阶段
     * @param next next durable phase / 下一持久阶段
     * @param recovery updated original recovery facts / 更新后的原恢复事实
     * @param result optional result facts / 可选结果事实
     * @param task authentic original task / 真实原任务
     * @return resulting public handoff / 结果公开交接
     * @throws ApiException if compare-and-set commit is unknown / 比较更新提交未知时 */
    public DeploymentHandoff advance(Entry entry, DeploymentHandoffState expected, DeploymentHandoffState next,
                                     JsonNode recovery, Optional<JsonNode> result, TaskContext task) throws ApiException {
        require(entry, task);
        byte[] recoveryBytes = bytes(recovery);
        byte[] resultBytes = result.map(DeploymentHandoffLedger::bytes).orElse(null);
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var update = connection.prepareStatement(
                 "UPDATE deployment_handoff SET state=?,recovery_payload=?,result_payload=?,updated_at=? WHERE handoff_id=? AND state=? AND platform_task_id=? AND owner_backup_mod=? AND revision_sha256=?")) {
            update.setString(1, next.name());
            update.setBytes(2, recoveryBytes);
            update.setBytes(3, resultBytes);
            update.setLong(4, Instant.now().toEpochMilli());
            update.setObject(5, entry.publicValue().id());
            update.setString(6, expected.name());
            update.setObject(7, task.id());
            update.setString(8, caller(task).ownerId());
            update.setString(9, entry.publicValue().revisionSha256());
            if (update.executeUpdate() != 1)
                throw invalid("DEPLOYMENT_HANDOFF_PHASE_CHANGED");
            return new DeploymentHandoff(entry.publicValue().id(), next, entry.publicValue().application(),
                entry.publicValue().revisionSha256(), blockers(recovery));
        } catch (SQLException failure) {
            throw invalid("DEPLOYMENT_HANDOFF_COMMIT_UNKNOWN");
        }
    }

    /** Returns an independent mutable copy of original private recovery facts. / 返回原私有恢复事实的独立可变副本。
     * @param entry original row / 原行
     * @return mutable copy / 可变副本 */
    public ObjectNode recovery(Entry entry) {
        return entry.recovery().deepCopy();
    }

    /** Inserts a reviewed immutable request and original caller identity once. / 一次性插入已审阅不可变请求及原调用身份。
     * @param application original application identity / 原应用身份
     * @param revision complete original release digest / 完整原发布摘要
     * @param target actual restore target or absence / 实际恢复目标或无
     * @param request complete immutable original intent / 完整不可变原意图
     * @param recovery initial private recovery facts / 初始私有恢复事实
     * @param task host-certified original caller task / 宿主认证原调用方任务
     * @return original durable handoff / 原持久交接
     * @throws ApiException if original authorization or commit fails / 原授权或提交失败时 */
    private DeploymentHandoff create(DeploymentApplicationId application, String revision, Optional<ServerId> target,
                                     ObjectNode request, ObjectNode recovery, TaskContext task) throws ApiException {
        TaskSnapshot owner = caller(task);
        if (!revision.matches("[0-9a-f]{64}"))
            throw invalid("DEPLOYMENT_HANDOFF_REVISION_INVALID");
        if (request.path("kind").asText().equals("COLLECTION"))
            context.servers().get(application.server(), task);
        else if (request.path("kind").asText().equals("RESTORE") && target.isPresent())
            context.servers().get(target.orElseThrow(), task);
        else
            throw invalid("DEPLOYMENT_HANDOFF_KIND_INVALID");
        UUID id = identity(owner, request.path("kind").asText(), application);
        var existing = find(id, task);
        if (existing.isPresent()) {
            requireRequest(existing.orElseThrow(), application, revision, target, request);
            return existing.orElseThrow().publicValue();
        }
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var insert = connection.prepareStatement(
                 "INSERT INTO deployment_handoff(handoff_id,owner_backup_mod,platform_task_id,application_id,source_server_id,target_server_id,revision_sha256,state,request_payload,recovery_payload,updated_at) VALUES(?,?,?,?,?,?,?,'REVIEWED',?,?,?)")) {
            insert.setObject(1, id);
            insert.setString(2, owner.ownerId());
            insert.setObject(3, owner.id());
            insert.setString(4, application.id());
            insert.setString(5, application.server().id());
            insert.setString(6, target.map(ServerId::id).orElse(null));
            insert.setString(7, revision);
            insert.setBytes(8, bytes(request));
            insert.setBytes(9, bytes(recovery));
            insert.setLong(10, Instant.now().toEpochMilli());
            insert.executeUpdate();
            return new DeploymentHandoff(id, DeploymentHandoffState.REVIEWED, application, revision, List.of());
        } catch (SQLException failure) {
            throw invalid("DEPLOYMENT_HANDOFF_CREATE_UNKNOWN");
        }
    }

    /** Derives the private identity of the one original task intent. / 推导唯一原任务意图的私有身份。
     * @param owner host-certified original caller / 宿主认证原调用方
     * @param kind original collection or restore intent / 原采集或恢复意图
     * @param application exact original application / 精确原应用
     * @return stable original intent identity / 稳定原意图身份 */
    private static UUID identity(TaskSnapshot owner, String kind, DeploymentApplicationId application) {
        return UUID.nameUUIDFromBytes(("deployment-handoff-v1\n" + owner.id() + "\n" + kind + "\n" + application.id())
            .getBytes(StandardCharsets.UTF_8));
    }

    /** Rejects replacing any part of the original persisted request. / 拒绝替换原持久请求的任何部分。
     * @param existing authenticated original handoff / 已认证原交接
     * @param application exact original application / 精确原应用
     * @param revision complete original release digest / 完整原发布摘要
     * @param target actual original restore target or absence / 实际原恢复目标或无
     * @param request complete immutable original intent / 完整不可变原意图
     * @throws ApiException if any original request fact differs / 任何原请求事实不符时 */
    private static void requireRequest(Entry existing, DeploymentApplicationId application, String revision,
                                       Optional<ServerId> target, ObjectNode request) throws ApiException {
        final JsonNode canonical;
        try {
            canonical = JSON.readTree(request.toString());
        } catch (IOException invalidRequest) {
            throw invalid("DEPLOYMENT_HANDOFF_REQUEST_INVALID");
        }
        if (!existing.publicValue().application().equals(application)
            || !existing.publicValue().revisionSha256().equals(revision) || !existing.target().equals(target)
            || !existing.request().equals(canonical))
            throw invalid("DEPLOYMENT_HANDOFF_REQUEST_CHANGED");
    }

    /** Verifies original task and owner before mutating a loaded row. / 修改已加载行前核实原任务和所有者。
     * @param entry authenticated original handoff / 已认证原交接
     * @param task host-certified original caller task / 宿主认证原调用方任务
     * @throws ApiException if original identity or endpoint authorization differs / 原身份或端点授权不符时 */
    private void require(Entry entry, TaskContext task) throws ApiException {
        var owner = caller(task);
        if (!owner.id().equals(entry.taskId()) || !owner.ownerVersion().equals(entry.ownerVersion()))
            throw denied();
        if (entry.request().path("kind").asText().equals("COLLECTION"))
            context.servers().get(entry.publicValue().application().server(), task);
        else if (entry.request().path("kind").asText().equals("RESTORE") && entry.target().isPresent())
            context.servers().get(entry.target().orElseThrow(), task);
        else
            throw invalid("DEPLOYMENT_HANDOFF_KIND_INVALID");
    }

    /** Reads host-certified root caller identity, never a caller-supplied string. / 读取宿主认证的根调用身份，绝不接受调用方自报字符串。
     * @param task original task in the current provider scope / 当前提供方范围内的原任务
     * @return exact host-certified caller / 精确宿主认证调用方
     * @throws ApiException if original task scope is unavailable / 原任务范围不可用时 */
    private TaskSnapshot caller(TaskContext task) throws ApiException {
        return context.tasks().inspect(task);
    }

    /** Serializes bounded JSON without encoding arbitrary private entities. / 序列化有界JSON而不编码任意私有实体。
     * @param node complete original document / 完整原文档
     * @return complete bounded JSON bytes / 完整有界JSON字节 */
    private static byte[] bytes(JsonNode node) {
        try {
            byte[] value = JSON.writeValueAsBytes(node);
            if (value.length > LIMIT)
                throw new IllegalArgumentException("Handoff exceeds bound");
            return value;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid handoff JSON", failure);
        }
    }

    /** Parses only the original bounded owner document. / 仅解析原有界所属文档。
     * @param stream exact original stored payload / 精确原存储载荷
     * @return complete original document / 完整原文档
     * @throws IOException if payload is absent, malformed or oversized / 载荷缺失、畸形或过大时 */
    private static JsonNode parse(java.io.InputStream stream) throws IOException {
        if (stream == null)
            throw new IOException("Handoff payload absent");
        try (stream) {
            byte[] value = stream.readNBytes(LIMIT + 1);
            if (value.length > LIMIT)
                throw new IOException("Handoff payload exceeds bound");
            JsonNode node;
            try (var parser = JSON.createParser(value)) {
                node = JSON.readTree(parser);
                if (parser.nextToken() != null)
                    throw new IOException("Trailing handoff data");
            }
            if (!node.isObject() || node.path("formatVersion").asInt() != 1)
                throw new IOException("Unsupported handoff format");
            return node;
        }
    }

    /** Returns persisted recovery blockers without silently clearing them. / 返回持久恢复阻碍且不静默清除。
     * @param recovery original private recovery facts / 原私有恢复事实
     * @return complete original blocker list / 完整原阻碍列表 */
    private static List<String> blockers(JsonNode recovery) {
        var result = new ArrayList<String>();
        var source = recovery.path("blockers");
        if (source.isArray())
            for (var value : source)
                result.add(value.asText());
        return List.copyOf(result);
    }

    /** Derives a fixed remote operation suffix from original task and application. / 从原任务与应用派生固定远端操作后缀。
     * @param task original caller task / 原调用方任务
     * @param application exact original application / 精确原应用
     * @param kind fixed original operation kind / 固定原操作类别
     * @return stable original remote suffix / 稳定原远端后缀 */
    private static String token(TaskContext task, DeploymentApplicationId application, String kind) {
        return UUID.nameUUIDFromBytes((task.id() + application.id() + kind).getBytes(StandardCharsets.UTF_8)).toString()
            .replace("-", "");
    }

    /** Rejects a forged or unrelated caller. / 拒绝伪造或无关调用者。
     * @return bounded original authorization failure / 有界原授权失败 */
    private static ApiException denied() {
        return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_HANDOFF_OWNER",
            "Handoff belongs to another original task or Mod", Map.of(), Optional.empty()));
    }

    /** Preserves one explicit data or phase failure. / 保留一项明确数据或阶段失败。
     * @param event stable evidence failure / 稳定依据失败
     * @return bounded original evidence exception / 有界原依据异常 */
    private static ApiException invalid(String event) {
        return new ApiException(new Problem(ProblemCode.INCOMPATIBLE, event,
            "Handoff identity or phase cannot be confirmed", Map.of(), Optional.empty()));
    }
}
