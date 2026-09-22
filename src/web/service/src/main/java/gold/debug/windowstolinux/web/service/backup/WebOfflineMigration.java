package gold.debug.windowstolinux.web.service.backup;

import java.util.*;

import gold.debug.windowstolinux.shared.backup.contract.spi.*;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.backup.execution.migration.*;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.interaction.WebTaskInteractionService;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import tools.jackson.databind.JsonNode;

/**
 * Locks both hosts and delegates offline migration order and recovery to the shared coordinator.
 * <p>锁定两台主机，并将离线迁移顺序及恢复委派给共享协调器。
 */
public final class WebOfflineMigration {
    /**
     * Bound web backup service collaborator for backups.
     * <p>处理备份集合的Web备份服务协作对象。
     */
    private final WebBackupService backups;

    /**
     * Applications.
     * <p>应用集合。
     */
    private final WebApplicationInventory applications;

    /**
     * Bound web server service collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的Web服务器服务协作对象。
     */
    private final WebServerService servers;

    /**
     * Credential references or scoped secret-access service.
     * <p>凭据引用或限定作用域的秘密访问服务。
     */
    private final WebApplicationSecrets secrets;
    /**
     * Binds the supplied dependencies and state for web offline migration.
     * <p>为Web离线迁移绑定传入的依赖及状态。
     *
     * @param backups backups / 备份集合
     * @param applications applications / 应用集合
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    public WebOfflineMigration(WebBackupService backups, WebApplicationInventory applications, WebServerService servers,
            WebApplicationSecrets secrets) {
        this.backups = backups;
        this.applications = applications;
        this.servers = servers;
        this.secrets = secrets;
    }

    /**
     * Prepares an offline migration that locks both endpoints and delegates synchronization, stopped-source handoff and recovery to the shared coordinator.
     * <p>准备锁定两个端点的离线迁移，并将同步、停机源交接及恢复委派给共享协调器。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public PreparedWebOperation prepare(WebRequestContext context, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "applicationId", "targetServerId");
        String id = WebRequestValidator.text(input, "applicationId", 63),
                target = WebRequestValidator.text(input, "targetServerId", 63);
        var application = applications.require(context, id);
        var graph = applications.graph(application);
        String source = application.attributes().get("server_id").toString();
        var sourceServer = servers.require(context, source);
        var targetServer = servers.require(context, target);
        if (WebServerService.lockKey(sourceServer).equals(WebServerService.lockKey(targetServer)))
            throw new IllegalArgumentException("Migration requires distinct hosts");
        return new PreparedWebOperation("MIGRATE", input, List.of(source, target),
                List.of(WebServerService.lockKey(sourceServer), WebServerService.lockKey(targetServer)), true, null, id,
                null, interaction -> {
                    WebTaskInteractionService.approve(interaction, "MIGRATION_STOP_WINDOW", WebJsonCodec.object()
                            .put("applicationId", id).put("sourceServerId", source).put("targetServerId", target));
                    var initialState = applications.lifecycle(context, id, "REFRESH_STATUS").work()
                            .execute(interaction);
                    if (!Set.of("RUNNING", "INSTALLED").contains(initialState.path("state").asText()))
                        throw new IllegalStateException(
                                "Migration requires all source components running before the stop window");
                    char[] password = secrets.decisionSecret(context, interaction, "backup.password");
                    try {
                        var initial = backups.create(context, id, password, interaction);
                        String admission = "backup-" + UUID.randomUUID().toString().replace("-", "");
                        boolean daemon = graph.components().stream()
                                .anyMatch(component -> component.runtime().workload().supportsLifecycle());
                        var port = new OfflineMigrationPort() {
                            /**
                             * Final archive.
                             * <p>最终归档。
                             */
                            private JsonNode finalArchive;

                            /**
                             * Restored.
                             * <p>已恢复。
                             */
                            private JsonNode restored;

