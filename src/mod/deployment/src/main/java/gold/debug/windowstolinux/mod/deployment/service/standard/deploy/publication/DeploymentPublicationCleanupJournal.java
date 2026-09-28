package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentRemoteReleaseSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;

/** Keeps original remote candidate and cleanup facts occupied until every actual cleanup is confirmed. / 保留原远端候选和清理依据，直至所有实际清理均已确认。 */
final class DeploymentPublicationCleanupJournal {
    /** Original task-local author evidence key. / 原任务内部作者依据键。 */
    static final String KEY = "deployment-publication-remote";

    /** Bounded JSON writer; no secret values enter this journal. / 有界JSON写入器；秘密值不进入此账本。 */
    private static final ObjectMapper JSON = new ObjectMapper();

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
     * @return occupied original cleanup journal / 已占用原清理账本
     * @throws ApiException if the original evidence cannot be confirmed / 原依据无法确认时 */
    static DeploymentPublicationCleanupJournal begin(ModContext context, TaskContext task, DeploymentUnitIdentity unit,
            String archiveSha256, String planSha256) throws ApiException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(task);
        Objects.requireNonNull(unit);
        var facts = JSON.createObjectNode();
        facts.put("version", 1);
        facts.put("task", task.id().toString());
        facts.put("server", unit.server().id());
        facts.put("application", unit.id());
        facts.put("ownerManifestSha256", unit.ownershipManifestSha256());
        facts.put("releaseRoot", unit.releaseRoot());
        facts.put("archiveSha256", archiveSha256);
        facts.put("candidate", new DeploymentRemoteWorkspace(unit.id(), archiveSha256).candidateId());
        facts.put("planSha256", planSha256);
        facts.put("phase", "CANDIDATE_MAY_EXIST");
        var checkpoint = context.tasks().beginCheckpoint(task, KEY, facts.toString());
        return new DeploymentPublicationCleanupJournal(checkpoint, facts);
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
        return mark("SNAPSHOT_VERIFIED");
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
