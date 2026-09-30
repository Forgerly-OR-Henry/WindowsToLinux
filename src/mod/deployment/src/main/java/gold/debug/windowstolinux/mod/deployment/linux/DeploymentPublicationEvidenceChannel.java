package gold.debug.windowstolinux.mod.deployment.linux;

import static gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublicationReceipt.*;
import java.time.Duration;
import java.util.*;
import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;

/** Reads and cleans evidence for the real root task and a distinct bounded attempt. / 读取并清理真实根任务及独立有界尝试的证据。 */
final class DeploymentPublicationEvidenceChannel {
    /** Original admitted task, never an operation alias. / 原准入任务，绝不是操作别名。 */
    private final TaskContext task;
    /** Existing reviewed publication transport. / 既有已审阅发布传输。 */
    private final CommandPort sender;
    /** Binds original task and transport. / 绑定原任务和传输。
     * @param task original task / 原任务
     * @param sender original reviewed sender / 原已审阅发送方 */
    DeploymentPublicationEvidenceChannel(TaskContext task, CommandPort sender) {
        this.task = Objects.requireNonNull(task);
        this.sender = Objects.requireNonNull(sender);
    }
    /** Sends through the existing host approval and original task. / 经既有宿主授权和原任务发送。 */
    @FunctionalInterface
    interface CommandPort {
        /** Sends a fixed reviewed helper command. / 发送固定已审阅helper命令。
         * @param operation host effect classification / 宿主副作用分类
         * @param unit exact owned unit / 精确所属单元
         * @param verb fixed helper verb / 固定helper动作
         * @param arguments literal validated arguments / 字面校验参数
         * @param timeout original deadline / 原截止时间
         * @param limit bounded output / 有界输出
         * @return original command result / 原命令结果
         * @throws ApiException if original admission rejects / 原准入拒绝时 */
        OperationResult<CommandResult> send(String operation, DeploymentUnitIdentity unit, String verb,
                List<String> arguments, Duration timeout, int limit) throws ApiException;
    }

