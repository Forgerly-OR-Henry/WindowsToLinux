package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentRemoteReleaseSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;

/** Keeps original remote candidate and cleanup facts occupied until every actual cleanup is confirmed. / 保留原远端候选和清理依据，直至所有实际清理均已确认。 */
final class DeploymentPublicationCleanupJournal {
    /** Original task-local author evidence key. / 原任务内部作者依据键。 */
    static final String KEY = "deployment-publication-remote";

    /** Bounded JSON writer; no secret values enter this journal. / 有界JSON写入器；秘密值不进入此账本。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Original durable occupancy. / 原持久占用。 */
    private final TaskCheckpoint checkpoint;

    /** Last confirmed CAS revision. / 上次已确认的CAS修订。 */
    private TaskCheckpoint.Snapshot snapshot;

    /** Immutable original identity and latest known phase. / 不变原身份及最近已知阶段。 */
    private final com.fasterxml.jackson.databind.node.ObjectNode facts;

    /** Whether the most recent advance is durably confirmed. / 最近推进是否已获持久确认。 */
    private boolean confirmed = true;

    /** Actual remote cleanup was confirmed before local resource release. / 本地资源释放前已确认实际远端清理。 */
    private boolean remoteClean;

    /** Borrowed session was actually closed. / 借用会话已实际关闭。 */
    private boolean sessionClosed;

    /** Binds an already persisted checkpoint. / 绑定已持久化的检查点。
     * @param checkpoint original occupancy / 原占用
     * @param facts original identity / 原身份
     * @throws ApiException if the original checkpoint cannot be read / 原检查点不可读取时 */
    private DeploymentPublicationCleanupJournal(TaskCheckpoint checkpoint,
            com.fasterxml.jackson.databind.node.ObjectNode facts) throws ApiException {
        this.checkpoint = checkpoint;
        this.snapshot = checkpoint.snapshot();
        this.facts = facts;
    }

