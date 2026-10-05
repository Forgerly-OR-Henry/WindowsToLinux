package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSchema;

/** Scans and adopts exact external services without claiming deployment ownership or rewriting their files. / 扫描并接管精确外部服务，不冒充部署归属或重写其文件。 */
public final class DeploymentExternalApplications {
    /** Provider capabilities used only within the authenticated task. / 仅在已认证任务内使用的提供方能力。 */
    private final ModContext context;

    /** Durable registration identity and original effect attempts. / 持久登记身份及原副作用尝试。 */
    private final DeploymentManagementActionLog journal;

    /** Binds the deployment-owned registry and task capabilities. / 绑定部署所属登记表与任务能力。
     * @param context active deployment context / 活动部署上下文 */
    public DeploymentExternalApplications(ModContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        this.journal = new DeploymentManagementActionLog(context);
    }

    /** Saved external presentation and exact adopted identity. / 已保存的外部展示及精确接管身份。
     * @param id registration identity / 登记身份
     * @param server original endpoint / 原端点
     * @param target adopted runtime / 接管运行身份
     * @param displayName local display / 本地展示
     * @param category local category / 本地类别
     * @param accessUrl optional website link / 可选网站链接 */
    public record Registered(String id, ServerId server, Discovered target, String displayName, String category,
                             Optional<String> accessUrl) {
    }

