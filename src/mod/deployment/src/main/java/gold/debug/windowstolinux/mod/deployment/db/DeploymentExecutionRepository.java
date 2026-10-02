package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentApplicationGraph;

/** Stores original execution evidence before effects and commits publication with its formal outcome. / 副作用前保存原执行证据，并将发布与正式结果同事务提交。 */
public final class DeploymentExecutionRepository {
    /** Original task-bound business generation. / 原任务绑定业务代次。 */
    private final ModDatabase database;

    /** Binds the original author database. / 绑定原作者数据库。
     * @param database original capability / 原能力 */
    public DeploymentExecutionRepository(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Persists original resource intent before reservation; an exact retry never resets later evidence. / 在预留前保存原资源意图；精确重试不重置后续证据。
     * @param task original accepted task / 原接纳任务
     * @param identity immutable reviewed operation / 不可变审阅操作
     * @param operationType original operation name / 原操作名
     * @param references exact ordered resource associations / 精确有序资源关联
     * @param reviewedProject complete source facts actually reviewed before this request / 本请求前实际审阅的完整来源事实
     * @param createdAt original creation time / 原创建时间
     * @throws SQLException if identity conflicts or persistence fails / 身份冲突或持久化失败时
     * @throws ApiException if task access or durability is uncertain / 任务访问或持久性不确定时 */
    public void begin(TaskContext task, DeploymentExecutionIdentity identity, String operationType,
                      List<DeploymentReferenceIntent> references, Optional<DeploymentProjectRecord> reviewedProject,
                      Instant createdAt) throws SQLException, ApiException {
        requireTask(task, identity);
        Objects.requireNonNull(reviewedProject);
        var request = DeploymentExecutionCodec.request(identity, operationType, references, reviewedProject);
        var initial = new DeploymentExecutionRecord(
            identity, "PREPARED", Optional.empty(), request, DeploymentExecutionCodec.recovery(identity,
            DeploymentExecutionCodec.requestVersion(request), "PREPARED", Map.of()),
            Optional.empty(), false, false, millis(createdAt), millis(createdAt));
        DeploymentExecutionCodec.known(initial);
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            var existing = read(connection, identity.executionId(), true);
            if (existing.isPresent()) {
                var original = existing.get();
                DeploymentExecutionCodec.known(original);
                if (!original.identity().equals(identity) || !original.requestJson().equals(request)
                    || !original.createdAt().equals(initial.createdAt()))
                    throw new SQLException("Execution identity or request is immutable");
                return;
            }
            if (reviewedProject.isPresent()) {
                var current = DeploymentProjectRepository.read(connection, reviewedProject.get().id(), true)
                    .orElseThrow(() -> new SQLException("Reviewed project is absent"));
                if (!current.equals(reviewedProject.get()))
                    throw new SQLException("Project changed after source review");
            }
            try (var statement = connection.prepareStatement(
                "INSERT INTO deployment_execution(execution_id,platform_task_id,project_id,application_id,server_profile_id,mode,phase,reviewed_revision,request_payload,recovery_payload,created_at,updated_at) VALUES(?,?,?,?,?,?,'PREPARED',?,?,?,?,?)")) {
                statement.setObject(1, identity.executionId());
                statement.setObject(2, identity.taskId());
                statement.setObject(3, identity.projectId().orElse(null));
                statement.setString(4, identity.applicationId());
                statement.setString(5, identity.server().id());
                statement.setString(6, identity.mode().name());
                statement.setString(7, identity.reviewedRevision());
                statement.setBytes(8, bytes(request));
                statement.setBytes(9, bytes(initial.recoveryJson()));
                statement.setLong(10, initial.createdAt().toEpochMilli());
                statement.setLong(11, initial.updatedAt().toEpochMilli());
                statement.executeUpdate();
            }
        });
    }

    /** Reads retained evidence, preserving well-formed unknown JSON versions for inspection. / 读取保留证据，将合法未知JSON版本原样留作检查。
     * @param task active read context / 活动读取上下文
     * @param executionId original operation ID / 原操作ID
     * @return stored evidence or absence / 存储证据或不存在
     * @throws SQLException if stored data is malformed / 存储数据无效时
     * @throws ApiException if task access fails / 任务访问失败时 */
    public Optional<DeploymentExecutionRecord> find(TaskContext task, UUID executionId)
        throws SQLException, ApiException {
        try (var connection = connection(task)) {
            return read(connection, Objects.requireNonNull(executionId), false);
        }
    }

    /** Finds the original operation even when creation acknowledgement lost its business UUID. / 即使创建确认丢失业务UUID，仍可找到原操作。
     * @param task authentic original task or recovery context / 真实原任务或恢复上下文
     * @return original execution evidence or absence / 原执行证据或不存在
     * @throws SQLException if stored evidence is malformed / 存储证据无效时
     * @throws ApiException if task access is unavailable / 任务访问不可用时 */
    public Optional<DeploymentExecutionRecord> findForTask(TaskContext task) throws SQLException, ApiException {
        try (var connection = connection(task);
             var statement = connection
                 .prepareStatement("SELECT * FROM deployment_execution WHERE platform_task_id=?")) {
            statement.setObject(1, task.id());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        }
    }

    /** Reads the original reviewed project revision retained in a supported execution. / 读取受支持执行中保留的原已审阅项目修订。
     * @param record original retained execution / 原留存执行
     * @return frozen source facts or explicit absence / 冻结来源事实或明确不存在
     * @throws SQLException if the format or facts are unsupported / 格式或事实不受支持时 */
    public Optional<DeploymentProjectRecord> reviewedProject(DeploymentExecutionRecord record) throws SQLException {
        return DeploymentExecutionCodec.project(Objects.requireNonNull(record));
    }

    /** Reads a complete pre-publish business proposal only when the original execution saved one. / 仅在原执行已保存时读取完整发布前业务提案。
     * @param record original retained execution / 原留存执行
     * @return original proposal or explicit absence / 原提案或明确缺失
     * @throws SQLException if retained evidence is invalid / 留存依据无效时 */
    public Optional<DeploymentPublicationProposal> publicationTemplate(DeploymentExecutionRecord record)
        throws SQLException {
        return DeploymentExecutionCodec.publicationTemplate(Objects.requireNonNull(record));
    }

    /** Persists the exact non-secret business proposal before any publish dispatch. / 在任何发布发送前持久化精确非秘密业务提案。
     * @param task original admitted task / 原准入任务
     * @param executionId original execution / 原执行
     * @param publication complete original proposal / 完整原提案
     * @param observedAt durable boundary time / 持久边界时间
     * @throws SQLException if original facts conflict or SQL fails / 原事实冲突或SQL失败时
     * @throws ApiException if original task access or durability is unavailable / 原任务访问或持久性不可用时 */
    public void recordPublicationTemplate(TaskContext task, UUID executionId, DeploymentPublicationProposal publication,
                                          Instant observedAt) throws SQLException, ApiException {
        Objects.requireNonNull(publication);
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (!record.phase().equals("EXECUTING") || record.outcome().isPresent()
                || !record.identity().applicationId().equals(publication.graph().applicationId())
                || publication.graph().components().stream()
                .anyMatch(component -> !component.unit().server().equals(record.identity().server())))
                throw new SQLException("Publication proposal differs from original executing task");
            String request = DeploymentExecutionCodec.withPublicationTemplate(record, publication);
            if (request.equals(record.requestJson()))
                return;
            var next = new DeploymentExecutionRecord(record.identity(), record.phase(), record.outcome(), request,
                record.recoveryJson(), record.resultJson(), record.businessCommitted(), record.cleanupComplete(),
                record.createdAt(), millis(observedAt));
            if (!DeploymentExecutionCodec.known(next).intents().equals(checkpoint.intents()))
                throw new SQLException("Publication proposal changed original references");
            try (var statement = connection.prepareStatement(
                "UPDATE deployment_execution SET request_payload=?,updated_at=? WHERE execution_id=?")) {
                statement.setBytes(1, bytes(request));
                statement.setLong(2, next.updatedAt().toEpochMilli());
                statement.setObject(3, executionId);
                if (statement.executeUpdate() != 1)
                    throw new SQLException("Original publication proposal was not confirmed");
            }
        });
    }

    /** Persists full typed recovery consent before any environment recovery command. / 任一环境恢复命令前持久化完整类型化恢复同意。
     * @param task authentic original task / 真实原任务
     * @param executionId original execution / 原执行
     * @param intent complete explicitly reviewed recovery / 完整明确审阅恢复
     * @param observedAt nondecreasing evidence time / 非递减依据时刻
     * @throws SQLException if original phase or full consent conflicts / 原阶段或完整同意冲突时
     * @throws ApiException if original persistence is uncertain / 原持久确认不明时 */
    public void recordBuildIntent(TaskContext task, UUID executionId, DeploymentAssistedBuildIntent intent,
                                  Instant observedAt) throws SQLException, ApiException {
        Objects.requireNonNull(intent);
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (!record.phase().equals("EXECUTING") || record.outcome().isPresent() || record.businessCommitted()
                || record.cleanupComplete()
                || checkpoint.intents().stream()
                .noneMatch(value -> value.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SERVER
                    && value.businessKey().equals(DeploymentReferenceKey
                    .unit(record.identity().server(), intent.unitId()).businessKey())))
                throw new SQLException("Original environment recovery boundary is unavailable");
            String request = DeploymentExecutionCodec.withBuildIntent(record, intent);
            String recovery = DeploymentExecutionCodec.recovery(record.identity(),
                DeploymentExecutionCodec.requestVersion(request), record.phase(), checkpoint.ids());
            var next = new DeploymentExecutionRecord(record.identity(), record.phase(), record.outcome(), request,
                recovery, record.resultJson(), false, false, record.createdAt(), millis(observedAt));
            if (!DeploymentExecutionCodec.known(next).equals(checkpoint))
                throw new SQLException("Recovery intent changed original reservations");
            try (var update = connection.prepareStatement(
                "UPDATE deployment_execution SET request_payload=?,recovery_payload=?,updated_at=? WHERE execution_id=? AND reviewed_revision=? AND phase='EXECUTING' AND outcome IS NULL")) {
                update.setBytes(1, bytes(request));
                update.setBytes(2, bytes(recovery));
                update.setLong(3, next.updatedAt().toEpochMilli());
                update.setObject(4, executionId);
                update.setString(5, record.identity().reviewedRevision());
                if (update.executeUpdate() != 1)
                    throw new SQLException("Original recovery intent CAS was not confirmed");
            }
        });
    }

    /** Reads the existing full intent without granting automatic replay. / 读取已有完整意图，不授予自动重放。
     * @param record authenticated original record / 已认证原记录
     * @return retained original intent / 留存原意图
     * @throws SQLException if complete framing conflicts / 完整信封冲突时 */
    public Optional<DeploymentAssistedBuildIntent> buildIntent(DeploymentExecutionRecord record) throws SQLException {
        return DeploymentExecutionCodec.buildIntent(record);
    }

    /** Atomically adopts one explicitly approved build-only review under the original reserved execution. / 在原已预留执行下原子采纳一份明确审批的仅构建审阅。
     * @param task authentic original task / 真实原任务
     * @param executionId original execution / 原执行
     * @param review complete old/new review and physical failure proof / 完整旧新审阅及物理失败证明
     * @param observedAt nondecreasing evidence time / 非递减依据时间
     * @throws SQLException if original identity, immutable stage or CAS conflicts / 原身份、不可变阶段或CAS冲突时
     * @throws ApiException if original persistence is unavailable / 原持久性不可用时 */
    public void adoptBuildReview(TaskContext task, UUID executionId, DeploymentAssistedBuildReview review,
                                 Instant observedAt) throws SQLException, ApiException {
        Objects.requireNonNull(review);
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (!record.phase().equals("EXECUTING") || record.outcome().isPresent() || record.businessCommitted()
                || record.cleanupComplete() || !record.identity().reviewedRevision().equals(review.previousRoot())
                || checkpoint.intents().stream().noneMatch(
                intent -> intent.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SERVER
                    && intent.businessKey().equals(DeploymentReferenceKey
                    .unit(record.identity().server(), review.unitId()).businessKey())))
                throw new SQLException("Original build review boundary is no longer available");
            String request = DeploymentExecutionCodec.withBuildReview(record, review);
            var old = record.identity();
            var identity = new DeploymentExecutionIdentity(old.executionId(), old.taskId(), old.projectId(),
                old.applicationId(), old.server(), old.mode(), review.nextRoot());
            var recovery = DeploymentExecutionCodec.recovery(identity, DeploymentExecutionCodec.requestVersion(request),
                record.phase(), checkpoint.ids());
            var next = new DeploymentExecutionRecord(identity, record.phase(), record.outcome(), request, recovery,
                record.resultJson(), false, false, record.createdAt(), millis(observedAt));
            if (!DeploymentExecutionCodec.known(next).equals(checkpoint))
                throw new SQLException("Build review changed original reserved resources");
            try (var update = connection
                .prepareStatement("UPDATE deployment_execution SET reviewed_revision=?,request_payload=?,"
                    + "recovery_payload=?,updated_at=? WHERE execution_id=? AND reviewed_revision=? AND phase='EXECUTING' AND outcome IS NULL")) {
                update.setString(1, review.nextRoot());
                update.setBytes(2, bytes(request));
                update.setBytes(3, bytes(recovery));
                update.setLong(4, next.updatedAt().toEpochMilli());
                update.setObject(5, executionId);
                update.setString(6, review.previousRoot());
                if (update.executeUpdate() != 1)
                    throw new SQLException("Original build review CAS was not confirmed");
            }
        });
    }

    /** Reads complete persisted build reviews for readonly original recovery. / 为只读原恢复读取完整持久构建审阅。
     * @param record authenticated original record / 已认证原记录
     * @return bounded immutable reviews / 有界不可变审阅
     * @throws SQLException if original evidence is malformed / 原依据无效时 */
    public List<DeploymentAssistedBuildReview> buildReviews(DeploymentExecutionRecord record) throws SQLException {
        return DeploymentExecutionCodec.buildReviews(record);
    }

    /** Returns supported exact associations with any already observed reservation UUID. / 返回受支持的精确关联及已观察预留UUID。
     * @param record original retained row / 原保留行
     * @return ordered immutable intent-to-optional-UUID map / 有序不可变意图到可选UUID映射
     * @throws SQLException if the format is unsupported or malformed / 格式不支持或无效时 */
    public Map<DeploymentReferenceIntent, Optional<UUID>> references(DeploymentExecutionRecord record)
        throws SQLException {
        var checkpoint = DeploymentExecutionCodec.known(record);
        var result = new LinkedHashMap<DeploymentReferenceIntent, Optional<UUID>>();
        for (int i = 0; i < checkpoint.intents().size(); i++)
            result.put(checkpoint.intents().get(i), Optional.ofNullable(checkpoint.ids().get(i)));
        return Collections.unmodifiableMap(result);
    }

    /** Saves one original reservation UUID without replacing another observed identity. / 保存一个原预留UUID，不替换另一已观察身份。
     * @param task original accepted task / 原接纳任务
     * @param executionId original operation ID / 原操作ID
     * @param intent original persisted association / 原已持久关联
     * @param referenceId observed original reservation / 已观察原预留
     * @param observedAt actual evidence time / 实际证据时间
     * @throws SQLException if identity or stage conflicts / 身份或阶段冲突时
     * @throws ApiException if task access or durability is uncertain / 任务访问或持久性不确定时 */
    public void recordReference(TaskContext task, UUID executionId, DeploymentReferenceIntent intent, UUID referenceId,
                                Instant observedAt) throws SQLException, ApiException {
        Objects.requireNonNull(referenceId);
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (!record.phase().equals("PREPARED") || record.outcome().filter(o -> o != Outcome.UNKNOWN).isPresent())
                throw new SQLException("References can only be recorded before effects");
            int index = checkpoint.intents().indexOf(intent);
            if (index < 0)
                throw new SQLException("Reference intent was not originally recorded");
            var ids = new TreeMap<>(checkpoint.ids());
            var previous = ids.putIfAbsent(index, referenceId);
            if (previous != null) {
                if (!previous.equals(referenceId))
                    throw new SQLException("Original reservation identity is immutable");
                return;
            }
            update(connection, record, checkpoint, ids, record.phase(), record.outcome(), record.resultJson(), false,
                false, observedAt);
        });
    }

    /** Persists the effect boundary only after every original reservation is known. / 仅在全部原预留已知后持久化副作用边界。
     * @param task original task / 原任务
     * @param executionId original operation / 原操作
     * @param observedAt boundary time / 边界时间
     * @throws SQLException if references are missing or the operation already advanced / 引用缺失或操作已推进时
     * @throws ApiException if task access or durability is uncertain / 任务访问或持久性不确定时 */
    public void startEffects(TaskContext task, UUID executionId, Instant observedAt) throws SQLException, ApiException {
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (!record.phase().equals("PREPARED") || record.outcome().isPresent()
                || checkpoint.ids().size() != checkpoint.intents().size())
                throw new SQLException("Execution cannot start or replay effects");
            update(connection, record, checkpoint, checkpoint.ids(), "EXECUTING", Optional.empty(), Optional.empty(),
                false, false, observedAt);
        });
    }

    /** Atomically saves all component publications and their formal business result. / 原子保存全部组件发布及其正式业务结果。
     * @param task original task / 原任务
     * @param executionId original operation / 原操作
     * @param graph complete observed application graph / 完整已观察应用图
     * @param publications complete observed component records / 完整已观察组件记录
     * @param observedAt successful observation time / 成功观测时间
     * @throws SQLException if facts conflict or any business write fails / 事实冲突或任一业务写入失败时
     * @throws ApiException if task access or transaction acknowledgement is uncertain / 任务访问或事务确认不确定时 */
    public void commitApplication(TaskContext task, UUID executionId, DeploymentApplicationGraph graph,
                                  List<DeploymentUnitCommit> publications, Instant observedAt) throws SQLException, ApiException {
        var records = List.copyOf(publications);
        Objects.requireNonNull(graph);
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (!record.phase().equals("EXECUTING") || record.businessCommitted()
                || record.outcome().filter(o -> o != Outcome.UNKNOWN).isPresent()
                || !record.identity().applicationId().equals(graph.applicationId())
                || graph.components().stream().anyMatch(c -> !c.unit().server().equals(record.identity().server())))
                throw new SQLException("Publication differs from the original unfinished operation");
            requireSecretIntents(connection, record.identity(), records, checkpoint);
            DeploymentPublicationRepository.save(connection, graph, records, observedAt);
            var release = DeploymentReleaseRepository.activate(connection, record.identity(),
                DeploymentReleaseSnapshot.capture(graph, records), observedAt);
            var result = DeploymentExecutionCodec.result(record.identity(), DeploymentExecutionCodec.version(record),
                Outcome.SUCCEEDED, Optional.empty(), "DEPLOYMENT_PUBLICATION_COMMITTED", records,
                Optional.of(release));
            update(connection, record, checkpoint, checkpoint.ids(), "COMMITTED", Optional.of(Outcome.SUCCEEDED),
                Optional.of(result), true, false, observedAt);
        });
    }

    /** Requires every published secret identity to have been included before effects. / 要求每个发布秘密身份在副作用前已被包含。
     * @param connection original transaction / 原事务
     * @param identity exact original server scope / 精确原服务器作用域
     * @param records complete component publications / 完整组件发布
     * @param checkpoint original reserved associations / 原预留关联
     * @throws SQLException if a secret lacks original reservation evidence / 秘密缺少原预留证据时 */
    private static void requireSecretIntents(Connection connection, DeploymentExecutionIdentity identity,
                                             List<DeploymentUnitCommit> records, DeploymentExecutionCodec.Checkpoint checkpoint) throws SQLException {
        for (var record : records)
            for (var reference : record.secrets()) {
                var revision = DeploymentSecretRepository.readRevision(connection, reference)
                    .orElseThrow(() -> new SQLException("Publication secret revision is absent"));
                if (checkpoint.intents().stream()
                    .noneMatch(intent -> intent.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SECRET
                        && intent.resourceId().equals(revision.credentialId())
                        && intent.businessKey().equals(DeploymentReferenceKey
                        .secretRevision(identity.server(),
                            new gold.debug.windowstolinux.appapi.secret.SecretRef(
                                intent.resourceId(), intent.purpose().orElseThrow()),
                            reference)
                        .businessKey())))
                    throw new SQLException("Publication secret was not in the original reserved intent");
            }
    }

    /** Records a checked failure, cancellation or unresolved result without claiming business commit. / 记录核实失败、取消或未决结果，不声称业务提交。
     * @param task original task / 原任务
     * @param executionId original operation / 原操作
     * @param outcome checked non-success result / 已核实非成功结果
     * @param problem original bounded public failure category and event / 原有界公开失败类别及事件
     * @param observedAt observation time / 观测时间
     * @throws SQLException if a known result would be overwritten / 将覆盖已知结果时
     * @throws ApiException if task access or durability is uncertain / 任务访问或持久性不确定时 */
    public void recordUncommittedOutcome(TaskContext task, UUID executionId, Outcome outcome, Problem problem,
                                         Instant observedAt) throws SQLException, ApiException {
        if (outcome == null || outcome == Outcome.SUCCEEDED)
            throw new IllegalArgumentException("Success requires atomic publication");
        Objects.requireNonNull(problem);
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (record.businessCommitted() || record.cleanupComplete()
                || record.outcome().filter(o -> o != Outcome.UNKNOWN).isPresent())
                throw new SQLException("Known business outcome is immutable");
            var result = DeploymentExecutionCodec.result(record.identity(), DeploymentExecutionCodec.version(record),
                outcome, Optional.of(problem.code()), problem.eventCode(), List.of(), Optional.empty());
            update(connection, record, checkpoint, checkpoint.ids(), record.phase(), Optional.of(outcome),
                Optional.of(result), false, false, observedAt);
        });
    }

    /** Reads the retained public failure category and event without inventing the old message. / 读取留存公开失败类别及事件，不补造旧消息。
     * @param record supported original result / 受支持原结果
     * @return retained problem metadata or absence / 留存问题元数据或空
     * @throws SQLException if its format is unsupported / 格式不受支持时 */
    public Optional<Problem> problem(DeploymentExecutionRecord record) throws SQLException {
        return DeploymentExecutionCodec.problem(record);
    }

    /** Marks actual cleanup only after a known formal result; uncertainty never becomes finished. / 仅在正式结果已知后标记实际收尾，未知结果不能结束。
     * @param task original task / 原任务
     * @param executionId original operation / 原操作
     * @param observedAt cleanup confirmation time / 收尾确认时间
     * @throws SQLException if the business result remains unknown / 业务结果仍未知时
     * @throws ApiException if task access or durability is uncertain / 任务访问或持久性不确定时 */
    public void finishCleanup(TaskContext task, UUID executionId, Instant observedAt)
        throws SQLException, ApiException {
        mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            if (record.outcome().isEmpty() || record.outcome().get() == Outcome.UNKNOWN)
                throw new SQLException("Unknown business outcome cannot finish");
            if (!record.cleanupComplete())
                update(connection, record, checkpoint, checkpoint.ids(), "FINISHED", record.outcome(),
                    record.resultJson(), record.businessCommitted(), true, observedAt);
        });
    }

    /** Locks and changes only the exact original task's supported execution. / 仅锁定并更改精确原任务的受支持执行。
     * @param task original task / 原任务
     * @param executionId original operation / 原操作
     * @param time actual nondecreasing evidence time / 实际非递减证据时间
     * @param change original bounded change / 原有界变更
     * @throws SQLException if identity, time or SQL fails / 身份、时间或SQL失败时
     * @throws ApiException if task access or durability is uncertain / 任务访问或持久性不确定时 */
    void mutate(TaskContext task, UUID executionId, Instant time, Change change) throws SQLException, ApiException {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(time);
        var connection = connection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            var record = read(connection, executionId, true)
                .orElseThrow(() -> new SQLException("Original execution is absent"));
            requireTask(task, record.identity());
            if (millis(time).isBefore(record.updatedAt()))
                throw new SQLException("Execution evidence time cannot move backwards");
            change.apply(connection, record, DeploymentExecutionCodec.known(record));
        });
    }

    /** Persists an already validated transition while retaining the original immutable request. / 保留原不可变请求并持久化已经校验的转换。
     * @param connection original transaction / 原事务
     * @param record original row / 原行
     * @param checkpoint validated original checkpoint / 已校验原检查点
     * @param ids observed original UUIDs / 已观察原UUID
     * @param phase new evidence stage / 新证据阶段
     * @param outcome observed result / 已观察结果
     * @param result formal result document / 正式结果文档
     * @param committed atomic business commit flag / 原子业务提交标志
     * @param cleaned actual cleanup confirmation / 实际收尾确认
     * @param time actual evidence time / 实际证据时间
     * @throws SQLException if new evidence or SQL is invalid / 新证据或SQL无效时 */
    private static void update(Connection connection, DeploymentExecutionRecord record,
                               DeploymentExecutionCodec.Checkpoint checkpoint, Map<Integer, UUID> ids, String phase,
                               Optional<Outcome> outcome, Optional<String> result, boolean committed, boolean cleaned, Instant time)
        throws SQLException {
        var recovery = DeploymentExecutionCodec.recovery(record.identity(), DeploymentExecutionCodec.version(record),
            phase, ids);
        var next = new DeploymentExecutionRecord(record.identity(), phase, outcome, record.requestJson(), recovery,
            result, committed, cleaned, record.createdAt(), millis(time));
        if (!DeploymentExecutionCodec.known(next).intents().equals(checkpoint.intents()))
            throw new SQLException("Execution intents changed");
        try (var statement = connection.prepareStatement(
            "UPDATE deployment_execution SET phase=?,outcome=?,recovery_payload=?,result_payload=?,business_committed=?,cleanup_complete=?,updated_at=? WHERE execution_id=?")) {
            statement.setString(1, phase);
            statement.setString(2, outcome.map(Enum::name).orElse(null));
            statement.setBytes(3, bytes(recovery));
            statement.setBytes(4, result.map(DeploymentExecutionRepository::bytes).orElse(null));
            statement.setBoolean(5, committed);
            statement.setBoolean(6, cleaned);
            statement.setLong(7, next.updatedAt().toEpochMilli());
            statement.setObject(8, record.identity().executionId());
            if (statement.executeUpdate() != 1)
                throw new SQLException("Execution transition was not confirmed");
        }
    }

    /** Reads a single original row, optionally inside an existing write transaction. / 读取一个原行，可选在已有写事务中加锁。
     * @param connection original connection / 原连接
     * @param executionId original operation / 原操作
     * @param lock whether this transaction mutates the row / 本事务是否变更该行
     * @return validated row or absence / 已校验行或不存在
     * @throws SQLException if data or JSON cannot be read / 数据或JSON无法读取时 */
    static Optional<DeploymentExecutionRecord> read(Connection connection, UUID executionId, boolean lock)
        throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT * FROM deployment_execution WHERE execution_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setObject(1, executionId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        }
    }

    /** Decodes bounded original evidence and retains unknown versions without interpreting them. / 解码有界原证据，并保留未知版本而不解释。
     * @param row current stored row / 当前存储行
     * @return original immutable evidence / 原不可变证据
     * @throws SQLException if columns or supported payloads are invalid / 列或受支持载荷无效时 */
    static DeploymentExecutionRecord row(ResultSet row) throws SQLException {
        try {
            var identity = new DeploymentExecutionIdentity(row.getObject("execution_id", UUID.class),
                row.getObject("platform_task_id", UUID.class),
                Optional.ofNullable(row.getObject("project_id", UUID.class)), row.getString("application_id"),
                new ServerRef(row.getString("server_profile_id")),
                DeploymentExecutionIdentity.DeploymentExecutionMode.valueOf(row.getString("mode")),
                row.getString("reviewed_revision"));
            byte[] result = DeploymentPayloadReader.read(row, "result_payload");
            var record = new DeploymentExecutionRecord(identity, row.getString("phase"),
                Optional.ofNullable(row.getString("outcome")).map(Outcome::valueOf),
                DeploymentJsonDocument.text(
                    DeploymentPayloadReader.read(row, "request_payload", DeploymentExecutionCodec.READ_LIMIT),
                    DeploymentExecutionCodec.READ_LIMIT),
                DeploymentJsonDocument.text(DeploymentPayloadReader.read(row, "recovery_payload")),
                result == null ? Optional.empty() : Optional.of(DeploymentJsonDocument.text(result)),
                row.getBoolean("business_committed"), row.getBoolean("cleanup_complete"),
                Instant.ofEpochMilli(row.getLong("created_at")), Instant.ofEpochMilli(row.getLong("updated_at")));
            if (DeploymentExecutionCodec.supported(record))
                DeploymentExecutionCodec.known(record);
            return record;
        } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Stored execution evidence is invalid", invalid);
        }
    }

    /** Validates supported records using only SELECT and leaves future versions unchanged. / 仅使用SELECT校验受支持记录，未来版本保持原样。
     * @param connection original SELECT-only connection / 原仅SELECT连接
     * @throws SQLException if any retained evidence is malformed / 任一保留证据无效时 */
    static void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM deployment_execution")) {
            while (rows.next())
                row(rows);
        }
    }

    /** Opens only the exact owned business generation. / 仅打开精确所属业务代次。
     * @param task original active context / 原活动上下文
     * @return original managed connection / 原受管连接
     * @throws SQLException if JDBC is unavailable / JDBC不可用时
     * @throws ApiException if access is unavailable / 访问不可用时 */
    private Connection connection(TaskContext task) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        return database.openConnection(task);
    }

    /** Prevents another task from updating retained original work. / 防止另一任务更改保留原工作。
     * @param task supplied active context / 提供的活动上下文
     * @param identity original operation identity / 原操作身份
     * @throws SQLException if task identity differs / 任务身份不符时 */
    private static void requireTask(TaskContext task, DeploymentExecutionIdentity identity) throws SQLException {
        if (task == null || !task.id().equals(identity.taskId()))
            throw new SQLException("Execution belongs to another original task");
    }

    /** Uses the database's exact millisecond precision. / 使用数据库精确毫秒精度。
     * @param time original observation / 原观测
     * @return millisecond time / 毫秒时间 */
    private static Instant millis(Instant time) {
        return Instant.ofEpochMilli(Objects.requireNonNull(time).toEpochMilli());
    }

    /** Encodes already validated bounded JSON. / 编码已经校验的有界JSON。
     * @param text validated original document / 已校验原文档
     * @return UTF-8 bytes / UTF-8字节 */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** One original mutation inside the already owned transaction. / 已持有事务内的一次原变更。 */
    @FunctionalInterface
    interface Change {
        /** Applies a checked transition. / 应用已校验转换。
         * @param connection original transaction / 原事务
         * @param record original row / 原行
         * @param checkpoint validated original facts / 已校验原事实
         * @throws SQLException if persistence or facts fail / 持久化或事实失败时 */
        void apply(Connection connection, DeploymentExecutionRecord record,
                   DeploymentExecutionCodec.Checkpoint checkpoint) throws SQLException;
    }
}