    /** Persists the candidate identity before any candidate or release request can be sent. / 在任何候选或发布请求可能发送前持久化候选身份。
     * @param context original Mod capabilities / 原Mod能力
     * @param task original task / 原任务
     * @param unit exact reviewed owner / 精确已审阅归属
     * @param archiveSha256 original tar-byte digest / 原tar字节摘要
     * @param planSha256 original reviewed plan / 原已审阅计划摘要
     * @param runtime original reviewed backend and engine / 原已审阅后端与引擎
     * @return occupied original cleanup journal / 已占用原清理账本
     * @throws ApiException if the original evidence cannot be confirmed / 原依据无法确认时 */
    static DeploymentPublicationCleanupJournal begin(ModContext context, TaskContext task, DeploymentUnitIdentity unit,
            String archiveSha256, String planSha256, DeploymentRuntimeDefinition runtime) throws ApiException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(task);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(runtime);
        var facts = JSON.createObjectNode();
        facts.put("version", 2);
        facts.put("task", task.id().toString());
        facts.put("server", unit.server().id());
        facts.put("application", unit.id());
        facts.put("ownerManifestSha256", unit.ownershipManifestSha256());
        facts.put("releaseRoot", unit.releaseRoot());
        facts.put("archiveSha256", archiveSha256);
        facts.put("candidate", new DeploymentRemoteWorkspace(unit.id(), archiveSha256).candidateId());
        facts.put("planSha256", planSha256);
        if (runtime instanceof DeploymentRuntimeDefinition.Container container) {
            facts.put("backend", "container");
            facts.put("engine", container.engine().name().toLowerCase(java.util.Locale.ROOT));
        } else {
            facts.put("backend", "native");
            facts.put("engine", "none");
        }
        facts.put("phase", "CANDIDATE_MAY_EXIST");
        var checkpoint = context.tasks().beginCheckpoint(task, KEY, facts.toString());
        return new DeploymentPublicationCleanupJournal(checkpoint, facts);
    }

    /** Reopens only an existing original version-two checkpoint; old incomplete formats remain unknown. / 仅重开已存在的原第二版检查点；旧缺失格式仍未知。
     * @param context original Mod activation / 原Mod启用
     * @param task authentic original recovery task / 真实原恢复任务
     * @return original journal or absence for a reference-only execution / 原账本或纯引用执行的空值
     * @throws ApiException if original task access fails / 原任务访问失败时
     * @throws IllegalArgumentException if retained facts are incomplete / 留存事实不完整时 */
    static Optional<DeploymentPublicationCleanupJournal> reopen(ModContext context, TaskContext task)
            throws ApiException {
        var found = context.tasks().findCheckpoint(task, KEY);
        if (found.isEmpty())
            return Optional.empty();
        var checkpoint = found.orElseThrow();
        var snapshot = checkpoint.snapshot();
        try {
            JsonNode parsed = JSON.readTree(snapshot.payload());
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode facts)
                    || !facts.path("version").isInt() || facts.path("version").intValue() != 2
                    || !task.id().toString().equals(facts.path("task").textValue()) || !facts.path("phase").isTextual())
                throw new IllegalArgumentException("Original publication checkpoint is unsupported");
            var journal = new DeploymentPublicationCleanupJournal(checkpoint, facts);
            journal.remoteClean = java.util.Set.of("REMOTE_CLEANUP_VERIFIED", "SESSION_CLOSED", "CLEANUP_VERIFIED")
                    .contains(facts.path("phase").textValue());
            journal.sessionClosed = java.util.Set.of("SESSION_CLOSED", "CLEANUP_VERIFIED")
                    .contains(facts.path("phase").textValue());
            return Optional.of(journal);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Original publication checkpoint is malformed", invalid);
        }
    }

    /** Returns a detached read-only view of persisted non-secret facts. / 返回已持久非秘密事实的分离只读视图。
     * @return copied original fields / 已复制原字段 */
    JsonNode facts() {
        return facts.deepCopy();
    }

    /** Reports whether the original explicit checkpoint was already released. / 报告原显式检查点是否已释放。
     * @return actual persisted release state / 实际持久释放状态 */
    boolean released() {
        return snapshot.released();
    }

    /** Records one confirmed non-secret phase without replacing an uncertain CAS. / 记录已确认非秘密阶段，且不覆盖确认不明的CAS。
     * @param phase stable actual phase / 稳定实际阶段
     * @return whether persistence was confirmed / 是否已确认持久化 */
    boolean mark(String phase) {
        if (!confirmed)
            return false;
        facts.put("phase", phase);
        try {
            snapshot = checkpoint.advance(snapshot, facts.toString());
            return true;
        } catch (ApiException uncertain) {
            confirmed = false;
            return false;
        }
    }

    /** Records the exact attempted remote content identity before publishing. / 发布前记录精确尝试的远端内容身份。
     * @param releaseSha256 original remote content digest / 原远端内容摘要
     * @return whether persistence was confirmed / 是否已确认持久化 */
    boolean publishing(String releaseSha256) {
        facts.put("releaseSha256", releaseSha256);
        return mark("PUBLISH_MAY_HAVE_RUN");
    }

    /** Persists the actual old-release snapshot before any new publication request. / 在任何新发布请求前持久化实际旧版快照。
     * @param saved exact observed old release / 精确观测的旧发布
     * @return whether the original snapshot was durably confirmed / 原快照是否已持久确认 */
    boolean snapshot(DeploymentRemoteReleaseSnapshot saved) {
        Objects.requireNonNull(saved);
        facts.put("previousToken", saved.token().orElse(""));
        facts.put("previousRunning", saved.previousRunning());
        facts.put("previousDigest", saved.previousDigest().orElse(""));
        facts.put("previousKind", saved.previousKind());
        return mark("SNAPSHOT_VERIFIED");
    }

    /** Freezes the actual known publication failure before a possibly uncertain rollback dispatch. / 回滚发送可能不明前冻结实际已知发布失败。
     * @param failure original bounded public failure / 原有界公开失败
     * @return whether the failure and rollback boundary were durably confirmed / 失败和回滚边界是否获持久确认 */
    boolean rollbackStarting(Problem failure) {
        Objects.requireNonNull(failure);
        facts.put("failureCode", failure.code().name());
        facts.put("failureEvent", failure.eventCode());
        return mark("ROLLBACK_MAY_HAVE_RUN");
    }

    /** Persists a proven business result independently of optional remote and local cleanup. / 独立于可选远端和本地收尾持久化已证明业务结果。
     * @param outcome verified success or failure / 已核实成功或失败
     * @param failure original bounded failure for a failed result / 失败结果的原有界问题
     * @return whether the result was durably confirmed / 结果是否已持久确认 */
    boolean verifiedBusiness(Outcome outcome, Optional<Problem> failure) {
        if (outcome != Outcome.SUCCEEDED && outcome != Outcome.FAILED)
            throw new IllegalArgumentException("Only verified publication results may be persisted");
        if ((outcome == Outcome.FAILED) != failure.isPresent())
            throw new IllegalArgumentException("Verified failure requires its actual problem");
        facts.put("verifiedOutcome", outcome.name());
        if (failure.isPresent()) {
            facts.put("verifiedCode", failure.orElseThrow().code().name());
            facts.put("verifiedEvent", failure.orElseThrow().eventCode());
        }
        return mark(facts.path("phase").asText());
    }

    /** Records completion of every required remote cleanup action. / 记录全部必要远端清理动作完成。
     * @return whether durable completion was confirmed / 持久完成是否已确认 */
    boolean remoteClean() {
        remoteClean = mark("REMOTE_CLEANUP_VERIFIED");
        return remoteClean;
    }

    /** Records the actual borrowed-session close. / 记录借用会话的实际关闭。
     * @return whether durable close was confirmed / 持久关闭是否已确认 */
    boolean sessionClosed() {
        sessionClosed = remoteClean && mark("SESSION_CLOSED");
        return sessionClosed;
    }

    /** Releases the real occupancy only after every required remote cleanup was confirmed. / 仅在所有必要远端清理确认后释放真实占用。
     * @return whether original release was confirmed / 原释放是否确认 */
    boolean release() {
        if (!remoteClean || !sessionClosed)
            return false;
        if (!mark("CLEANUP_VERIFIED"))
            return false;
        try {
            checkpoint.close();
            return true;
        } catch (ApiException uncertain) {
            return false;
        }
    }
}
