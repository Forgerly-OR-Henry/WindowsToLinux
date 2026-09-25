package gold.debug.windowstolinux.shared.deploy.publication;

import java.util.*;
import java.util.function.BiConsumer;

import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload;

/** Mode-neutral release, whole-application health and verified reverse rollback. / 模式中立的发布、整应用健康及可验证逆序回滚。 */
public final class ManagedPublicationTransaction {
    /** Publishes an already-built graph; no project analysis or build strategy is invoked. / 发布已经构建的图，不调用项目分析或构建策略。
     * @param session authenticated remote session / 已认证远端会话
     * @param supplied prepared dependency graph / 已准备依赖图
     * @param progress actual operation observations / 实际操作观察
     * @return verified transaction outcome / 已验证事务结果
     */
    public Result publish(DeploymentRemoteSession session, List<PreparedPublication> supplied,
            BiConsumer<String, DeploymentEvent> progress) {
        return publish(session, supplied, Optional.empty(), progress);
    }

    /** Publishes a graph with an explicit whole-application health gate. / 使用显式整应用健康门禁发布图。
     * @param session authenticated session / 已认证会话
     * @param supplied prepared graph / 已准备图
     * @param applicationHealth additional whole-application gate / 额外整应用门禁
     * @param progress actual observations / 实际观察
     * @return verified result / 已验证结果
     */
    public Result publish(DeploymentRemoteSession session, List<PreparedPublication> supplied,
            Optional<gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate> applicationHealth,
            BiConsumer<String, DeploymentEvent> progress) {
        var publications = order(supplied);
        var snapshots = new LinkedHashMap<String, ReleaseSnapshot>();
        var touched = new ArrayList<PreparedPublication>();
        var observations = new LinkedHashMap<String, LifecycleObservation>();
        var stopped = new ArrayList<PreparedPublication>();
        if (applicationHealth.isPresent()
                && publications.stream().noneMatch(p -> p.id().equals(applicationHealth.orElseThrow().componentId())))
            throw new IllegalArgumentException("health gate component missing");
        try {
            for (var item : publications.reversed()) {
                var snapshot = session.snapshotDeployment(item.application(), item.runtime());
                snapshots.put(item.id(), snapshot);
                progress.accept(item.id(),
                        DeploymentEvent.result(DeploymentTraceEvent.SNAPSHOT, true, snapshot.evidence()));
            }
            if (publications.size() > 1)
                for (var item : publications.reversed()) {
                    var snapshot = snapshots.get(item.id());
                    if (!snapshot.hasPreviousRelease() || !snapshot.previousWasRunning())
                        continue;
                    stopped.add(item);
                    var state = session.executeDeploymentLifecycle(item.application(), item.runtime(),
                            LifecycleAction.STOP);
                    progress.accept(item.id(),
                            DeploymentEvent.result(DeploymentTraceEvent.STOP_OLD,
                                    state.ownershipVerified() && state.runtimeState() == RuntimeState.STOPPED,
                                    state.evidence()));
                    if (!state.ownershipVerified() || state.runtimeState() != RuntimeState.STOPPED)
                        return recover(session, touched, stopped, snapshots, progress);
                }
            for (var item : publications) {
                var snapshot = snapshots.get(item.id());
                touched.add(item);
                var published = session.publishDeployment(item.application(), item.standardFacts().orElse(null),
                        item.workspace(), item.build(), item.release(), item.runtime(), item.inputs(), item.resources(),
                        snapshot);
                progress.accept(item.id(), DeploymentEvent.result(DeploymentTraceEvent.PUBLISH, published.succeeded(),
                        published.evidence()));
                if (published.timedOut())
                    return new Result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, Map.of(),
                            "publication outcome unknown");
                if (!published.succeeded())
                    return recover(session, touched, stopped, snapshots, progress);
                if (!healthy(session, item, observations, progress))
                    return recover(session, touched, stopped, snapshots, progress);
            }
            // Recheck earlier components after all dependents start. / 全部下游组件启动后重新检查先前组件。
            if (publications.size() > 1)
                for (var item : publications)
                    if (!healthy(session, item, observations, progress))
                        return recover(session, touched, stopped, snapshots, progress);
            if (applicationHealth.isPresent()) {
                var gate = applicationHealth.orElseThrow();
                var item = publications.stream().filter(p -> p.id().equals(gate.componentId())).findFirst()
                        .orElseThrow();
                var whole = session.checkDeploymentHealth(item.application(), item.runtime(), gate.healthCheck());
                progress.accept("", DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_HEALTH, whole.healthy(),
                        whole.evidence()));
                if (!whole.healthy())
                    return recover(session, touched, stopped, snapshots, progress);
            }
            for (var item : publications)
                session.backupArtifacts().endMaintenance(item.application(), "deployment-" + item.release());
            return new Result(DeploymentStatus.SUCCEEDED, observations, "all required managed components verified");
        } catch (Exception unavailable) {
            progress.accept("", DeploymentEvent.result(DeploymentTraceEvent.LINUX_OPERATION, false,
                    "publication or recovery evidence unavailable: " + unavailable.getClass().getSimpleName()));
            return new Result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, Map.of(),
                    "unknown publication; no automatic write replay");
        }
    }

    /** Verifies ownership, runtime state and explicit health together. / 同时验证归属、运行状态及显式健康。
     * @param session remote session / 远端会话
     * @param item prepared component / 已准备组件
     * @param observations verified observations / 已验证观察
     * @param progress actual evidence / 实际证据
     * @return whether this exact component is healthy / 精确组件是否健康
     * @throws Exception when observations cannot be established / 无法确定观察时
     */
    private static boolean healthy(DeploymentRemoteSession session, PreparedPublication item,
            Map<String, LifecycleObservation> observations, BiConsumer<String, DeploymentEvent> progress)
            throws Exception {
        var health = session.checkDeploymentHealth(item.application(), item.runtime(), item.runtime().healthCheck());
        progress.accept(item.id(),
                DeploymentEvent.result(DeploymentTraceEvent.CANDIDATE_HEALTH, health.healthy(), health.evidence()));
        if (!health.healthy())
            return false;
        var observed = session.observeDeployment(item.application(), item.runtime());
        RuntimeState expected = item.runtime().workload().mode() == ApplicationWorkload.ExecutionMode.ON_DEMAND
                ? RuntimeState.INSTALLED
                : RuntimeState.RUNNING;
        progress.accept(item.id(), DeploymentEvent.result(DeploymentTraceEvent.FINAL_OBSERVATION,
                observed.ownershipVerified() && observed.runtimeState() == expected, observed.evidence()));
        if (!health.healthy() || !observed.ownershipVerified() || observed.runtimeState() != expected)
            return false;
        observations.put(item.id(), observed);
        return true;
    }

    /** Recovers known failures in reverse dependency order and proves prior state. / 对已知失败按逆依赖顺序恢复并证明先前状态。
     * @param session remote session / 远端会话
     * @param touched components possibly published / 可能已发布组件
     * @param stopped components whose previous runtime was stopped / 先前运行已停止的组件
     * @param snapshots exact prior snapshots / 精确先前快照
     * @param progress observed recovery evidence / 观察到的恢复证据
     * @return verified recovery or manual state / 已验证恢复或人工状态
     * @throws Exception when recovery is unknown / 恢复未知时
     */
    private static Result recover(DeploymentRemoteSession session, List<PreparedPublication> touched,
            List<PreparedPublication> stopped, Map<String, ReleaseSnapshot> snapshots,
            BiConsumer<String, DeploymentEvent> progress) throws Exception {
        boolean previous = false;
        boolean verified = true;
        var restoredObservations = new LinkedHashMap<String, LifecycleObservation>();
        var affected = new LinkedHashMap<String, PreparedPublication>();
        touched.forEach(item -> affected.put(item.id(), item));
        stopped.forEach(item -> affected.put(item.id(), item));
        for (String id : snapshots.keySet()) {
            var item = affected.get(id);
            if (item == null)
                continue;
            var snapshot = snapshots.get(item.id());
            previous |= snapshot.hasPreviousRelease();
            var rollback = session.rollbackDeployment(item.application(), snapshot, item.build(), item.release(),
                    item.runtime(), item.inputs());
            progress.accept(item.id(),
                    DeploymentEvent.result(DeploymentTraceEvent.ROLLBACK, rollback.succeeded(), rollback.evidence()));
            if (rollback.timedOut())
                return new Result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, restoredObservations,
                        "rollback outcome unknown");
            if (!rollback.succeeded()) {
                verified = false;
                continue;
            }
            if (snapshot.hasPreviousRelease()) {
                var restored = session.observe(item.application());
                progress.accept(item.id(), DeploymentEvent.result(DeploymentTraceEvent.ROLLBACK_OBSERVATION,
                        restored.ownershipVerified(), restored.evidence()));
                boolean expected = snapshot.previousWasRunning()
                        ? restored.runtimeState() == RuntimeState.RUNNING
                        : restored.runtimeState() == RuntimeState.STOPPED
                                || restored.runtimeState() == RuntimeState.INSTALLED;
                if (!restored.ownershipVerified() || !expected)
                    return new Result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, Map.of(), "prior runtime unverified");
                restoredObservations.put(item.id(), restored);
            }
            session.backupArtifacts().endMaintenance(item.application(), "deployment-" + item.release());
        }
        return new Result(
                !verified
                        ? DeploymentStatus.MANUAL_RECOVERY_REQUIRED
                        : previous ? DeploymentStatus.FAILED_ROLLED_BACK : DeploymentStatus.FAILED_FIRST_DEPLOYMENT,
                restoredObservations, "known failed publication recovered");
    }

    /** Revalidates dependency closure independently of either engine. / 独立于两种引擎重新校验依赖闭包。
     * @param supplied prepared graph / 已准备图
     * @return dependency-ordered components / 依赖排序组件
     */
    private static List<PreparedPublication> order(List<PreparedPublication> supplied) {
        supplied = List.copyOf(supplied);
        if (supplied.isEmpty() || supplied.size() > 16)
            throw new IllegalArgumentException("publication count");
        var remaining = new LinkedHashMap<String, PreparedPublication>();
        var applications = new HashSet<String>();
        for (var item : supplied)
            if (remaining.put(item.id(), item) != null || !applications.add(item.application().id()))
                throw new IllegalArgumentException("duplicate publication identity");
        var result = new ArrayList<PreparedPublication>();
        var done = new HashSet<String>();
        while (!remaining.isEmpty()) {
            var ready = remaining.values().stream().filter(item -> done.containsAll(item.dependencies())).toList();
            if (ready.isEmpty())
                throw new IllegalArgumentException("publication dependency cycle or missing component");
            for (var item : ready) {
                result.add(item);
                done.add(item.id());
                remaining.remove(item.id());
            }
        }
        return List.copyOf(result);
    }

    /** Actual public-layer result and complete component evidence. / 公共层实际结果及完整组件证据。
     * @param status transaction status / 事务状态
     * @param observations verified component observations / 已验证组件观察
     * @param evidence bounded nonsecret explanation / 有界非秘密说明
     */
    public record Result(DeploymentStatus status, Map<String, LifecycleObservation> observations, String evidence) {
        /** Freezes observations at the return boundary. / 在返回边界冻结观察。
         * @param status transaction status / 事务状态
         * @param observations actual observations / 实际观察
         * @param evidence nonsecret evidence / 非秘密证据
         */
        public Result {
            Objects.requireNonNull(status);
            observations = Map.copyOf(observations);
            Objects.requireNonNull(evidence);
        }
    }
}