                            /**
                             * Restore entered.
                             * <p>恢复Entered。
                             */
                            private boolean restoreEntered;
                            /**
                             * Verifies the target prerequisites before permitting migration changes.
                             * <p>在允许迁移变更前验证目标前提条件。
                             *
                             * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
                             * @return constructed or resolved target preflight evidence / 构造或解析得到的目标预检证据
                             * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
                             */
                            @Override
                            public TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request)
                                    throws BackupException {
                                return checked(BackupFailureType.MIGRATION_PREFLIGHT_FAILED, () -> {
                                    var result = backups.restore(context, initial.path("id").asText(), target, password,
                                            interaction, true);
                                    return new TargetPreflightEvidence(true, true, true,
                                            result.path("availableBytes").asLong(),
                                            List.of("Exact backup and live target passed shared restore preflight"));
                                });
                            }

                            /**
                             * Copies the initial reviewed source state before the final stop window.
                             * <p>在最终停机窗口前复制初始已审阅源状态。
                             *
                             * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
                             * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
                             */
                            @Override
                            public SyncEvidence initialSync(OfflineMigrationRequest request) {
                                return sync(initial, false);
                            }

                            /**
                             * Stops source writes.
                             * <p>停止源码写入集合。
                             *
                             * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
                             * @return constructed or resolved source quiesce evidence / 构造或解析得到的源码停写证据
                             * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
                             * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
                             */
                            @Override
                            public SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request)
                                    throws BackupException {
                                try {
                                    pauseSource(context, source, graph, admission, interaction);
                                    var result = applications.lifecycle(context, id, daemon ? "STOP" : "REFRESH_STATUS")
                                            .work().execute(interaction);
                                    if (!Set.of("STOPPED", "INSTALLED").contains(result.path("state").asText()))
                                        throw new IllegalStateException("Source stop is unverified");
                                    return new SourceQuiesceEvidence(true, true, admission,
                                            List.of("Every managed source component is authoritatively stopped"));
                                } catch (Exception failure) {
                                    return new SourceQuiesceEvidence(false, false, admission, List.of(
                                            "Source quiesce was not verified; recover daemon state and task admission before retrying"));
                                }
                            }

                            /**
                             * Copies the final stopped-writer source state used for target activation.
                             * <p>复制供目标激活使用的最终停写源状态。
                             *
                             * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
                             * @param baseline baseline / 基线
                             * @param stopped stopped / 已停止
                             * @return constructed or resolved sync evidence / 构造或解析得到的同步证据
                             * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
                             */
                            @Override
                            public SyncEvidence finalSync(OfflineMigrationRequest request, SyncEvidence baseline,
                                    SourceQuiesceEvidence stopped) throws BackupException {
                                return checked(BackupFailureType.MIGRATION_SYNC_FAILED, () -> {
                                    finalArchive = backups.create(context, id, password, interaction, admission);
                                    return sync(finalArchive, true);
                                });
                            }

                            /**
                             * Restores and verify target.
                             * <p>恢复与验证目标。
                             *
                             * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
                             * @param finalSync final sync / 最终同步
                             * @return constructed or resolved target candidate evidence / 构造或解析得到的目标候选证据
                             * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
                             * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
                             */
                            @Override
                            public TargetCandidateEvidence restoreAndVerifyTarget(OfflineMigrationRequest request,
                                    SyncEvidence finalSync) throws BackupException {
                                return checked(BackupFailureType.MIGRATION_TARGET_FAILED, () -> {
                                    restoreEntered = true;
                                    restored = backups.restore(context, finalArchive.path("id").asText(), target,
                                            password, interaction, false);
                                    if (!restored.path("status").asText().equals("SUCCEEDED"))
                                        throw new IllegalStateException("Target activation failed");
                                    return new TargetCandidateEvidence(
                                            graph.applicationId() + "-" + finalSync.contentSha256().substring(0, 16),
                                            true, true, true,
                                            List.of("Target application health passed and external traffic remains unchanged"));
                                });
                            }

                            /**
                             * Discards target candidate.
                             * <p>清理目标候选。
                             *
                             * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
                             * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
                             */
                            @Override
                            public RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) {
                                boolean verified = !restoreEntered || restored != null
                                        && restored.path("status").asText().equals("FAILED_EXISTING_PRESERVED");
                                return new RecoveryEvidence(verified, verified,
                                        List.of(verified
                                                ? "Shared restore verified target preservation"
                                                : "Target recovery requires fresh verification"));
                            }

                            /**
                             * Recovers source identity or content read by the operation.
                             * <p>恢复操作读取的源身份或内容。
                             *
                             * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
                             * @param stopped stopped / 已停止
                             * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
                             * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
                             */
                            @Override
                            public RecoveryEvidence recoverSource(OfflineMigrationRequest request,
                                    SourceQuiesceEvidence stopped) throws BackupException {
                                return checked(BackupFailureType.MIGRATION_RECOVERY_FAILED, () -> {
                                    return recoverSourceState(context, id, source, daemon, graph, admission,
                                            interaction);
                                });
                            }
                        };
                        var result = new OfflineMigrationCoordinator(port).prepare(new OfflineMigrationRequest(
                                "migration-" + UUID.randomUUID().toString().replace("-", ""), graph.applicationId(),
                                source, target, initial.path("byteCount").asLong(), true));
                        interaction.completion(result.status() == OfflineMigrationStatus.READY_FOR_MANUAL_TRAFFIC_SWITCH
                                ? OperationCompletionState.SUCCEEDED
                                : result.status() == OfflineMigrationStatus.MANUAL_RECOVERY_REQUIRED
                                        ? OperationCompletionState.REVALIDATION_REQUIRED
                                        : OperationCompletionState.FAILED);
                        return WebJsonCodec.object().put("status", result.status().name()).put("sourceRetained", true)
                                .put("externalTrafficChanged", false).put("sourceTaskAdmission", admission);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                });
    }

    /**
     * Acquires migration maintenance and pauses source components through an authenticated session, retaining ownership-aware recovery.
     * <p>通过已认证会话获取迁移维护状态并暂停源组件，保留依据归属进行恢复的处理。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param graph graph / 图
     * @param admission the deterministic admission status / 确定性准入状态
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private void pauseSource(WebRequestContext context, String source, ManagedWebGraph graph, String admission,
            TaskInteraction interaction) throws Exception {
        servers.withSession(context, source, interaction, session -> {
            var paused = new ArrayList<gold.debug.windowstolinux.shared.model.managed.ManagedApplication>();
            try {
                for (var component : graph.components()) {
                    session.backupArtifacts().beginMaintenance(component.application(), admission);
                    paused.add(component.application());
                }
            } catch (Exception failure) {
                for (var app : paused.reversed())
                    try {
                        session.backupArtifacts().endMaintenance(app, admission);
                    } catch (Exception recovery) {
                        failure.addSuppressed(recovery);
                    }
                throw failure;
            }
            return null;
        });
    }

    /**
     * Recovers source state.
     * <p>恢复源码状态。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param daemon daemon / 守护线程
     * @param graph graph / 图
     * @param admission the deterministic admission status / 确定性准入状态
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved recovery evidence; null when no matching value is available / 构造或解析得到的恢复证据；没有匹配值时为 null
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private OfflineMigrationPort.RecoveryEvidence recoverSourceState(WebRequestContext context, String id,
            String source, boolean daemon, ManagedWebGraph graph, String admission, TaskInteraction interaction)
            throws Exception {
        boolean interrupted = Thread.interrupted();
        try {
            var result = applications.lifecycle(context, id, daemon ? "START" : "REFRESH_STATUS").work()
                    .execute(recoveryInteraction(interaction));
            boolean verified = Set.of("RUNNING", "INSTALLED").contains(result.path("state").asText());
            if (verified)
                servers.withSession(context, source, recoveryInteraction(interaction), session -> {
                    for (var component : graph.components())
                        session.backupArtifacts().endMaintenance(component.application(), admission);
                    return null;
                });
            return new OfflineMigrationPort.RecoveryEvidence(verified, verified,
                    List.of("Source recovery was followed by authoritative lifecycle observations"));
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * Builds sync evidence from the supplied sync inputs.
     * <p>根据所提供同步输入构建同步证据。
     *
     * @param backup the local backup page state / 本地备份页面状态
     * @param stopped stopped / 已停止
     * @return sync evidence from the supplied sync inputs / 根据所提供同步输入构建同步证据
     */
    private static OfflineMigrationPort.SyncEvidence sync(JsonNode backup, boolean stopped) {
        return new OfflineMigrationPort.SyncEvidence(backup.path("byteCount").asLong(), backup.path("digest").asText(),
                true, stopped, List.of("Complete local archive passed independent shared validation"));
    }

    /**
     * Validates supplied content before returning it to the next stage.
     * <p>在将所提供内容返回给下一阶段前完成校验。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static <T> T checked(BackupFailureType type, CheckedAction<T> action) throws BackupException {
        try {
            return action.run();
        } catch (Exception failure) {
            throw BackupException.create(type, "Web migration step failed; inspect task status before retrying",
                    failure);
        }
    }

    /**
     * Builds task interaction from the supplied recovery interaction inputs.
     * <p>根据所提供恢复交互输入构建任务交互。
     *
     * @param original original / 原始
     * @return task interaction from the supplied recovery interaction inputs / 根据所提供恢复交互输入构建任务交互
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static TaskInteraction recoveryInteraction(TaskInteraction original) {
        return new TaskInteraction() {
            /**
             * Accepts the callback without side effects because this adapter needs no additional action.
             * <p>接受回调且不产生副作用，因为当前适配器无需额外动作。
             *
             * @param code stable machine-readable classification code / 稳定的机器可读分类码
             * @param details details / 详情
             */
            @Override
            public void progress(String code, JsonNode details) {
            }

            /**
             * Rejects interactive recovery prompts because recovery requires previously trusted credentials.
             * <p>拒绝交互式恢复提示，因为恢复要求使用此前已信任的凭据。
             *
             * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
             * @param prompt prompt / 提示
             * @return constructed or resolved json node / 构造或解析得到的JSON节点
             * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
             */
            @Override
            public JsonNode decide(String kind, JsonNode prompt) {
                throw new IllegalStateException("Recovery requires previously trusted credentials");
            }

            /**
             * Accepts the callback without side effects because this adapter needs no additional action.
             * <p>接受回调且不产生副作用，因为当前适配器无需额外动作。
             */
            @Override
            public void checkCancelled() {
            }

            /**
             * Escalates an unsuccessful recovery completion to REVALIDATION_REQUIRED on the original task.
             * <p>将未成功的恢复完成状态升级为原任务的 REVALIDATION_REQUIRED。
             *
             * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
             */
            @Override
            public void completion(OperationCompletionState state) {
                if (state != OperationCompletionState.SUCCEEDED)
                    original.completion(OperationCompletionState.REVALIDATION_REQUIRED);
            }
        };
    }
    /**
     * Runs a migration step that can fail with a checked exception.
     * <p>执行可能以受检异常失败的迁移步骤。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     */
    @FunctionalInterface
    private interface CheckedAction<T> {
        /**
         * Runs T.
         * <p>运行T。
         *
         * @return constructed or resolved T / 构造或解析得到的T
         * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
         */
        T run() throws Exception;
    }
}