    /** Creates one original helper rollback snapshot; an incomplete reply never means first deployment. / 创建一份原helper回滚快照；不完整回执绝不表示首次部署。
     * @param unit exact owned application / 精确所属应用
     * @param operation exact snapshot operation / 精确快照操作
     * @param expectedCurrent reviewed previous current or explicit empty / 已审阅旧当前或明确为空
     * @param attempt whether this is the distinct operation contract / 是否使用独立操作契约
     * @param runtime reviewed runtime kind / 已审阅运行类型
     * @return original snapshot or retained uncertainty / 原快照或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    OperationResult<DeploymentRemoteReleaseSnapshot> snapshot(DeploymentUnitIdentity unit,
            DeploymentRuntimeDefinition runtime, java.util.UUID operation, Optional<String> expectedCurrent, boolean attempt) throws ApiException {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(expectedCurrent);
        if (expectedCurrent.isPresent() && !expectedCurrent.orElseThrow().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Reviewed previous current digest required");
        String verb = (runtime instanceof DeploymentRuntimeDefinition.Container ? "snapshot-container" : "snapshot-deployment")
                + (attempt ? "-attempt" : "");
        var arguments = attempt
                ? List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString(), operation.toString(), expectedCurrent.orElse("empty"))
                : List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString());
        var observed = sender.send("deployment.publication.snapshot.v1", unit, verb, arguments, Duration.ofSeconds(30), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed))
            return unknown("DEPLOYMENT_SNAPSHOT_UNCONFIRMED");
        String output = observed.value().orElseThrow().stdout();
        if (output.equals("PREVIOUS=0\n"))
            return attempt && expectedCurrent.isPresent() ? unknown("DEPLOYMENT_SNAPSHOT_CURRENT_CHANGED")
                    : success(new DeploymentRemoteReleaseSnapshot(Optional.empty(), false, Optional.empty(), "none"));
        var match = java.util.regex.Pattern.compile(
                "SNAPSHOT_TOKEN=([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\\nPREVIOUS=1\\nPREVIOUS_RUNNING=([01])\\nPREVIOUS_DIGEST=([0-9a-f]{64})\\nPREVIOUS_KIND=(ordinary|deployment|container)\\n")
                .matcher(output);
        if (!match.matches())
            return unknown("DEPLOYMENT_SNAPSHOT_RECEIPT_INVALID");
        if (!operation.toString().equals(match.group(1))
                || runtime instanceof DeploymentRuntimeDefinition.Container != "container".equals(match.group(4)))
            return unknown("DEPLOYMENT_SNAPSHOT_RECEIPT_INVALID");
        if (attempt && !expectedCurrent.equals(Optional.of(match.group(3))))
            return unknown("DEPLOYMENT_SNAPSHOT_CURRENT_CHANGED");
        return success(new DeploymentRemoteReleaseSnapshot(Optional.of(match.group(1)), "1".equals(match.group(2)),
                Optional.of(match.group(3)), match.group(4)));
    }

    /** Reads only the original task's owned publication evidence without replaying an effect. / 仅读取原任务所属发布证据而不重放副作用。
     * @param unit original owned application / 原所属应用
     * @param candidateId original confirmed candidate / 原已确认候选
     * @param releaseSha256 exact attempted release, absent before publication identity exists / 精确尝试发布；发布身份未形成前为空
     * @param backend original persisted native or container backend / 原已持久原生或容器后端
     * @param engine original persisted container engine, or none / 原已持久容器引擎或none
     * @param operation exact original operation / 精确原操作
     * @param attempt whether to require operation ownership / 是否要求操作归属
     * @return complete original status or retained uncertainty / 完整原状态或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    OperationResult<DeploymentOriginalPublicationStatus> originalStatus(DeploymentUnitIdentity unit,
            String candidateId, Optional<String> releaseSha256, String backend, String engine, java.util.UUID operation, boolean attempt) throws ApiException {
        Objects.requireNonNull(releaseSha256);
        if (candidateId == null || !candidateId.matches("[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}")
                || releaseSha256.isPresent() && !releaseSha256.orElseThrow().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Original publication identity required");
        if (!Set.of("native:none", "container:docker", "container:podman").contains(backend + ":" + engine))
            throw new IllegalArgumentException("Original backend and engine required");
        Objects.requireNonNull(operation);
        var arguments = attempt
                ? List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString(), operation.toString(), candidateId, releaseSha256.orElse("none"), backend, engine)
                : List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString(), candidateId, releaseSha256.orElse("none"), backend, engine);
        var observed = sender.send("deployment.publication.original-status.v1", unit,
                "publication-original-status" + (attempt ? "-attempt" : ""), arguments, Duration.ofSeconds(30), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed))
            return unknown("DEPLOYMENT_ORIGINAL_STATUS_UNCONFIRMED");
        var values = DeploymentPublicationReceipt.fields(observed.value().orElseThrow().stdout());
        if (!values.keySet()
                .equals(Set.of("PUBLICATION_STATUS", "CURRENT", "CURRENT_KIND", "CURRENT_MODE", "SNAPSHOT",
                        "SNAPSHOT_OLD", "SNAPSHOT_KIND", "SNAPSHOT_RUNNING", "MAINTENANCE", "CANDIDATE", "BUILD_ACTIVE",
                        "UNIT_PRESENT", "CONTAINER_PRESENT", "RETAINED_COUNT"))
                || !"1".equals(values.get("PUBLICATION_STATUS"))
                || !Set.of("none", operation.toString()).contains(values.get("SNAPSHOT"))
                || !Set.of("none", "0", "1").contains(values.get("SNAPSHOT_RUNNING"))
                || ("none".equals(values.get("SNAPSHOT")) != "none".equals(values.get("SNAPSHOT_RUNNING")))
                || !("none".equals(values.get("MAINTENANCE")) || releaseSha256.isPresent()
                        && ("deployment-" + releaseSha256.orElseThrow()).equals(values.get("MAINTENANCE")))
                || !Set.of("owned", "absent").contains(values.get("CANDIDATE"))
                || !Set.of("yes", "no").contains(values.get("BUILD_ACTIVE"))
                || !Set.of("yes", "no").contains(values.get("UNIT_PRESENT"))
                || !Set.of("yes", "no", "not-applicable").contains(values.get("CONTAINER_PRESENT"))
                || (backend.equals("native") != "not-applicable".equals(values.get("CONTAINER_PRESENT"))))
            return unknown("DEPLOYMENT_ORIGINAL_STATUS_INVALID");
        try {
            if (!values.get("RETAINED_COUNT").matches("0|[1-9][0-9]{0,3}"))
                return unknown("DEPLOYMENT_ORIGINAL_STATUS_INVALID");
            return success(new DeploymentOriginalPublicationStatus(
                    "none".equals(values.get("CURRENT")) ? Optional.empty() : Optional.of(values.get("CURRENT")),
                    values.get("CURRENT_KIND"), values.get("CURRENT_MODE"), !"none".equals(values.get("SNAPSHOT")),
                    "none".equals(values.get("SNAPSHOT_OLD"))
                            ? Optional.empty()
                            : Optional.of(values.get("SNAPSHOT_OLD")),
                    values.get("SNAPSHOT_KIND"),
                    "none".equals(values.get("SNAPSHOT_RUNNING"))
                            ? Optional.empty()
                            : Optional.of("1".equals(values.get("SNAPSHOT_RUNNING"))),
                    !"none".equals(values.get("MAINTENANCE")), "owned".equals(values.get("CANDIDATE")),
                    "yes".equals(values.get("BUILD_ACTIVE")), "yes".equals(values.get("UNIT_PRESENT")),
                    "not-applicable".equals(values.get("CONTAINER_PRESENT"))
                            ? Optional.empty()
                            : Optional.of("yes".equals(values.get("CONTAINER_PRESENT"))),
                    Integer.parseInt(values.get("RETAINED_COUNT"))));
        } catch (IllegalArgumentException malformed) {
            return unknown("DEPLOYMENT_ORIGINAL_STATUS_INVALID");
        }
    }

    /** Discards only a task-bound prepublish snapshot after read-only proof that old current is unchanged. / 只在只读证明旧当前版本未变后丢弃原任务发布前快照。
     * @param unit exact original owner / 精确原归属
     * @param operation exact original snapshot operation / 精确原快照操作
     * @param attempt whether operation ownership is required / 是否要求操作归属
     * @return verified discard or retained uncertainty / 已核实丢弃或保留未知
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    OperationResult<Void> discardSnapshot(DeploymentUnitIdentity unit, java.util.UUID operation, boolean attempt) throws ApiException {
        Objects.requireNonNull(operation);
        var arguments = attempt
                ? List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString(), operation.toString())
                : List.of(unit.id(), unit.ownershipManifestSha256(), task.id().toString());
        var observed = sender.send("deployment.publication.snapshot-discard.v1", unit,
                attempt ? "snapshot-discard-attempt" : "snapshot-discard-task", arguments, Duration.ofSeconds(60), 4096);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (observed.outcome() == Outcome.CANCELLED || observed.outcome() == Outcome.UNKNOWN)
            return withoutValue(observed);
        if (!complete(observed) || !"SNAPSHOT_DISCARDED=1\n".equals(observed.value().orElseThrow().stdout()))
            return unknown("DEPLOYMENT_SNAPSHOT_DISCARD_UNCONFIRMED");
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

}
