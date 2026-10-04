package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Advances bounded operation consent through the same original row lock and reserved formal execution. / 经同一原行锁及已预留正式执行推进有界操作同意。 */
public final class DeploymentPublishAttemptStore {
    /** Existing original execution transaction owner. / 既有原执行事务所有者。 */
    private final DeploymentExecutionStore executions;

    /** Reuses the existing exact transaction and task authorization. / 复用既有精确事务及任务权限。
     * @param database original Mod generation / 原Mod代次 */
    public DeploymentPublishAttemptStore(ModDatabase database) {
        executions = new DeploymentExecutionStore(database);
    }

    /** Stores complete original restoration and human consent before any new fixed environment action. / 任一新固定环境动作前存储完整原恢复及人工同意。
     * @param task authentic unchanged original task / 真实不变原任务
     * @param executionId original unique formal execution / 原唯一正式执行
     * @param intent complete explicit typed consent / 完整明确类型化同意
     * @param proof complete actual prior cleanup / 完整实际原清理
     * @param observedAt nondecreasing durable evidence time / 非递减持久依据时间
     * @throws SQLException if original phase, unit, source or CAS differs / 原阶段、单元、来源或CAS不符时
     * @throws ApiException if original task access or durability is unavailable / 原任务访问或持久性不可用时 */
    public void recordIntent(TaskContext task, UUID executionId, DeploymentPublishAttemptIntent intent,
                             DeploymentPublishAttemptProof proof, Instant observedAt) throws SQLException, ApiException {
        Objects.requireNonNull(intent);
        Objects.requireNonNull(proof);
        executions.mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            requireExecuting(record, checkpoint, intent.unitId());
            if (!proof.task().equals(task.id()) || !intent.previousRoot().equals(record.identity().reviewedRevision()))
                throw new SQLException("Publish recovery changed its authentic original task or root");
            String request = DeploymentPublishAttemptCodec.withIntent(record, intent, proof);
            store(connection, record, checkpoint, request, record.identity(), observedAt);
        });
    }

    /** Atomically adopts only a genuinely changed exact final review under the same formal execution. / 在同一正式执行下仅原子采纳真实变化的精确最终审阅。
     * @param task authentic original task / 真实原任务
     * @param executionId original unique execution / 原唯一执行
     * @param review complete actual observed and approved review / 完整实际观察并审批审阅
     * @param observedAt nondecreasing evidence time / 非递减依据时间
     * @throws SQLException if pending intent, original resources or CAS conflict / 待定意图、原资源或CAS冲突时
     * @throws ApiException if original durability or access is uncertain / 原持久性或访问不明时 */
    public void adoptReview(TaskContext task, UUID executionId, DeploymentPublishAttemptReview review,
                            Instant observedAt) throws SQLException, ApiException {
        Objects.requireNonNull(review);
        executions.mutate(task, executionId, observedAt, (connection, record, checkpoint) -> {
            requireExecuting(record, checkpoint, review.unitId());
            if (!record.identity().reviewedRevision().equals(review.previousRoot()))
                throw new SQLException("Original publication root changed before final review");
            var old = record.identity();
            var identity = new DeploymentExecutionIdentity(old.executionId(), old.taskId(), old.projectId(), old.applicationId(), old.server(), old.mode(), review.nextRoot());
            store(connection, record, checkpoint, DeploymentPublishAttemptCodec.withReview(record, review), identity, observedAt);
        });
    }

    /** Reads the current explicit operation without granting any recovery execution. / 读取当前明确操作，不授予任一恢复执行。
     * @param record authentic original durable row / 真实原持久行
     * @return actual current operation / 实际当前操作
     * @throws SQLException if original full request conflicts / 原完整请求冲突时 */
    public UUID operation(DeploymentExecutionRecord record) throws SQLException {
        DeploymentExecutionCodec.known(record);
        return DeploymentPublishAttemptCodec.operation(parse(record.requestJson(), DeploymentExecutionCodec.READ_LIMIT));
    }

    /** Reads the complete predecessor of the last formally consumed operation for pointer recovery only. / 仅为指针恢复读取最近正式已消费操作的完整前驱。
     * @param record authentic original durable row / 真实原持久行
     * @return exact immutable predecessor / 精确不可变前驱
     * @throws SQLException if no consumed transition or predecessor exists / 不存在已消费转换或前驱时 */
    public DeploymentPublishProposal previousTemplate(DeploymentExecutionRecord record) throws SQLException {
        DeploymentExecutionCodec.known(record);
        var request = parse(record.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        int size = array(request, "attemptReviews", 2).size();
        if (size == 0) throw new SQLException("Original operation has no consumed predecessor");
        return DeploymentPublishAttemptCodec.retired(request, size - 1, record.identity().executionId());
    }

    /** Hashes the actual current complete formal template using its original canonical codec. / 使用原规范codec哈希实际当前完整正式模板。
     * @param record authentic original row / 真实原行
     * @return complete current template SHA / 完整当前模板SHA
     * @throws SQLException if the original template is absent or unconfirmed / 原模板缺失或未确认时 */
    public String templateSha256(DeploymentExecutionRecord record) throws SQLException {
        DeploymentExecutionCodec.known(record);
        var actual = DeploymentExecutionCodec.publicationTemplate(record).orElseThrow(() -> new SQLException("Original current template is absent"));
        return DeploymentPublishAttemptProof.digest(DeploymentPublishTemplateCodec.encodeTemplate(actual));
    }

    /** Reads all actual consumed corrections across the current and retired operations. / 跨当前及已退出操作读取所有实际已消费纠正。
     * @param record authentic original durable row / 真实原持久行
     * @return bounded original correction count / 有界原纠正次数
     * @throws SQLException if original full request conflicts / 原完整请求冲突时 */
    public int retries(DeploymentExecutionRecord record) throws SQLException {
        DeploymentExecutionCodec.known(record);
        return DeploymentPublishAttemptCodec.retries(parse(record.requestJson(), DeploymentExecutionCodec.READ_LIMIT));
    }

    /** Reads retained full pending consent only for observation and confirmed cleanup. / 仅为观察及已确认清理读取留存完整待定同意。
     * @param record authentic original durable row / 真实原持久行
     * @return full original consent or absence / 完整原同意或空值
     * @throws SQLException if original full request conflicts / 原完整请求冲突时 */
    public Optional<DeploymentPublishAttemptIntent> intent(DeploymentExecutionRecord record) throws SQLException {
        DeploymentExecutionCodec.known(record);
        var request = parse(record.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        return !request.path("attemptIntent").isNull()
            ? Optional.of(DeploymentPublishAttemptIntent.decode(request.path("attemptIntent"))) : Optional.empty();
    }

    /** Reads exact final review pointers for original task recovery without continuing a new build. / 为原任务恢复读取精确最终审阅指针，不继续新构建。
     * @param record authentic original row / 真实原行
     * @return immutable bounded reviews / 不可变有界审阅
     * @throws SQLException if original full request conflicts / 原完整请求冲突时 */
    public List<DeploymentPublishAttemptReview> reviews(DeploymentExecutionRecord record) throws SQLException {
        DeploymentExecutionCodec.known(record);
        var request = parse(record.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        var reviews = new ArrayList<DeploymentPublishAttemptReview>();
        for (var entry : array(request, "attemptReviews", 2))
            reviews.add(DeploymentPublishAttemptReview.decode(entry));
        return List.copyOf(reviews);
    }

    /** Requires the old formal execution to remain uncommitted with its exact original unit reservation. / 要求旧正式执行保持未提交并持有精确原单元预留。
     * @param record original locked row / 原已锁行
     * @param checkpoint exact original reserved references / 精确原已预留引用
     * @param unit actual original unit / 实际原单元
     * @throws SQLException if a final outcome or original unit differs / 最终结果或原单元不符时 */
    private static void requireExecuting(DeploymentExecutionRecord record, DeploymentExecutionCodec.Checkpoint checkpoint, String unit) throws SQLException {
        if (!record.phase().equals("EXECUTING") || record.outcome().isPresent() || record.businessCommitted() || record.cleanupComplete()
            || record.identity().mode() != DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED
            || checkpoint.intents().stream().noneMatch(value -> value.kind() == DeploymentRefIntent.DeploymentRefKind.SERVER
            && value.businessKey().equals(DeploymentRefKey.unit(record.identity().server(), unit).businessKey())))
            throw new SQLException("Original publication cannot revive a completed execution or replace its unit reservation");
    }

    /** Persists an exact request and recovery identity in the already held original transaction. / 在已持有原事务中持久化精确请求及恢复身份。
     * @param connection original row-lock transaction / 原行锁事务
     * @param record original current row / 原当前行
     * @param checkpoint exact original refs / 精确原引用
     * @param request complete explicitly versioned request / 完整明确版本化请求
     * @param identity actual confirmed identity / 实际已确认身份
     * @param observedAt original nondecreasing time / 原非递减时刻
     * @throws SQLException if full validation or row CAS is unconfirmed / 完整校验或行CAS不明时 */
    private static void store(Connection connection, DeploymentExecutionRecord record, DeploymentExecutionCodec.Checkpoint checkpoint,
                              String request, DeploymentExecutionIdentity identity, Instant observedAt) throws SQLException {
        var time = observedAt.truncatedTo(ChronoUnit.MILLIS);
        String recovery = DeploymentExecutionCodec.recovery(identity, record.phase(), checkpoint.ids());
        var next = new DeploymentExecutionRecord(identity, record.phase(), record.outcome(), request, recovery, record.resultJson(), false, false, record.createdAt(), time);
        if (!DeploymentExecutionCodec.known(next).equals(checkpoint))
            throw new SQLException("New operation changed original exact reserved references");
        try (var update = connection.prepareStatement("UPDATE deployment_execution SET reviewed_revision=?,request_payload=?,recovery_payload=?,updated_at=? "
            + "WHERE execution_id=? AND platform_task_id=? AND reviewed_revision=? AND phase='EXECUTING' AND outcome IS NULL AND business_committed=FALSE AND cleanup_complete=FALSE")) {
            update.setString(1, identity.reviewedRevision());
            update.setBytes(2, request.getBytes(StandardCharsets.UTF_8));
            update.setBytes(3, recovery.getBytes(StandardCharsets.UTF_8));
            update.setLong(4, time.toEpochMilli());
            update.setObject(5, identity.executionId());
            update.setObject(6, identity.taskId());
            update.setString(7, record.identity().reviewedRevision());
            if (update.executeUpdate() != 1)
                throw new SQLException("Original publication operation CAS was not confirmed");
        }
    }
}
