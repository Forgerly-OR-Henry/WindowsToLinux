package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentRestoreEvidence.*;
import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentRestoreSteps.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublicationCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Complete configuration/secret authority and original candidate preparation. / 完整配置与秘密权限及原候选准备。 */
final class DeploymentRestorePreparation {
    /** Provider-owned target and secret capabilities. / 提供方所属目标与秘密能力。 */
    private final ModContext context;

    /** Shared original publication and reservation boundary. / 共用原发布及预留边界。 */
    private final DeploymentOriginalPublicationCommit commit;

    /** Binds preparation to the original publication transaction. / 将准备绑定原发布事务。
     * @param context actual provider context / 实际提供方上下文
     * @param commit shared publication boundary / 共用发布边界 */
    DeploymentRestorePreparation(ModContext context, DeploymentOriginalPublicationCommit commit) {
        this.context = context;
        this.commit = commit;
    }

    /** Records original target states and formal intent before staging immutable inputs. / 暂存不可变输入前记录原目标状态与正式意图。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param session authenticated borrowed session / 已认证借用会话
     * @param current reviewed actual current releases / 已审阅实际当前发布
     * @param credentials target-authorized original secret references / 目标已授权原秘密引用
     * @param task authentic original task / 真实原任务
     * @return staging conclusion or retained uncertainty / 暂存结论或留存未知
     * @throws Exception if authority, persistence or remote evidence is unavailable / 权限、持久化或远端依据不可用时 */
    OperationResult<Void> beginPreparation(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                                           RemoteSession session, Map<String, String> current, Map<DeploymentSecretReference, SecretRef> credentials,
                                           TaskContext task) throws Exception {
        var components = cursor.facts.putObject("components");
        for (var component : plan.proposal.graph().components()) {
            var unit = components.putObject(component.componentId())
                .put("candidate", plan.candidate(cursor.entry.publicValue().id(), component.componentId()))
                .put("beforeDigest", current.get(component.componentId()));
            var state = "ABSENT";
            if (!current.get(component.componentId()).equals("-")) {
                var observed = publication(context, session, plan.request, task).observe(component.unit(),
                    component.reviewedRuntime());
                if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
                    return copy(observed);
                state = observed.value().orElseThrow().state().name();
            }
            unit.put("beforeState", state);
            unit.putObject("steps");
            unit.putObject("files");
        }
        cursor.facts.put("createdAt", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        cursor.facts.put("changed", true);
        cursor.save(DeploymentHandoffState.UNKNOWN);
        var publication = new DeploymentPublication(plan.proposal.graph(), plan.proposal.units());
        commit.begin(publicationPlan(cursor, plan, publication, credentials), publication, task, "deployment-restore");
        cursor.facts.put("executionStarted", true);
        cursor.save(DeploymentHandoffState.UNKNOWN);
        for (var metadata : plan.archive.secrets())
            new DeploymentSecretRepository(context.db()).saveRevision(task, metadata);
        var publisher = publication(context, session, plan.request, task);
        // All plaintext authorizations and bounded renders were checked before the first effect.
        for (var unit : plan.proposal.units()) {
            for (String format : List.of("systemd", "container")) {
                byte[] bytes = DeploymentPublicationArguments.configuration(unit.configuration(), format);
                try {
                    cursor.facts.put("immutableStageAttempted", true);
                    cursor.save(DeploymentHandoffState.UNKNOWN);
                    if (publisher.stageConfiguration(unit.unit(), unit.configuration().sha256(), format, bytes)
                        .outcome() != Outcome.SUCCEEDED)
                        return cursor.uncertain("DEPLOYMENT_RESTORE_CONFIGURATION_STAGE_UNKNOWN");
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
            for (var revision : unit.secrets()) {
                var ref = credentials.get(revision);
                cursor.facts.put("immutableStageAttempted", true);
                cursor.save(DeploymentHandoffState.UNKNOWN);
                var result = context.secrets().use(ref, ref.purpose(), task,
                    bytes -> publisher.stageSecret(unit.unit(), revision.identifier(), revision.revision(), bytes));
                if (result.outcome() != Outcome.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_SECRET_STAGE_UNKNOWN");
            }
        }
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Creates and prepares one original candidate with verified files and distinct database candidates. / 以已核实文件及独立数据库候选创建并准备单项原候选。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param component original component identity / 原组件身份
     * @param channel fixed original helper channel / 固定原helper频道
     * @param publisher original publication channel / 原发布频道
     * @param session authenticated borrowed session / 已认证借用会话
     * @param task authentic original task / 真实原任务
     * @return complete preparation or retained uncertainty / 完整准备或留存未知
     * @throws Exception if inputs, authority or original receipts are unavailable / 输入、权限或原回执不可用时 */
    OperationResult<Void> prepareComponent(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                                           DeploymentApplicationGraph.Component component, DeploymentHandoffChannel channel,
                                           DeploymentPublicationChannel publisher, RemoteSession session, TaskContext task) throws Exception {

        if (task.cancelToken().requested())
            return cursor.uncertain("DEPLOYMENT_RESTORE_CANCELLED_WITH_CANDIDATES");
        if (!targets(plan.proposal.graph(), channel).equals(before(cursor)))
            return cursor.uncertain("DEPLOYMENT_RESTORE_CURRENT_CHANGED");
        var unit = cursor.unit(component.componentId());
        unit.put("createAttempted", true);
        cursor.save(DeploymentHandoffState.UNKNOWN);
        String candidate = unit.path("candidate").asText();
        var created = send(channel, "handoff-restore-create", identity(plan, cursor, component.componentId()),
            Duration.ofMinutes(2));
        if (!DeploymentHandoffChannel.receipt(created, "RESTORE_CANDIDATE=" + root(candidate) + "\n"))
            return cursor.uncertain("DEPLOYMENT_RESTORE_CANDIDATE_CREATE_UNKNOWN");
        unit.put("created", true);
        cursor.save(DeploymentHandoffState.UNKNOWN);
        for (var input : plan.files.stream()
            .filter(value -> value.artifact().componentId().equals(component.componentId())).toList()) {
            var file = input.file();
            var facts = unit.withObject("files").putObject(file.bindingId()).put("uploadAttempted", true);
            cursor.save(DeploymentHandoffState.UNKNOWN);
            DeploymentRestorePlan.verifyLocal(file);
            var transfer = new TransferRequest(file.verifiedLocalFile(),
                root(candidate) + "/mutable/restore/" + input.member(), file.byteCount());
            var approved = context.interaction().approve(RemoteActions.transfer(plan.request.target(),
                plan.request.reviewedTargetSha256(), transfer, file.sha256(), true), task);
            if (approved.outcome() != Outcome.SUCCEEDED)
                return cursor.uncertain("DEPLOYMENT_RESTORE_UPLOAD_NOT_APPROVED");
            var uploaded = session.upload(transfer, approved.value().orElseThrow());
            if (uploaded.outcome() != Outcome.SUCCEEDED || uploaded.value().isEmpty())
                return cursor.uncertain("DEPLOYMENT_RESTORE_UPLOAD_UNKNOWN");
            var actual = uploaded.value().orElseThrow();
            if (actual.transferredBytes() != file.byteCount() || !actual.sha256().equals(file.sha256())
                || !actual.localPath().equals(file.verifiedLocalFile())
                || !actual.remoteAbsolutePath().equals(transfer.remoteAbsolutePath()))
                return cursor.uncertain("DEPLOYMENT_RESTORE_UPLOAD_IDENTITY_CHANGED");
            if (!DeploymentHandoffChannel.receipt(
                send(channel, "handoff-restore-file",
                    append(identity(plan, cursor, component.componentId()), input.member(),
                        Long.toString(file.byteCount()), file.sha256()),
                    Duration.ofMinutes(10)),
                "RESTORE_FILE_VERIFIED=1\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_FILE_UNCONFIRMED");
            facts.put("verified", true);
            cursor.save(DeploymentHandoffState.UNKNOWN);
        }
        if (!step(channel, cursor, plan, component.componentId(), "prepare", "restore-prepare",
            preparation(plan, cursor, component), "PREPARED=1\nCOMPONENT=" + component.componentId() + "\n"))
            return cursor.uncertain("DEPLOYMENT_RESTORE_PREPARE_UNKNOWN");
        for (var input : databases(plan, component.componentId())) {
            String key = databaseKey(input), databaseCandidate = databaseCandidate(cursor, component, input),
                artifact = databaseArtifact(cursor, input);
            if (!step(channel, cursor, plan, component.componentId(), "create-" + key, "restore-db-create",
                List.of(component.unit().id(), databaseCandidate, input.member(), artifact, input.file().sha256()),
                "DATABASE_CANDIDATE_CREATED=1\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_DATABASE_CREATE_UNKNOWN");
            if (!step(channel, cursor, plan, component.componentId(), "prepare-" + key, "database-restore-candidate",
                databaseArguments(cursor, component, input),
                "CANDIDATE_ID=" + databaseCandidate + "\nCONNECTION_TOKEN=" + databaseCandidate
                    + "\nINTEGRITY_VERIFIED=1\nSCHEMA_READABLE=1\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_DATABASE_PREPARE_UNKNOWN");
        }
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Verifies current target secret purposes and all bounded renders before effects. / 副作用前核实当前目标秘密用途及全部有界渲染。
     * @param plan complete immutable request / 完整不可变请求
     * @param task authentic original task / 真实原任务
     * @return target-authorized exact secret references / 目标已授权精确秘密引用
     * @throws Exception if purpose, metadata or configuration conflicts / 用途、元数据或配置冲突时 */
    Map<DeploymentSecretReference, SecretRef> authorize(DeploymentRestorePlan plan, TaskContext task) throws Exception {
        var credentials = new HashMap<DeploymentSecretReference, SecretRef>();
        for (var unit : plan.proposal.units())
            for (String format : List.of("systemd", "container"))
                Arrays.fill(DeploymentPublicationArguments.configuration(unit.configuration(), format), (byte) 0);
        for (var metadata : plan.archive.secrets()) {
            var ref = new SecretRef(metadata.credentialId(), metadata.purpose().orElseThrow());
            context.secrets().use(ref, ref.purpose(), task, value -> {
                if (value.length < 1 || value.length > 65536)
                    throw new IllegalArgumentException("Invalid target secret bound");
                return Boolean.TRUE;
            });
            var existing = new DeploymentSecretRepository(context.db()).findRevision(task, metadata.reference());
            if (existing.isPresent() && !existing.orElseThrow().equals(metadata))
                throw new IllegalArgumentException("Original secret metadata conflicts");
            credentials.put(metadata.reference(), ref);
        }
        return Map.copyOf(credentials);
    }

    /** Rejects conflicting local unit ownership or immutable configuration revisions. / 拒绝冲突的本地单元归属或不可变配置修订。
     * @param plan complete immutable request / 完整不可变请求
     * @param task authentic original task / 真实原任务
     * @throws Exception if an existing source row or configuration differs / 既有来源行或配置不符时 */
    void collision(DeploymentRestorePlan plan, TaskContext task) throws Exception {
        var repository = new DeploymentPublicationRepository(context.db());
        for (var unit : plan.proposal.units()) {
            var existing = repository.findUnit(task, unit.unit().id());
            if (existing.isPresent() && !existing.orElseThrow().equals(unit.unit()))
                throw new IllegalArgumentException("Target unit identity conflicts with existing source row");
            var configuration = new DeploymentConfigurationRepository(context.db()).find(task, unit.unit().id(),
                unit.configuration().revision());
            if (configuration.isPresent() && !configuration.orElseThrow().equals(unit.configuration()))
                throw new IllegalArgumentException("Target configuration revision conflicts");
        }
    }

    /** Binds reservations to the original handoff, target review and canonical timestamp. / 将预留绑定原交接、目标审阅及规范时间。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param publication exact restored whole application / 精确恢复整应用
     * @param credentials target-authorized original secret references / 目标已授权原秘密引用
     * @return original formal publication plan / 原正式发布计划
     * @throws Exception if original plan identity cannot be constructed / 原计划身份无法构造时 */
    DeploymentPublicationPlan publicationPlan(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                                              DeploymentPublication publication, Map<DeploymentSecretReference, SecretRef> credentials) throws Exception {
        return new DeploymentPublicationPlan(cursor.entry.publicValue().id(), Optional.empty(),
            plan.proposal.graph().applicationId(), plan.request.target(),
            DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, plan.request.reviewedTargetSha256(),
            DeploymentOriginalPublicationCommit.references(plan.request.target(), publication, credentials),
            Instant.parse(cursor.facts.path("createdAt").asText()));
    }
}
