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
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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
        return begin(context, task, KEY, unit, archiveSha256, planSha256, runtime);
    }

    /** Begins one indexed component journal before its first possible remote effect. / 在组件首次可能的远端副作用前建立索引账本。
     * @param context original Mod / 原Mod
     * @param task original task / 原任务
     * @param key graph-indexed fixed checkpoint key / 图索引固定检查点键
     * @param unit exact component owner / 精确组件归属
     * @param archiveSha256 original tar digest / 原tar摘要
     * @param planSha256 original component plan digest / 原组件计划摘要
     * @param runtime reviewed backend / 已审阅后端
     * @return occupied original component journal / 已占用原组件账本
     * @throws ApiException if persistence is unconfirmed / 持久确认不明时 */
    static DeploymentPublicationCleanupJournal begin(ModContext context, TaskContext task, String key,
                                                     DeploymentUnitIdentity unit, String archiveSha256, String planSha256, DeploymentRuntimeDefinition runtime)
        throws ApiException {
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
        var checkpoint = context.tasks().beginCheckpoint(task, key, facts.toString());
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
        return reopen(context, task, KEY);
    }

    /** Reopens only an existing indexed component journal. / 仅重开已有索引组件账本。
     * @param context original Mod / 原Mod
     * @param task original recovery task / 原恢复任务
     * @param key original indexed key / 原索引键
     * @return original journal or absence / 原账本或不存在
     * @throws ApiException if original access fails / 原访问失败时 */
    static Optional<DeploymentPublicationCleanupJournal> reopen(ModContext context, TaskContext task, String key)
        throws ApiException {
        var found = context.tasks().findCheckpoint(task, key);
        if (found.isEmpty())
            return Optional.empty();
        var checkpoint = found.orElseThrow();
        var snapshot = checkpoint.snapshot();
        try {
            JsonNode parsed = JSON.readTree(snapshot.payload());
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode facts)
                || !facts.path("version").isInt() || !java.util.Set.of(2, 3, 4).contains(facts.path("version").intValue())
                || !task.id().toString().equals(facts.path("task").textValue()) || !facts.path("phase").isTextual())
                throw new IllegalArgumentException("Original publication checkpoint is unsupported");
            requireBuildFacts(facts, snapshot.payload());
            DeploymentPublicationAttemptPointers.requireChild(facts);
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

    /** Rejects coerced or incomplete build pointers before any retained resource can be observed or cleaned. / 观察或清理任一留存资源前拒绝强制转换或不完整构建指针。
     * @param facts original checkpoint facts / 原检查点事实
     * @param payload exact original UTF-8 envelope / 精确原UTF-8信封
     * @throws IllegalArgumentException if a retained type, bound or predecessor is invalid / 留存类型、边界或前驱不合法时 */
    private static void requireBuildFacts(JsonNode facts, String payload) {
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12000)
            throw new IllegalArgumentException("Original publication checkpoint exceeds its admitted bound");
        var hashes = java.util.Set.of("buildPlan", "buildScript", "buildTools", "buildFailureEvidence", "environmentTools", "buildReviewPrevious",
            "buildReviewNext", "buildReviewRootPrevious", "buildReviewRootNext", "buildReviewIntent");
        for (String field : hashes)
            if (facts.has(field) && (!facts.path(field).isTextual() || !facts.path(field).textValue().matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("Original build checkpoint digest is malformed");
        if (facts.path("version").intValue() == 2) {
            if (hashes.stream().anyMatch(facts::has) || facts.has("buildReviewState") || facts.has("environmentRecovery") || facts.has("buildReviewAttempt"))
                throw new IllegalArgumentException("Original checkpoint version does not contain build review pointers");
            return;
        }
        if ((facts.path("version").intValue() == 3 || facts.has("buildPlan") || facts.has("buildScript") || facts.has("buildTools"))
            && (!facts.has("buildPlan") || !facts.has("buildScript") || !facts.has("buildTools")))
            throw new IllegalArgumentException("Original build dispatch binding is incomplete");
        if (facts.has("environmentRecovery") && (!facts.path("environmentRecovery").isTextual()
            || !java.util.Set.of("MAY_HAVE_RUN", "VERIFIED").contains(facts.path("environmentRecovery").textValue())
            || facts.path("environmentRecovery").textValue().equals("VERIFIED") != facts.has("environmentTools")))
            throw new IllegalArgumentException("Original environment recovery receipt is incomplete");
        if (!facts.has("environmentRecovery") && facts.has("environmentTools"))
            throw new IllegalArgumentException("Original tool receipt lacks its recovery boundary");
        if (facts.has("buildReviewState")) {
            if (!facts.path("buildReviewState").isTextual() || !java.util.Set.of("PENDING", "CONFIRMED").contains(facts.path("buildReviewState").textValue())
                || !facts.path("buildReviewAttempt").isInt() || facts.path("buildReviewAttempt").intValue() < 1 || facts.path("buildReviewAttempt").intValue() > 2
                || java.util.List.of("buildReviewPrevious", "buildReviewNext", "buildReviewRootPrevious", "buildReviewRootNext", "buildReviewIntent").stream().anyMatch(field -> !facts.has(field)))
                throw new IllegalArgumentException("Original full review pointer is incomplete");
        } else if (facts.has("buildReviewAttempt") || java.util.List.of("buildReviewPrevious", "buildReviewNext", "buildReviewRootPrevious", "buildReviewRootNext", "buildReviewIntent").stream().anyMatch(facts::has))
            throw new IllegalArgumentException("Original review pointer lacks its state");
    }

    /** Binds an actual prepared script and installed tools before its first possible build send. / 首次可能构建发送前绑定实际准备脚本及已安装工具。
     * @param plan exact actual plan SHA / 精确实际计划SHA
     * @param script exact fixed input SHA / 精确固定输入SHA
     * @param tools complete installed tool binding SHA / 完整安装工具绑定SHA
     * @return whether the physical dispatch binding was confirmed / 物理发送绑定是否确认 */
    boolean buildStarting(String plan, String script, String tools) {
        if (!facts.path("phase").asText().equals("CANDIDATE_MAY_EXIST") || !facts.path("planSha256").asText().equals(plan)
            || !script.matches("[0-9a-f]{64}") || !tools.matches("[0-9a-f]{64}")) return false;
        facts.put("version", Math.max(3, facts.path("version").intValue())).put("buildPlan", plan).put("buildScript", script).put("buildTools", tools);
        facts.remove("buildFailureEvidence");
        return mark("CANDIDATE_MAY_EXIST");
    }

    /** Records complete known failure evidence only for the actually bound build dispatch. / 仅为实际已绑定构建发送记录完整已知失败依据。
     * @param plan actual failed plan SHA / 实际失败计划SHA
     * @param evidence exact physical receipt SHA / 精确物理回执SHA
     * @return whether failure was durably paired with the original dispatch / 失败是否已持久配对原发送 */
    boolean buildFailed(String plan, String evidence) {
        if (!facts.path("buildPlan").asText().equals(plan) || !evidence.matches("[0-9a-f]{64}")) return false;
        facts.put("buildFailureEvidence", evidence);
        return mark("CANDIDATE_MAY_EXIST");
    }

    /** Checks full recovery intent against the original physical dispatch and receipt pointers. / 按原物理发送与回执指针检查完整恢复意图。
     * @param intent full original recovery consent / 完整原恢复同意
     * @return whether all original executed bindings match / 全部原执行绑定是否匹配 */
    boolean actualBuild(gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildIntent intent) {
        try {
            return facts.path("buildPlan").asText().equals(gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan()).reviewedRevision())
                && facts.path("buildScript").asText().equals(digest(intent.previousScript())) && facts.path("buildTools").asText().equals(digest(intent.previousTools()))
                && facts.path("buildFailureEvidence").asText().equals(digest(intent.failureEvidence()));
        } catch (java.sql.SQLException invalid) {
            return false;
        }
    }

    /** Records a possible fixed installation effect only after full formal consent is confirmed. / 完整正式同意确认后才记录可能的固定安装效果。
     * @param intent exactly stored recovery consent / 精确已存恢复同意
     * @return whether its original effect boundary was confirmed / 原效果边界是否确认 */
    boolean recoveryStarting(gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildIntent intent) {
        if (!actualBuild(intent)) return false;
        facts.put("environmentRecovery", "MAY_HAVE_RUN");
        facts.remove("environmentTools");
        return mark("CANDIDATE_MAY_EXIST");
    }

    /** Records complete physically observed installation and binding completion. / 记录完整物理观察的安装及绑定完成。
     * @param tools exact observed complete binding SHA / 精确已观察完整绑定SHA
     * @return whether completion was persisted / 完成是否持久 */
    boolean recoveryObserved(String tools) {
        if (!facts.path("environmentRecovery").asText().equals("MAY_HAVE_RUN") || !tools.matches("[0-9a-f]{64}"))
            return false;
        facts.put("environmentRecovery", "VERIFIED").put("environmentTools", tools);
        return mark("CANDIDATE_MAY_EXIST");
    }

    /** Hashes exact bounded persisted input without exposing it. / 哈希精确有界持久输入，不公开其内容。
     * @param value actual input / 实际输入
     * @return exact lowercase SHA / 精确小写SHA */
    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException invalid) {
            throw new IllegalStateException("JDK SHA-256 unavailable", invalid);
        }
    }

    /** Persists bounded original-to-next pointers before consuming a full formal recovery intent. / 消费完整正式恢复意图前持久化有界原、新指针。
     * @param intent complete consent stored in the formal request / 正式请求所存完整同意
     * @return whether this exact pending pointer was confirmed / 此精确待确认指针是否获确认 */
    boolean reviewPending(gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildIntent intent) {
        if (!facts.path("phase").asText().equals("CANDIDATE_MAY_EXIST") || !actualBuild(intent)) return false;
        try {
            var before = gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan());
            var after = gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec.decodeDescription(intent.nextPlan());
            if (!facts.path("application").asText().equals(intent.unitId()) || !facts.path("planSha256").asText().equals(before.reviewedRevision()))
                return false;
            facts.put("version", Math.max(3, facts.path("version").intValue())).put("buildReviewState", "PENDING").put("buildReviewAttempt", intent.attempt())
                .put("buildReviewPrevious", before.reviewedRevision()).put("buildReviewNext", after.reviewedRevision())
                .put("buildReviewRootPrevious", intent.previousRoot()).put("buildReviewRootNext", intent.nextRoot())
                .put("buildReviewIntent", gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReview.failureBinding(intent.previousPlan(), intent.previousScript(), intent.previousTools(), intent.failureEvidence()));
            return mark("CANDIDATE_MAY_EXIST");
        } catch (java.sql.SQLException invalid) {
            return false;
        }
    }

    /** Confirms the exact consumed formal transition before any new build send. / 任一新构建发送前确认精确已消费正式转换。
     * @param review actual formally persisted transition / 实际正式持久转换
     * @return whether the child pointer now matches its formal review / 子指针现在是否匹配正式审阅 */
    boolean reviewConfirmed(gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReview review) {
        try {
            var after = gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec.decodeDescription(review.nextPlan());
            if (!facts.path("buildReviewState").asText().equals("PENDING") || facts.path("buildReviewAttempt").asInt() != review.attempt()
                || !facts.path("buildReviewPrevious").asText().equals(gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec.decodeDescription(review.previousPlan()).reviewedRevision())
                || !facts.path("buildReviewNext").asText().equals(after.reviewedRevision()) || !facts.path("buildReviewIntent").asText().equals(review.failureBinding())
                || !facts.path("buildReviewRootPrevious").asText().equals(review.previousRoot()) || !facts.path("buildReviewRootNext").asText().equals(review.nextRoot()))
                return false;
            facts.put("planSha256", after.reviewedRevision()).put("buildReviewState", "CONFIRMED");
            return mark("CANDIDATE_MAY_EXIST");
        } catch (java.sql.SQLException invalid) {
            return false;
        }
    }

    /** Keeps the complete old closed operation while marking the next fixed environment action uncertain before send. / 保持完整旧关闭操作，并在发送前标记下一固定环境动作不明。
     * @param intent full formally persisted original consent / 完整正式持久原同意
     * @return whether exact pending occupancy was confirmed / 精确待定占用是否确认 */
    boolean attemptBoundary(gold.debug.windowstolinux.mod.deployment.db.DeploymentPublicationAttemptIntent intent) {
        if (!confirmed || !remoteClean || !sessionClosed || !facts.path("phase").asText().equals("SESSION_CLOSED"))
            return false;
        if (!DeploymentPublicationAttemptPointers.pendingChild(facts, intent)) return false;
        return mark("SESSION_CLOSED");
    }

    /** Confirms actual fixed tool observations without advancing the formally reviewed operation. / 确认实际固定工具观察，不推进正式已审阅操作。
     * @param tools exact complete observed binding SHA / 精确完整观察绑定SHA
     * @return whether the physical environment observation was persisted / 物理环境观察是否已持久化 */
    boolean attemptObserved(String tools) {
        if (!facts.path("attemptState").asText().equals("PENDING") || !facts.path("attemptEnvironment").asText().equals("MAY_HAVE_RUN")
            || !tools.matches("[0-9a-f]{64}")) return false;
        facts.put("attemptEnvironment", "VERIFIED").put("attemptTools", tools);
        return mark("SESSION_CLOSED");
    }

    /** Replaces only current-operation pointers after the exact formal CAS and old operation proof. / 精确正式CAS及旧操作证明后仅替换当前操作指针。
     * @param review complete actually persisted final review / 完整实际持久最终审阅
     * @param plan exact complete current component SHA / 精确完整当前组件SHA
     * @param runtime actual newly confirmed fixed backend / 实际新确认固定后端
     * @return whether the same original checkpoint now confirms the next operation / 同一原检查点是否已确认下一操作 */
    boolean attemptConfirmed(gold.debug.windowstolinux.mod.deployment.db.DeploymentPublicationAttemptReview review,
                             String plan, DeploymentRuntimeDefinition runtime) {
        if (!confirmed || !remoteClean || !sessionClosed || !DeploymentPublicationAttemptPointers.confirmChild(facts, review, plan, runtime))
            return false;
        remoteClean = false;
        sessionClosed = false;
        return mark("CANDIDATE_MAY_EXIST");
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

    /** Freezes the reviewed content digest before a graph can stop old runtimes, without claiming a publish dispatch. / 整图可能停旧运行时前冻结已审阅内容摘要，但不宣称已发送发布。
     * @param releaseSha256 exact content identity / 精确内容身份
     * @return whether the original digest was durably confirmed / 原摘要是否已持久确认 */
    boolean plannedRelease(String releaseSha256) {
        facts.put("releaseSha256", releaseSha256);
        return mark(facts.path("phase").asText());
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