    /** Lists saved registrations without claiming a fresh remote observation. / 列出保存的登记，不宣称新远端观察。
     * @param task original query / 原查询
     * @return bounded saved rows / 有界保存行
     * @throws ApiException if the registry is unavailable / 登记不可用时 */
    public List<Registered> list(TaskContext task) throws ApiException {
        DeploymentSchema.requireIdentity(context.db().identity());
        var result = new ArrayList<Registered>();
        try (var connection = context.db().openConnection(task);
             var query = connection.prepareStatement(
                 "SELECT * FROM external_application ORDER BY external_application_id LIMIT 10001");
             var rows = query.executeQuery()) {
            while (rows.next()) {
                if (result.size() == 10000)
                    throw unknown("DEPLOYMENT_EXTERNAL_LIST_LIMIT");
                var target = new Discovered(rows.getString("runtime_type"), rows.getString("runtime_identity"),
                    rows.getString("identity_fingerprint"), rows.getString("detected_name"),
                    rows.getString("runtime_state"), false, rows.getInt("can_start") == 1,
                    rows.getInt("can_stop") == 1);
                boolean override = rows.getInt("has_display_override") == 1;
                result.add(new Registered(rows.getString("external_application_id"),
                    new ServerId(rows.getString("server_profile_id")), target,
                    override ? rows.getString("display_name") : target.name(),
                    override ? rows.getString("application_category") : "APP",
                    Optional.ofNullable(rows.getString("access_url"))));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw unknown("DEPLOYMENT_EXTERNAL_LIST_UNAVAILABLE");
        }
    }

    /** Changes only local presentation for the original adopted fingerprint. / 仅更改原接管指纹的本地展示。
     * @param original selected original row / 所选原行
     * @param name display name / 展示名称
     * @param category WEBSITE or APP / 网站或应用
     * @param url optional website URL / 可选网站链接
     * @param task original query / 原查询
     * @return saved name / 已存名称
     * @throws ApiException if transaction identity is unknown / 事务身份未知时 */
    public OperationResult<String> edit(Registered original, String name, String category, Optional<String> url,
                                        TaskContext task) throws ApiException {
        if (name == null || name.isBlank() || name.length() > 160 || name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0
            || name.indexOf('\r') >= 0 || !java.util.Set.of("APP", "WEBSITE").contains(category))
            return failed("DEPLOYMENT_EXTERNAL_PRESENTATION_INVALID");
        if (url.isPresent()) {
            if (!category.equals("WEBSITE"))
                return failed("DEPLOYMENT_EXTERNAL_URL_NOT_APPLICABLE");
            try {
                new gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentAccessUrl(
                    java.net.URI.create(url.orElseThrow()));
            } catch (IllegalArgumentException invalid) {
                return failed("DEPLOYMENT_EXTERNAL_URL_INVALID");
            }
        }
        try (var connection = context.db().openConnection(task);
             var update = connection.prepareStatement(
                 "UPDATE external_application SET has_display_override=1,display_name=?,application_category=?,access_url=? WHERE external_application_id=? AND server_profile_id=? AND identity_fingerprint=?")) {
            update.setString(1, name.trim());
            update.setString(2, category);
            update.setString(3, url.orElse(null));
            update.setString(4, original.id());
            update.setString(5, original.server().id());
            update.setString(6, original.target().fingerprint());
            if (update.executeUpdate() != 1)
                return failed("DEPLOYMENT_EXTERNAL_PRESENTATION_IDENTITY_CHANGED");
            return success(name.trim());
        } catch (SQLException failure) {
            throw unknown("DEPLOYMENT_EXTERNAL_PRESENTATION_COMMIT_UNKNOWN");
        }
    }

    /** Scanned immutable target plus its current capabilities. / 扫描到的不可变目标及当前能力。
     * @param kind SYSTEMD or DOCKER / 系统服务或Docker
     * @param identity exact remote identity / 精确远端身份
     * @param fingerprint exact scanned content identity / 精确扫描内容身份
     * @param name observed name / 已观察名称
     * @param state observed runtime state / 已观察运行态
     * @param managed whether helper ownership was detected / 是否发现helper归属
     * @param canStart whether start is supported / 是否支持启动
     * @param canStop whether stop is supported / 是否支持停止 */
    public record Discovered(String kind, String identity, String fingerprint, String name, String state,
                             boolean managed, boolean canStart, boolean canStop) {
        /** Validates the fixed scan protocol. / 校验固定扫描协议。
         * @param kind runtime kind / 运行类别
         * @param identity exact identity / 精确身份
         * @param fingerprint scanned digest / 扫描摘要
         * @param name observed name / 已观察名称
         * @param state observed state / 已观察状态
         * @param managed helper ownership / helper归属
         * @param canStart start capability / 启动能力
         * @param canStop stop capability / 停止能力 */
        public Discovered {
            if (!java.util.Set.of("SYSTEMD", "DOCKER").contains(kind) || identity == null || identity.isBlank()
                || identity.length() > 256 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || name == null || name.length() > 2048 || state == null || state.length() > 32)
                throw new IllegalArgumentException("Invalid scanned external application");
        }
    }

    /** One bounded scan and nonfatal issue list. / 一次有界扫描及非致命问题列表。
     * @param applications current discovered targets / 当前发现的目标
     * @param issues bounded scan issues / 有界扫描问题 */
    public record Scan(List<Discovered> applications, List<String> issues) {
        /** Copies scan output. / 复制扫描输出。
         * @param applications discovered targets / 发现的目标
         * @param issues scan issues / 扫描问题 */
        public Scan {
            applications = List.copyOf(applications);
            issues = List.copyOf(issues);
        }
    }

    /** Reads metadata only from the selected authorized host. / 仅从所选授权主机读取元数据。
     * @param server exact platform server / 精确平台服务器
     * @param task original task / 原任务
     * @return bounded discovery / 有界发现结果
     * @throws ApiException if authentication or protocol fails / 认证或协议失败时 */
    public OperationResult<Scan> scan(ServerId server, TaskContext task) throws ApiException {
        context.servers().get(server, task);
        return invoke(server, List.of("SCAN"), task);
    }

    /** Registers or reuses the exact rechecked identity without changing its runtime. / 不改运行时地登记或复用精确复核身份。
     * @param server selected original server / 所选原服务器
     * @param candidate selected scan result / 所选扫描结果
     * @param task authentic original task / 真实原任务
     * @return stable original identity / 稳定原身份
     * @throws ApiException if original evidence cannot be verified / 无法核实原依据时 */
    public OperationResult<String> adopt(ServerId server, Discovered candidate, TaskContext task) throws ApiException {
        return new Registration().register(server, candidate, Optional.empty(), task);
    }

    /** Complete nonsecret review bound to an unchanged persisted row and target. / 绑定未变持久行及目标的完整非秘密审阅。
     * @param original exact selected registration / 精确所选登记
     * @param candidate fresh replacement scan / 新替换扫描
     * @param endpoint reviewed current server / 已审阅当前服务器
     * @param oldEndpoint original saved endpoint / 原保存端点
     * @param rowDigest digest of all original row fields / 全部原行字段摘要 */
    public record Reattachment(Registered original, Discovered candidate, ServerInfo endpoint, String oldEndpoint,
                               String rowDigest) {
    }

    /** Reads a reattachment review without changing either registration or references. / 读取重新绑定审阅，不改变登记或引用。
     * @param original selected saved identity / 所选保存身份
     * @param candidate selected replacement scan / 所选替换扫描
     * @param task authentic review task / 真实审阅任务
     * @return complete original review / 完整原审阅
     * @throws ApiException if selection, observation or occupancy differs / 选择、观察或占用不符时 */
    public OperationResult<Reattachment> reviewReattachment(Registered original, Discovered candidate, TaskContext task)
        throws ApiException {
        return new Registration().review(original, candidate, task);
    }

    /** Replaces only the explicitly reviewed local association after exact approval. / 精确审批后仅替换明确审阅的本地关联。
     * @param review immutable original review / 不可变原审阅
     * @param task authentic original action / 真实原动作
     * @return replacement identity or retained uncertainty / 替换身份或留存未知
     * @throws ApiException if evidence or commit is uncertain / 依据或提交未知时 */
    public OperationResult<String> reattach(Reattachment review, TaskContext task) throws ApiException {
        return new Registration().register(review.original().server(), review.candidate(), Optional.of(review), task);
    }

    /** Owns exact local registration transactions and their original reference reconciliation. / 负责精确本地登记事务及其原引用核对。 */
    private final class Registration {
        /** Uses the enclosing original provider capabilities. / 使用外层原提供方能力。 */
        private Registration() {
        }

        /** Reviews an exact persisted selection and fresh nonmanaged replacement. / 审阅精确持久选择及新非受管替换。
         * @param original selected saved row / 所选保存行
         * @param candidate selected fresh target / 所选新目标
         * @param task authenticated query / 已认证查询
         * @return immutable review or known refusal / 不可变审阅或已知拒绝
         * @throws ApiException if original persistence cannot be read / 原持久状态无法读取时 */
        private OperationResult<Reattachment> review(Registered original, Discovered candidate, TaskContext task)
            throws ApiException {
            if (!original.target().kind().equals(candidate.kind())
                || !original.target().identity().equals(candidate.identity()) || candidate.managed())
                return failed("DEPLOYMENT_EXTERNAL_REATTACH_SELECTION");
            var row = identity(original.server(), candidate, task);
            if (row.isEmpty() || !selected(original, row.orElseThrow()))
                return failed("DEPLOYMENT_EXTERNAL_REATTACH_STALE");
            if (!quiet(original.id(), task))
                return failed("DEPLOYMENT_EXTERNAL_OCCUPIED");
            var fresh = invoke(original.server(),
                List.of(candidate.kind(), candidate.identity(), candidate.fingerprint(), "REFRESH_STATUS"), task);
            if (fresh.outcome() != TaskResult.SUCCEEDED)
                return copy(fresh);
            if (fresh.value().orElseThrow().applications().size() != 1
                || !sameIdentity(candidate, fresh.value().orElseThrow().applications().getFirst())
                || fresh.value().orElseThrow().applications().getFirst().managed())
                return failed("DEPLOYMENT_EXTERNAL_IDENTITY_CHANGED");
            var info = context.servers().get(original.server(), task);
            return success(new Reattachment(original, candidate, info, endpoint(row.orElseThrow()),
                digest(row.orElseThrow())));
        }

        /** Reuses an exact confirmed row or journals one explicit replacement before any write. / 复用精确确证行，或在写入前记录一次明确替换。
         * @param server original server identity / 原服务器身份
         * @param candidate reviewed scanner identity / 已审阅扫描身份
         * @param replacement explicit immutable replacement review / 明确不可变替换审阅
         * @param task authenticated action / 已认证动作
         * @return original registration conclusion / 原登记结论
         * @throws ApiException if persistence or references are uncertain / 持久化或引用未知时 */
        private OperationResult<String> register(ServerId server, Discovered candidate,
                                                 Optional<Reattachment> replacement, TaskContext task) throws ApiException {
            var prior = journal.find(task);
            if (prior.isPresent()) {
                var original = prior.orElseThrow();
                if (!original.action().equals("EXTERNAL_ADOPT") || !original.server().equals(server)
                    || !original.currentRevision().equals(candidate.fingerprint())
                    || !original.facts().path("kind").asText().equals(candidate.kind())
                    || !original.facts().path("identity").asText().equals(candidate.identity()))
                    return failed("DEPLOYMENT_EXTERNAL_TASK_CHANGED");
                return recover(original, task);
            }
            var info = context.servers().get(server, task);
            var existing = identity(server, candidate, task);
            if (existing.isPresent() && replacement.isEmpty()) {
                var row = existing.orElseThrow();
                if (!row.path("identity_fingerprint").asText().equals(candidate.fingerprint())
                    || !sameEndpoint(row, info))
                    return failed("DEPLOYMENT_EXTERNAL_REATTACH_REQUIRED");
                if (!quiet(row.path("external_application_id").asText(), task))
                    return failed("DEPLOYMENT_EXTERNAL_OCCUPIED");
            }
            if (replacement.isPresent()
                && (existing.isEmpty() || !selected(replacement.orElseThrow().original(), existing.orElseThrow())
                || !digest(existing.orElseThrow()).equals(replacement.orElseThrow().rowDigest())
                || !endpoint(existing.orElseThrow()).equals(replacement.orElseThrow().oldEndpoint())
                || !sameEndpoint(info, replacement.orElseThrow().endpoint())
                || !replacement.orElseThrow().original().server().equals(server)
                || !sameIdentity(candidate, replacement.orElseThrow().candidate())))
                return failed("DEPLOYMENT_EXTERNAL_REATTACH_STALE");
            var fresh = invoke(server,
                List.of(candidate.kind(), candidate.identity(), candidate.fingerprint(), "REFRESH_STATUS"), task);
            if (fresh.outcome() != TaskResult.SUCCEEDED)
                return copy(fresh);
            if (fresh.value().orElseThrow().applications().size() != 1
                || !sameIdentity(candidate, fresh.value().orElseThrow().applications().getFirst())
                || fresh.value().orElseThrow().applications().getFirst().managed())
                return failed("DEPLOYMENT_EXTERNAL_IDENTITY_CHANGED");
            info = context.servers().get(server, task);
            if (existing.isPresent() && replacement.isEmpty()) {
                if (!sameEndpoint(existing.orElseThrow(), info))
                    return failed("DEPLOYMENT_EXTERNAL_SERVER_CHANGED");
                String id = existing.orElseThrow().path("external_application_id").asText();
                var ref = context.servers().findReference(server, "deployment.external." + id, task);
                return ref.isPresent()
                    && ref.orElseThrow().state() == gold.debug.windowstolinux.appapi.task.ref.ResourceRefState.ACTIVE
                    ? success(id)
                    : unknownResult("DEPLOYMENT_EXTERNAL_RESERVATION_UNCONFIRMED");
            }
            return publish(server, fresh.value().orElseThrow().applications().getFirst(), info, existing, replacement,
                task);
        }

        /** Captures the old reference and exact approval before reserving a new identity. / 在预留新身份前捕获旧引用及精确审批。
         * @param server exact shared server / 精确共享服务器
         * @param observed fresh reviewed target / 新复核目标
         * @param info actual endpoint after authentication / 认证后实际端点
         * @param previous original row or absence / 原行或不存在
         * @param replacement explicit review or absence / 明确审阅或不存在
         * @param task original action / 原动作
         * @return registered conclusion / 登记结论
         * @throws ApiException if original evidence or write is unknown / 原依据或写入未知时 */
        private OperationResult<String> publish(ServerId server, Discovered observed, ServerInfo info,
                                                Optional<ObjectNode> previous, Optional<Reattachment> replacement, TaskContext task)
            throws ApiException {
            String id = "external:"
                + UUID.nameUUIDFromBytes(("deployment-external-v1\n" + task.id()).getBytes(StandardCharsets.UTF_8));
            var facts = DeploymentManagementActionLog.facts().put("kind", observed.kind())
                .put("identity", observed.identity()).put("fingerprint", observed.fingerprint())
                .put("host", info.host()).put("port", info.port()).put("username", info.username())
                .put("registrationId", id).put("businessKey", "deployment.external." + id);
            if (previous.isPresent()) {
                var old = previous.orElseThrow();
                String oldId = old.path("external_application_id").asText();
                if (replacement.isEmpty() || !sameEndpoint(info, replacement.orElseThrow().endpoint())
                    || !quiet(oldId, task))
                    return failed("DEPLOYMENT_EXTERNAL_REATTACH_STALE");
                var oldRef = context.servers().findReference(server, "deployment.external." + oldId, task);
                if (oldRef.isEmpty() || oldRef.orElseThrow()
                    .state() != gold.debug.windowstolinux.appapi.task.ref.ResourceRefState.ACTIVE)
                    return unknownResult("DEPLOYMENT_EXTERNAL_RESERVATION_UNCONFIRMED");
                facts.set("previousRow", old.deepCopy());
                facts.put("previousReferenceId", oldRef.orElseThrow().id().toString());
                var intent = new ActionIntent("deployment.external.reattach", List.of(server.id()), oldId, digest(old),
                    facts.toString(),
                    "Replace only this reviewed local registration and clear its local presentation");
                var approval = context.interaction().approve(intent, task);
                if (approval.outcome() != TaskResult.SUCCEEDED)
                    return copy(approval);
                var current = identity(server, observed, task);
                if (current.isEmpty() || !current.orElseThrow().equals(old)
                    || !sameEndpoint(info, context.servers().get(server, task)) || !quiet(oldId, task))
                    return failed("DEPLOYMENT_EXTERNAL_REATTACH_STALE");
            }
            var operation = journal.begin(id, server, "EXTERNAL_ADOPT", observed.fingerprint(), Optional.empty(), facts,
                task);
            var reference = context.servers().reserve(server, facts.path("businessKey").asText(), task);
            operation = journal.advance(operation, "RESERVED",
                DeploymentManagementActionLog.copy(operation).put("referenceId", reference.id().toString()), task);
            operation = journal.advance(operation, "INSERTING", DeploymentManagementActionLog.copy(operation), task);
            try {
                insert(operation, observed, info, previous, task);
            } catch (SQLException failure) {
                context.servers().resolve(reference.id(), ResourceRefResult.UNKNOWN, task);
                journal.advance(operation, "UNKNOWN", DeploymentManagementActionLog.copy(operation), task);
                throw unknown("DEPLOYMENT_EXTERNAL_ADOPTION_UNCONFIRMED");
            }
            operation = journal.advance(operation, "REGISTERED", DeploymentManagementActionLog.copy(operation), task);
            return recover(operation, task);
        }

        /** Commits exact deletion and replacement together under the original row lock. / 在原行锁下共同提交精确删除与替换。
         * @param operation original durable insertion intent / 原持久插入意图
         * @param observed rechecked target / 复核目标
         * @param info rechecked endpoint / 复核端点
         * @param previous expected complete old row / 预期完整旧行
         * @param task authenticated original action / 已认证原动作
         * @throws SQLException if exact transaction or acknowledgement fails / 精确事务或回执失败时 */
        private void insert(DeploymentManagementActionLog.Entry operation, Discovered observed, ServerInfo info,
                            Optional<ObjectNode> previous, TaskContext task) throws SQLException {
            try (var connection = context.db().openConnection(task)) {
                connection.setAutoCommit(false);
                try {
                    if (previous.isPresent()) {
                        var old = previous.orElseThrow();
                        String id = old.path("external_application_id").asText();
                        try (var query = connection.prepareStatement(
                            "SELECT * FROM external_application WHERE external_application_id=? FOR UPDATE")) {
                            query.setString(1, id);
                            try (var rows = query.executeQuery()) {
                                if (!rows.next() || !row(rows).equals(old) || rows.next())
                                    throw new SQLException("Reviewed registration changed");
                            }
                        }
                        if (!quiet(connection, id, task))
                            throw new SQLException("Original registration is occupied");
                        try (var delete = connection.prepareStatement(
                            "DELETE FROM external_application WHERE external_application_id=? AND identity_fingerprint=?")) {
                            delete.setString(1, id);
                            delete.setString(2, old.path("identity_fingerprint").asText());
                            if (delete.executeUpdate() != 1)
                                throw new SQLException("Original registration deletion unconfirmed");
                        }
                    }
                    try (var insert = connection.prepareStatement(
                        "INSERT INTO external_application(external_application_id,server_profile_id,runtime_type,runtime_identity,identity_fingerprint,bound_host,bound_ssh_port,bound_username,runtime_state,can_start,can_stop,detected_name,adopted_at,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        insert.setString(1, operation.applicationId());
                        insert.setString(2, operation.server().id());
                        insert.setString(3, observed.kind());
                        insert.setString(4, observed.identity());
                        insert.setString(5, observed.fingerprint());
                        insert.setString(6, info.host());
                        insert.setInt(7, info.port());
                        insert.setString(8, info.username());
                        insert.setString(9, observed.state());
                        insert.setInt(10, observed.canStart() ? 1 : 0);
                        insert.setInt(11, observed.canStop() ? 1 : 0);
                        insert.setString(12, observed.name());
                        long at = Instant.now().toEpochMilli();
                        insert.setLong(13, at);
                        insert.setLong(14, at);
                        insert.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
            } catch (ApiException failure) {
                throw new SQLException("Original database capability unavailable", failure);
            }
        }

        /** Reads the actual new row and old absence before resolving either reference. / 在核对任一引用前读取实际新行及旧行不存在。
         * @param operation authenticated original operation / 已认证原操作
         * @param task same original task / 同一原任务
         * @return confirmed original ID or retained uncertainty / 确认原ID或留存未知
         * @throws ApiException if reference resolution is uncertain / 引用核对未知时 */
        private OperationResult<String> recover(DeploymentManagementActionLog.Entry operation, TaskContext task)
            throws ApiException {
            var facts = operation.facts();
            String id = facts.path("registrationId").asText();
            var reference = context.servers().findReference(operation.server(), facts.path("businessKey").asText(),
                task);
            if (reference.isEmpty()
                || !reference.orElseThrow().id().toString().equals(facts.path("referenceId").asText()))
                return unknownResult("DEPLOYMENT_EXTERNAL_RESERVATION_UNCONFIRMED");
            try (var connection = context.db().openConnection(task)) {
                try (var query = connection
                    .prepareStatement("SELECT * FROM external_application WHERE external_application_id=?")) {
                    query.setString(1, id);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next())
                            return unknownResult("DEPLOYMENT_EXTERNAL_REGISTRATION_ABSENT");
                        var actual = row(rows);
                        if (!actual.path("server_profile_id").asText().equals(operation.server().id())
                            || !actual.path("runtime_type").asText().equals(facts.path("kind").asText())
                            || !actual.path("runtime_identity").asText().equals(facts.path("identity").asText())
                            || !actual.path("identity_fingerprint").asText().equals(operation.currentRevision())
                            || !actual.path("bound_host").asText().equals(facts.path("host").asText())
                            || !actual.path("bound_ssh_port").asText().equals(facts.path("port").asText())
                            || !actual.path("bound_username").asText().equals(facts.path("username").asText())
                            || rows.next())
                            return unknownResult("DEPLOYMENT_EXTERNAL_REGISTRATION_CONFLICT");
                    }
                }
                if (facts.has("previousRow")) {
                    try (var query = connection.prepareStatement(
                        "SELECT external_application_id FROM external_application WHERE external_application_id=?")) {
                        query.setString(1, facts.path("previousRow").path("external_application_id").asText());
                        try (var rows = query.executeQuery()) {
                            if (rows.next())
                                return unknownResult("DEPLOYMENT_EXTERNAL_OLD_ROW_PRESENT");
                        }
                    }
                }
            } catch (SQLException failure) {
                return unknownResult("DEPLOYMENT_EXTERNAL_REGISTRATION_UNCONFIRMED");
            }
            context.servers().resolve(reference.orElseThrow().id(), ResourceRefResult.REFERENCED, task);
            if (facts.has("previousRow")) {
                String oldId = facts.path("previousRow").path("external_application_id").asText();
                var old = context.servers().findReference(operation.server(), "deployment.external." + oldId, task);
                if (old.isPresent()) {
                    if (!old.orElseThrow().id().toString().equals(facts.path("previousReferenceId").asText()))
                        return unknownResult("DEPLOYMENT_EXTERNAL_OLD_REFERENCE_CHANGED");
                    context.servers().resolve(old.orElseThrow().id(), ResourceRefResult.NOT_REFERENCED, task);
                }
            }
            if (!operation.phase().equals("FINISHED"))
                journal.advance(operation, "FINISHED", DeploymentManagementActionLog.copy(operation), task);
            return success(id);
        }

        /** Reads the unique original runtime identity without inventing a new registration. / 读取唯一原运行身份，不虚构新登记。
         * @param server exact original profile / 精确原资料
         * @param candidate exact runtime kind and name / 精确运行种类及名称
         * @param task authenticated query / 已认证查询
         * @return complete old row or absence / 完整旧行或不存在
         * @throws ApiException if original row cannot be read / 无法读取原行时 */
        private Optional<ObjectNode> identity(ServerId server, Discovered candidate, TaskContext task)
            throws ApiException {
            DeploymentSchema.requireIdentity(context.db().identity());
            try (var connection = context.db().openConnection(task);
                 var query = connection.prepareStatement(
                     "SELECT * FROM external_application WHERE server_profile_id=? AND runtime_type=? AND runtime_identity=?")) {
                query.setString(1, server.id());
                query.setString(2, candidate.kind());
                query.setString(3, candidate.identity());
                try (var rows = query.executeQuery()) {
                    if (!rows.next())
                        return Optional.empty();
                    var value = row(rows);
                    if (rows.next())
                        throw new SQLException("Duplicate external identity");
                    return Optional.of(value);
                }
            } catch (SQLException failure) {
                throw unknown("DEPLOYMENT_EXTERNAL_REGISTRATION_UNAVAILABLE");
            }
        }

        /** Copies every persisted column as an exact nonsecret review fact. / 将每个持久列复制为精确非秘密审阅事实。
         * @param rows current stored row / 当前存储行
         * @return complete row fact / 完整行事实
         * @throws SQLException if the row cannot be read / 无法读取行时 */
        private ObjectNode row(java.sql.ResultSet rows) throws SQLException {
            var result = DeploymentManagementActionLog.facts();
            var columns = rows.getMetaData();
            for (int i = 1; i <= columns.getColumnCount(); i++)
                result.put(columns.getColumnName(i).toLowerCase(java.util.Locale.ROOT), rows.getString(i));
            return result;
        }

        /** Checks local selection fields without confusing detected and overridden names. / 核对本地选择字段，不混淆检测名称与覆盖名称。
         * @param original selected row / 所选行
         * @param row current complete persistence / 当前完整持久状态
         * @return exact selected identity and presentation / 精确选择身份及展示是否一致 */
        private boolean selected(Registered original, ObjectNode row) {
            boolean override = row.path("has_display_override").asText().equals("1");
            return original.id().equals(row.path("external_application_id").asText())
                && original.server().id().equals(row.path("server_profile_id").asText())
                && original.target().kind().equals(row.path("runtime_type").asText())
                && original.target().identity().equals(row.path("runtime_identity").asText())
                && original.target().fingerprint().equals(row.path("identity_fingerprint").asText())
                && original.displayName().equals(row.path(override ? "display_name" : "detected_name").asText())
                && original.category().equals(override ? row.path("application_category").asText() : "APP")
                && original.accessUrl().equals(Optional
                .ofNullable(row.path("access_url").isNull() ? null : row.path("access_url").asText()));
        }

        /** Requires no other unclosed original management operation on the old row. / 要求旧行不存在其他未结束原管理操作。
         * @param id exact registration / 精确登记
         * @param task original task / 原任务
         * @return whether the row is unoccupied / 行是否未被占用
         * @throws ApiException if occupancy cannot be read / 无法读取占用时 */
        private boolean quiet(String id, TaskContext task) throws ApiException {
            try (var connection = context.db().openConnection(task)) {
                return quiet(connection, id, task);
            } catch (SQLException failure) {
                throw unknown("DEPLOYMENT_EXTERNAL_OCCUPANCY_UNCONFIRMED");
            }
        }

        /** Checks the same occupancy within the replacement transaction. / 在替换事务内核对同一占用。
         * @param connection original transaction / 原事务
         * @param id old registration / 旧登记
         * @param task authenticated task / 已认证任务
         * @return whether no other operation remains open / 是否无其他未结操作
         * @throws SQLException if the query fails / 查询失败时 */
        private boolean quiet(java.sql.Connection connection, String id, TaskContext task) throws SQLException {
            try (var query = connection.prepareStatement(
                "SELECT operation_id FROM deployment_management_operation WHERE application_id=? AND phase<>'FINISHED' AND platform_task_id<>?")) {
                query.setString(1, id);
                query.setObject(2, task.id());
                try (var rows = query.executeQuery()) {
                    return !rows.next();
                }
            }
        }

        /** Digests the complete immutable review without transmitting secret values. / 摘要完整不可变审阅，不传递秘密值。
         * @param row original local row / 原本地行
         * @return original SHA-256 binding / 原SHA-256绑定 */
        private String digest(ObjectNode row) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(row.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        /** Formats the actual saved endpoint for explicit review. / 格式化实际保存端点供明确审阅。
         * @param row actual stored registration / 实际保存登记
         * @return exact account, host and port / 精确账号、主机及端口 */
        private String endpoint(ObjectNode row) {
            return row.path("bound_username").asText() + "@" + row.path("bound_host").asText() + ":"
                + row.path("bound_ssh_port").asText();
        }

        /** Compares stored endpoint fields against current platform configuration. / 对比保存端点字段与当前平台配置。
         * @param row original row / 原行
         * @param info current authorized configuration / 当前授权配置
         * @return whether the actual endpoint is unchanged / 实际端点是否未变 */
        private boolean sameEndpoint(ObjectNode row, ServerInfo info) {
            return row.path("bound_host").asText().equals(info.host())
                && row.path("bound_ssh_port").asText().equals(Integer.toString(info.port()))
                && row.path("bound_username").asText().equals(info.username());
        }

        /** Keeps endpoint and trust identity fixed between review and actual approval. / 在审阅与实际审批之间保持端点及信任身份不变。
         * @param actual actual current configuration / 实际当前配置
         * @param reviewed original review configuration / 原审阅配置
         * @return whether the authenticated target remains exact / 已认证目标是否仍精确 */
        private boolean sameEndpoint(ServerInfo actual, ServerInfo reviewed) {
            return actual.ref().equals(reviewed.ref()) && actual.host().equals(reviewed.host())
                && actual.port() == reviewed.port() && actual.username().equals(reviewed.username())
                && actual.trustedFingerprint().equals(reviewed.trustedFingerprint());
        }
    }

    /** Rechecks the stored endpoint and immutable runtime fingerprint before an allowed action. / 允许动作前复核保存的端点与不可变运行指纹。
     * @param id stable registration / 稳定登记
     * @param action REFRESH_STATUS, START, STOP or RESTART / 状态刷新、启动、停止或重启
     * @param task original task / 原任务
     * @return current exact observation / 当前精确观察
     * @throws ApiException if server or identity cannot be checked / 服务器或身份无法核对时 */
    public OperationResult<Discovered> execute(String id, String action, TaskContext task) throws ApiException {
        if (!java.util.Set.of("REFRESH_STATUS", "START", "STOP", "RESTART").contains(action))
            return failed("DEPLOYMENT_EXTERNAL_ACTION_INVALID");
        var stored = stored(id, task);
        var prior = journal.find(task);
        var info = context.servers().get(stored.server(), task);
        if (!info.host().equals(stored.host()) || info.port() != stored.port()
            || !info.username().equals(stored.username()))
            return prior.isPresent() && java.util.Set.of("EFFECTING", "UNKNOWN").contains(prior.orElseThrow().phase())
                ? unknownResult("DEPLOYMENT_EXTERNAL_SERVER_CHANGED")
                : failed("DEPLOYMENT_EXTERNAL_SERVER_CHANGED");
        DeploymentManagementActionLog.Entry operation = null;
        boolean readOnlyReconcile = false;
        if (!action.equals("REFRESH_STATUS")) {
            var begun = beginAction(stored, id, action, task);
            if (begun.outcome() != TaskResult.SUCCEEDED)
                return copy(begun);
            operation = begun.value().orElseThrow();
            readOnlyReconcile = !operation.phase().equals("REVIEWED");
            if (!readOnlyReconcile)
                operation = journal.advance(operation, "EFFECTING",
                    DeploymentManagementActionLog.copy(operation).put("attempted", true), task);
        }
        var result = invoke(stored.server(), List.of(stored.target().kind(), stored.target().identity(),
            stored.target().fingerprint(), readOnlyReconcile ? "REFRESH_STATUS" : action), task);
        if (result.outcome() != TaskResult.SUCCEEDED) {
            return operation == null
                ? copy(result)
                : unverified(operation, "DEPLOYMENT_EXTERNAL_EFFECT_UNVERIFIED", task);
        }
        if (result.value().orElseThrow().applications().size() != 1)
            return unverified(operation, "DEPLOYMENT_EXTERNAL_RESPONSE_INVALID", task);
        var value = result.value().orElseThrow().applications().getFirst();
        if (!sameIdentity(stored.target(), value) || value.managed())
            return unverified(operation, "DEPLOYMENT_EXTERNAL_IDENTITY_CHANGED", task);
        if (readOnlyReconcile && (!operation.facts().path("attempted").asBoolean(false)
            || action.equals("RESTART") && !operation.phase().equals("FINISHED")
            || action.equals("START") && !value.state().equals("RUNNING")
            || action.equals("STOP") && !value.state().equals("STOPPED")))
            return unknownResult("DEPLOYMENT_EXTERNAL_EFFECT_UNVERIFIED");
        if (operation != null && (action.equals("STOP") && !value.state().equals("STOPPED")
            || !action.equals("STOP") && !value.state().equals("RUNNING")))
            return unverified(operation, "DEPLOYMENT_EXTERNAL_EFFECT_UNVERIFIED", task);
        return persistObservation(stored, id, value, operation, readOnlyReconcile, task);
    }

    /** Rechecks exact unmanaged identity before persisting the first action attempt. / 持久化首个动作尝试前复核精确非受管身份。
     * @param stored original registered endpoint and runtime / 原登记端点及运行身份
     * @param id stable registration identity / 稳定登记身份
     * @param action fixed original action / 固定原动作
     * @param task authenticated original task / 已认证原任务
     * @return original durable intent or known refusal / 原持久意图或已知拒绝
     * @throws ApiException if original authorization or evidence fails / 原授权或依据失败时 */
    private OperationResult<DeploymentManagementActionLog.Entry> beginAction(Stored stored, String id, String action,
                                                                             TaskContext task) throws ApiException {
        DeploymentManagementActionLog.Entry operation = null;
        var prior = journal.find(task);
        if (prior.isPresent()) {
            operation = prior.orElseThrow();
            if (!operation.applicationId().equals(id) || !operation.server().equals(stored.server())
                || !operation.action().equals("EXTERNAL_" + action)
                || !operation.currentRevision().equals(stored.target().fingerprint()))
                return failed("DEPLOYMENT_EXTERNAL_TASK_CHANGED");
        }
        if (operation == null) {
            var preflight = invoke(stored.server(), List.of(stored.target().kind(), stored.target().identity(),
                stored.target().fingerprint(), "REFRESH_STATUS"), task);
            if (preflight.outcome() != TaskResult.SUCCEEDED)
                return copy(preflight);
            if (preflight.value().orElseThrow().applications().size() != 1
                || !sameIdentity(stored.target(), preflight.value().orElseThrow().applications().getFirst())
                || preflight.value().orElseThrow().applications().getFirst().managed())
                return failed("DEPLOYMENT_EXTERNAL_IDENTITY_CHANGED");
            var actual = preflight.value().orElseThrow().applications().getFirst();
            if (action.equals("START") && !actual.canStart() || action.equals("STOP") && !actual.canStop()
                || action.equals("RESTART") && (!actual.canStart() || !actual.canStop()))
                return failed("DEPLOYMENT_EXTERNAL_ACTION_UNSUPPORTED");
            var facts = DeploymentManagementActionLog.facts()
                .put("before", preflight.value().orElseThrow().applications().getFirst().state())
                .put("kind", stored.target().kind()).put("identity", stored.target().identity())
                .put("fingerprint", stored.target().fingerprint()).put("attempted", false);
            operation = journal.begin(id, stored.server(), "EXTERNAL_" + action, stored.target().fingerprint(),
                Optional.empty(), facts, task);
        }
        return success(operation);
    }

    /** Stores an exact observed runtime before concluding the original action. / 结束原动作前保存精确已观察运行状态。
     * @param stored original registered identity / 原登记身份
     * @param id stable registration identity / 稳定登记身份
     * @param value exact observed runtime / 精确已观察运行状态
     * @param operation original action or absent read-only query / 原动作或无动作的只读查询
     * @param readOnlyReconcile whether the evidence came from observation only / 依据是否仅来自观察
     * @param task authenticated original task / 已认证原任务
     * @return persisted original observation or uncertainty / 持久化原观察或未知
     * @throws ApiException if original commit cannot be confirmed / 原提交无法确认时 */
    private OperationResult<Discovered> persistObservation(Stored stored, String id, Discovered value,
                                                           DeploymentManagementActionLog.Entry operation, boolean readOnlyReconcile, TaskContext task)
        throws ApiException {
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var update = connection.prepareStatement(
                 "UPDATE external_application SET runtime_state=?,can_start=?,can_stop=?,detected_name=?,observed_at=? WHERE external_application_id=? AND identity_fingerprint=?")) {
            update.setString(1, value.state());
            update.setInt(2, value.canStart() ? 1 : 0);
            update.setInt(3, value.canStop() ? 1 : 0);
            update.setString(4, value.name());
            update.setLong(5, Instant.now().toEpochMilli());
            update.setString(6, id);
            update.setString(7, stored.target().fingerprint());
            if (update.executeUpdate() != 1)
                return unverified(operation, "DEPLOYMENT_EXTERNAL_REGISTRATION_CHANGED", task);
        } catch (SQLException failure) {
            throw unknown("DEPLOYMENT_EXTERNAL_OBSERVATION_UNCONFIRMED");
        }
        if (operation != null && !operation.phase().equals("FINISHED"))
            journal.advance(operation, "FINISHED", DeploymentManagementActionLog.copy(operation)
                .put("after", value.state()).put("reconciledReadOnly", readOnlyReconcile), task);
        return success(value);
    }

    /** Retains an attempted action when the exact final observation is missing. / 缺少精确最终观察时留存已尝试动作。
     * @param operation original action or absent read-only query / 原动作或无动作的只读查询
     * @param event stable evidence failure / 稳定依据失败
     * @param task authenticated original task / 已认证原任务
     * @return retained uncertainty or known query refusal / 留存未知或已知查询拒绝
     * @throws ApiException if original uncertainty cannot be persisted / 原未知无法持久化时 */
    private OperationResult<Discovered> unverified(DeploymentManagementActionLog.Entry operation, String event,
                                                   TaskContext task) throws ApiException {
        if (operation == null)
            return failed(event);
        if (!operation.phase().equals("FINISHED"))
            journal.advance(operation, "UNKNOWN", DeploymentManagementActionLog.copy(operation), task);
        return unknownResult(event);
    }

    /** Reads only the deployment-owned external registration row. / 仅读取部署所属外部登记行。
     * @param id stable original registration / 稳定原登记
     * @param task authenticated original task / 已认证原任务
     * @return complete original endpoint and runtime / 完整原端点及运行身份
     * @throws ApiException if the original row cannot be confirmed / 原行无法确认时 */
    private Stored stored(String id, TaskContext task) throws ApiException {
        DeploymentSchema.requireIdentity(context.db().identity());
        try (var connection = context.db().openConnection(task);
             var query = connection
                 .prepareStatement("SELECT * FROM external_application WHERE external_application_id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    throw new SQLException("External application is absent");
                return new Stored(new ServerId(rows.getString("server_profile_id")), rows.getString("bound_host"),
                    rows.getInt("bound_ssh_port"), rows.getString("bound_username"),
                    new Discovered(rows.getString("runtime_type"), rows.getString("runtime_identity"),
                        rows.getString("identity_fingerprint"), rows.getString("detected_name"),
                        rows.getString("runtime_state"), false, rows.getInt("can_start") == 1,
                        rows.getInt("can_stop") == 1));
            }
        } catch (SQLException failure) {
            throw unknown("DEPLOYMENT_EXTERNAL_REGISTRATION_UNAVAILABLE");
        }
    }

    /** Executes only the fixed archived scanner under current provider review. / 仅在当前提供方审批下执行固定归档扫描器。
     * @param server exact authorized target / 精确授权目标
     * @param arguments fixed original scan or runtime action / 固定原扫描或运行动作
     * @param task authenticated original task / 已认证原任务
     * @return complete original scanner receipt or failure / 完整原扫描回执或失败
     * @throws ApiException if archived scanner, session or approval fails / 归档扫描器、会话或审批失败时 */
    private OperationResult<Scan> invoke(ServerId server, List<String> arguments, TaskContext task)
        throws ApiException {
        var resource = getClass().getResourceAsStream("external-applications.py");
        if (resource == null)
            throw unknown("DEPLOYMENT_EXTERNAL_SCANNER_MISSING");
        byte[] script;
        try (resource) {
            script = resource.readAllBytes();
        } catch (IOException failure) {
            throw unknown("DEPLOYMENT_EXTERNAL_SCANNER_UNREADABLE");
        }
        if (script.length > 16384)
            throw unknown("DEPLOYMENT_EXTERNAL_SCANNER_OVERSIZE");
        String command = "exec python3 - " + arguments.stream().map(DeploymentExternalApplications::quote)
            .collect(java.util.stream.Collectors.joining(" "));
        try (var session = context.linux().connect(server, task);
             var request = new CommandRequest(command, script, Duration.ofSeconds(110), 1_048_576)) {
            var intent = RemoteActions.command(server, "deployment-external-v1", request);
            var grant = context.interaction().approve(intent, task);
            if (grant.outcome() != TaskResult.SUCCEEDED)
                return copy(grant);
            var reply = session.execute(request, grant.value().orElseThrow());
            if (reply.outcome() != TaskResult.SUCCEEDED || reply.value().isEmpty())
                return copy(reply);
            CommandResult result = reply.value().orElseThrow();
            if (result.truncated() || !result.exitCode().equals(Optional.of(0)))
                return failed("DEPLOYMENT_EXTERNAL_REMOTE_FAILED");
            try {
                return success(parse(result.stdout()));
            } catch (IllegalArgumentException invalid) {
                return failed("DEPLOYMENT_EXTERNAL_RESPONSE_INVALID");
            }
        } finally {
            java.util.Arrays.fill(script, (byte) 0);
        }
    }

    /** Parses complete bounded tab-delimited protocol output. / 解析完整有界制表符协议输出。
     * @param output complete scanner receipt / 完整扫描回执
     * @return complete original scan and nonfatal issues / 完整原扫描及非致命问题 */
    private static Scan parse(String output) {
        var apps = new ArrayList<Discovered>();
        var issues = new ArrayList<String>();
        boolean ended = false;
        for (String line : output.lines().toList()) {
            if (ended)
                throw new IllegalArgumentException("Trailing scanner output");
            String[] fields = line.split("\t", -1);
            if (fields.length == 9 && fields[0].equals("APP")) {
                apps.add(new Discovered(fields[1], decode(fields[2]), fields[3], decode(fields[4]), fields[5],
                    bit(fields[8]), bit(fields[6]), bit(fields[7])));
            } else if (fields.length == 2 && fields[0].equals("ISSUE")) {
                issues.add(fields[1]);
            } else if (fields.length == 2 && fields[0].equals("END") && Integer.parseInt(fields[1]) == apps.size()) {
                ended = true;
            } else
                throw new IllegalArgumentException("Invalid scanner line");
        }
        if (!ended || apps.size() > 200 || issues.size() > 32)
            throw new IllegalArgumentException("Incomplete scan");
        return new Scan(apps, issues);
    }

    /** Decodes one bounded scanner field. / 解码一项有界扫描字段。
     * @param value bounded base64 original text / 有界base64原文本
     * @return decoded original text / 解码原文本 */
    private static String decode(String value) {
        if (value.length() > 2048)
            throw new IllegalArgumentException("Scanner field too long");
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    /** Parses a strict protocol bit. / 解析严格协议位。
     * @param value original zero or one / 原零或一
     * @return exact original flag / 精确原标志 */
    private static boolean bit(String value) {
        if (!value.equals("0") && !value.equals("1"))
            throw new IllegalArgumentException("Invalid scanner bit");
        return value.equals("1");
    }

    /** Quotes one literal scanner argument. / 引用一项字面扫描参数。
     * @param value original literal argument / 原字面参数
     * @return safely quoted original argument / 安全引用的原参数 */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Compares immutable target facts while allowing state to change. / 比较不可变目标事实并允许运行态变化。
     * @param left original reviewed runtime / 原已审阅运行身份
     * @param right fresh observed runtime / 新观察运行身份
     * @return whether exact immutable identities match / 精确不可变身份是否匹配 */
    private static boolean sameIdentity(Discovered left, Discovered right) {
        return left.kind().equals(right.kind()) && left.identity().equals(right.identity())
            && left.fingerprint().equals(right.fingerprint());
    }

    /** Copies an upstream non-success. / 复制上游非成功结果。
     * @param <T> result type / 结果类型
     * @param source original upstream result / 原上游结果
     * @return original conclusion without a fabricated value / 无捏造值的原结论 */
    private static <T> OperationResult<T> copy(OperationResult<?> source) {
        return new OperationResult<>(source.outcome(), Optional.empty(), source.problem());
    }

    /** Returns an observed successful value. / 返回已观察成功值。
     * @param <T> result type / 结果类型
     * @param value verified original value / 已核实原值
     * @return successful original result / 成功原结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Returns a known identity or protocol refusal. / 返回已知身份或协议拒绝。
     * @param <T> result type / 结果类型
     * @param event stable known refusal / 稳定已知拒绝
     * @return known original failure / 已知原失败 */
    private static <T> OperationResult<T> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.INCOMPATIBLE,
            event, "External application identity changed or cannot be used", Map.of(), Optional.empty())));
    }

    /** Returns uncertainty without another registration or remote effect. / 不再次登记或发送远端副作用地返回未知。
     * @param <T> result type / 结果类型
     * @param event stable uncertainty identity / 稳定未知身份
     * @return retained original uncertainty / 留存原未知 */
    private static <T> OperationResult<T> unknownResult(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original external application requires reconciliation", Map.of(), Optional.empty())));
    }

    /** Retains uncertain remote or business effects. / 保留未确认的远端或业务效果。
     * @param event stable uncertainty identity / 稳定未知身份
     * @return bounded original evidence exception / 有界原依据异常 */
    private static ApiException unknown(String event) {
        return new ApiException(new Problem(ProblemCode.UNKNOWN, event,
            "External application result requires reconciliation", Map.of(), Optional.empty()));
    }

    /** Original endpoint and target retained inside deployment only. / 仅在部署内部保留的原端点与目标。
     * @param server original configured target / 原配置目标
     * @param host original reviewed hostname / 原已审阅主机名
     * @param port original reviewed SSH port / 原已审阅SSH端口
     * @param username original reviewed user / 原已审阅用户
     * @param target original immutable runtime identity / 原不可变运行身份 */
    private record Stored(ServerId server, String host, int port, String username, Discovered target) {
    }
}
