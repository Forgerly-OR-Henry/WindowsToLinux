package gold.debug.windowstolinux.mod.deployment.linux.publish;

import java.nio.file.Path;
import java.time.Duration;

import java.util.*;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityParser;
import gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityProbe;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;

/** Sends fixed pre-upload probes, task-owned candidate creation and one exact archive transfer. / 发送固定上传前探测、任务所属候选创建及一次精确归档传输。 */
public final class DeploymentCandidateChannel {
    /** Original borrowed connection. / 原借用连接。 */
    private final RemoteSession session;

    /** Original host review capability. / 原宿主审核能力。 */
    private final InteractionService interaction;

    /** Exact saved server. / 精确已保存服务器。 */
    private final ServerId server;

    /** Original admitted task. / 原准入任务。 */
    private final TaskContext task;

    /** New-domain reviewed plan identity. / 新域已审阅计划身份。 */
    private final String revision;

    /** Shared cumulative build execution budget. / 共用的累计构建执行预算。 */
    private final DeploymentBuildCommandChannel commands;

    /** Caller-owned check immediately before each send. / 每次发送紧前调用方持有的核对。 */
    private final DeploymentSendGuard beforeSend;

    private final DeploymentRemoteLayout layout;

    /** Binds original capabilities without taking ownership of the session or archive. / 绑定原能力，不接管会话或归档所有权。
     * @param session original borrowed session / 原借用会话
     * @param interaction actual host review / 真实宿主审核
     * @param server exact saved server / 精确已保存服务器
     * @param task original task / 原任务
     * @param revision reviewed plan digest / 已审阅计划摘要
     * @param budget shared cumulative build budget / 共用累计构建预算 */
    public DeploymentCandidateChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, String revision, DeploymentBuildBudget budget) {
        this(session, interaction, server, task, revision, budget, DeploymentSendGuard.NONE,
            DeploymentRemoteLayout.original());
    }

    /** Binds an additional caller-owned pre-dispatch check. / 绑定调用方附加的发送前核对。
     * @param session original borrowed session / 原借用会话
     * @param interaction actual host review / 真实宿主审核
     * @param server exact saved server / 精确已保存服务器
     * @param task original task / 原任务
     * @param revision reviewed plan digest / 已审阅计划摘要
     * @param budget shared build budget / 共用构建预算
     * @param beforeSend caller-owned check / 调用方持有的核对 */
    public DeploymentCandidateChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, String revision, DeploymentBuildBudget budget, DeploymentSendGuard beforeSend) {
        this(session, interaction, server, task, revision, budget, beforeSend, DeploymentRemoteLayout.original());
    }

    public DeploymentCandidateChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, String revision, DeploymentBuildBudget budget,
                                      DeploymentRemoteLayout layout) {
        this(session, interaction, server, task, revision, budget, DeploymentSendGuard.NONE, layout);
    }

    public DeploymentCandidateChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, String revision, DeploymentBuildBudget budget,
                                      DeploymentSendGuard beforeSend, DeploymentRemoteLayout layout) {
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        this.beforeSend = Objects.requireNonNull(beforeSend);
        this.layout = Objects.requireNonNull(layout);
        this.commands = new DeploymentBuildCommandChannel(session, interaction, server, task, revision, budget,
            beforeSend, layout);
    }

    /** Collects the original typed read-only host matrix with the actual SSH fingerprint. / 以实际SSH指纹采集原类型化只读主机矩阵。
     * @return typed host evidence or an unconfirmed probe / 类型化主机证据或未确认探测
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public OperationResult<DeploymentLinuxCapabilityFacts> hostFacts() throws ApiException {
        return hostFacts("deployment.candidate.host-probe.v1", DeploymentPlatformCapabilityProbe.render());
    }

    /** Collects runtime-specific facts, separately reviewing possible container-store initialization. / 采集运行时所需事实，单独审核可能的容器存储初始化。
     * @param runtime reviewed original runtime / 已审阅原运行时
     * @return confirmed original host facts / 已确认原主机事实
     * @throws ApiException if exact admission fails / 精确准入失败时 */
    public OperationResult<DeploymentLinuxCapabilityFacts> hostFacts(DeploymentRuntimeDefinition runtime) throws ApiException {
        Objects.requireNonNull(runtime);
        return runtime instanceof DeploymentRuntimeDefinition.Container container
            ? hostFacts("deployment.candidate.container-probe.v1", DeploymentPlatformCapabilityProbe.renderForContainer(container.engine()))
            : hostFacts();
    }

    /** Sends one fixed matrix and keeps unobserved capabilities conservative. / 发送固定矩阵，对未观测能力保持保守。
     * @param operation exact registered action / 精确登记动作
     * @param script fixed program / 固定程序
     * @return typed observed facts / 类型化观测事实
     * @throws ApiException if approval fails / 审批失败时 */
    private OperationResult<DeploymentLinuxCapabilityFacts> hostFacts(String operation, String script) throws ApiException {
        var observed = commands.send(operation, "deployment-host", server.id(),
            script, new byte[0], Duration.ofMinutes(2), 65536);
        if (observed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(observed);
        if (observed.value().isEmpty())
            return failure(ProblemCode.IO_FAILURE, "DEPLOYMENT_HOST_PROBE_MISSING");
        if (!complete(observed))
            return failure(ProblemCode.IO_FAILURE, "DEPLOYMENT_HOST_PROBE_UNCONFIRMED");
        try {
            var values = firstValues(observed.value().orElseThrow().stdout());
            String fingerprint = session.facts().fingerprint();
            if (fingerprint.isBlank())
                return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HOST_FINGERPRINT_MISSING");
            return success(DeploymentPlatformCapabilityParser.fromValues(values, fingerprint));
        } catch (IllegalArgumentException malformed) {
            return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HOST_PROBE_INVALID");
        }
    }

    /** Reads available bytes in the controlled root before creating a candidate. / 创建候选前读取受控根可用字节。
     * @return observed nonnegative available bytes / 已观察的非负可用字节
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public record CapacityObservation(long availableBytes, java.util.UUID task, String server, String revision,
                                      String layoutSha256, String target, String requestSha256, String resultSha256) {
    }

    public OperationResult<Long> availableBytes() throws ApiException {
        var result = capacityObservation();
        return result.outcome() == TaskResult.SUCCEEDED ? success(result.value().orElseThrow().availableBytes()) : withoutValue(result);
    }

    public static String capacityTarget(DeploymentRemoteLayout layout) {
        return layout.equals(DeploymentRemoteLayout.original()) ? "/var/lib" : layout.paths().stateRoot();
    }

    /** Renders the existing fixed read-only observation without sending it. */
    public static String capacityScript(DeploymentRemoteLayout layout) {
        String script = "set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\n"
            + "stat -f -c '%a %S' -- '/var/lib'\n";
        if (!layout.equals(DeploymentRemoteLayout.original())) {
            String target = layout.paths().stateRoot();
            String components = Arrays.stream(target.substring(1).split("/"))
                .map(DeploymentCandidateChannel::quoted).collect(java.util.stream.Collectors.joining(" "));
            script = """
                set -euo pipefail
                wtl_capacity_directory=/
                test -d "$wtl_capacity_directory" && test ! -L "$wtl_capacity_directory"
                test "$(stat -c '%%u:%%g' -- "$wtl_capacity_directory")" = 0:0
                for wtl_capacity_component in %s; do
                    wtl_capacity_next="${wtl_capacity_directory%%/}/$wtl_capacity_component"
                    test ! -L "$wtl_capacity_next"
                    if test -e "$wtl_capacity_next"; then
                        test -d "$wtl_capacity_next"
                        test "$(stat -c '%%u:%%g' -- "$wtl_capacity_next")" = 0:0
                        wtl_capacity_directory="$wtl_capacity_next"
                    else
                        break
                    fi
                done
                stat -f -c '%%a %%S' -- "$wtl_capacity_directory"
                """.formatted(components);
        }
        return script;
    }

    public OperationResult<CapacityObservation> capacityObservation() throws ApiException {
        String script = capacityScript(layout);
        String target = capacityTarget(layout);
        String requestSha256;
        try (var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(script, new byte[0], Duration.ofSeconds(20), 1024)) {
            requestSha256 = digest(RemoteActions.command(server, revision, request).exactAction());
        }
        var observed = commands.send("deployment.candidate.capacity.v1", "deployment-workspace", target, script,
            new byte[0], Duration.ofSeconds(20), 1024);
        if (observed.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(observed);
        if (observed.value().isEmpty())
            return failure(ProblemCode.IO_FAILURE, "DEPLOYMENT_CAPACITY_MISSING");
        if (!complete(observed))
            return failure(ProblemCode.IO_FAILURE, "DEPLOYMENT_CAPACITY_UNCONFIRMED");
        String stdout = observed.value().orElseThrow().stdout();
        if (!stdout.matches("[0-9]{1,20} [0-9]{1,20}\\n"))
            return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_CAPACITY_INVALID");
        try {
            String[] parts = stdout.trim().split(" ");
            return success(new CapacityObservation(Math.multiplyExact(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                task.id(), server.id(), revision, layout.digest(), target, requestSha256, digest(stdout)));
        } catch (ArithmeticException | NumberFormatException invalid) {
            return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_CAPACITY_INVALID");
        }
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** Exclusively creates and then re-queries one original-task candidate. / 排他创建并回查一份原任务候选。
     * @param workspace reviewed candidate identity / 已审阅候选身份
     * @param maxWorkspaceBytes reviewed capacity / 已审阅容量
     * @return confirmed task-owned candidate / 已确认任务所属候选
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public OperationResult<DeploymentRemoteWorkspace> create(DeploymentRemoteWorkspace workspace,
                                                             long maxWorkspaceBytes) throws ApiException {
        requireWorkspace(workspace);
        if (maxWorkspaceBytes < 64L * 1024 * 1024 || maxWorkspaceBytes > 128L * 1024 * 1024 * 1024)
            throw new IllegalArgumentException("Candidate budget is outside helper limits");
        String original = task.id().toString();
        String args = quoted(workspace.applicationId()) + " " + quoted(workspace.candidateId()) + " "
            + maxWorkspaceBytes + " " + quoted(original);
        String helper = quoted(layout.helperPath());
        var created = commands.send("deployment.candidate.create.v1", "deployment-candidate", workspace.candidateRoot(),
            helper + " candidate-create-task " + args, new byte[0], Duration.ofMinutes(5), 4096);
        if (created.outcome() == TaskResult.CANCELLED || created.outcome() == TaskResult.UNKNOWN)
            return withoutValue(created);
        if (created.outcome() == TaskResult.FAILED && created.value().isEmpty())
            return withoutValue(created);
        if (!complete(created)
            || !created.value().orElseThrow().stdout().equals("CANDIDATE=" + workspace.candidateRoot() + "\n"))
            return unknown("DEPLOYMENT_CANDIDATE_CREATE_UNCONFIRMED");
        var queried = commands.send("deployment.candidate.query.v1", "deployment-candidate", workspace.candidateRoot(),
            helper + " candidate-query-task " + quoted(workspace.applicationId()) + " "
                + quoted(workspace.candidateId()) + " " + quoted(original),
            new byte[0], Duration.ofSeconds(20), 4096);
        if (!complete(queried)
            || !queried.value().orElseThrow().stdout().equals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n"))
            return unknown("DEPLOYMENT_CANDIDATE_OWNER_UNCONFIRMED");
        return success(workspace);
    }

    /** Uploads the still-owned frozen archive through the public host transport exactly once. / 经公开宿主传输恰好一次上传仍受管的冻结归档。
     * @param archive original frozen archive / 原冻结归档
     * @param workspace task-owned candidate / 任务所属候选
     * @return exact endpoint and byte confirmation / 精确端点和字节确认
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public OperationResult<TransferResult> upload(DeploymentSourceArchive archive, DeploymentRemoteWorkspace workspace)
        throws ApiException {
        Objects.requireNonNull(archive);
        requireWorkspace(workspace);
        Path local = archive.path();
        String remote = workspace.candidateRoot() + "/mutable/source.tar.gz";
        var request = new TransferRequest(local, remote, archive.byteCount());
        var intent = RemoteActions.transfer(server, revision, request, archive.sha256(), true)
            .withSemantics(new ActionSemantics("deployment.candidate.upload.v1",
                Set.of(new ActionTarget(server.id(), "deployment-candidate", remote))));
        var grant = interaction.approve(intent, task);
        if (grant.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(grant);
        final OperationResult<TransferResult> result;
        beforeSend.verify();
        try {
            result = session.upload(request, grant.value().orElseThrow());
        } catch (ApiException failure) {
            if (failure.problem().code() == ProblemCode.ACCESS_DENIED
                || failure.problem().code() == ProblemCode.INVALID_INPUT)
                throw failure;
            return unknown("DEPLOYMENT_UPLOAD_DISPATCH_UNCONFIRMED");
        }
        if (result.outcome() != TaskResult.SUCCEEDED)
            return result;
        if (result.value().isEmpty())
            return unknown("DEPLOYMENT_UPLOAD_RECEIPT_MISSING");
        var receipt = result.value().orElseThrow();
        if (!receipt.localPath().equals(local) || !receipt.remoteAbsolutePath().equals(remote)
            || receipt.transferredBytes() != archive.byteCount() || !receipt.sha256().equals(archive.sha256()))
            return unknown("DEPLOYMENT_UPLOAD_RECEIPT_CONFLICT");
        return result;
    }

    /** Re-queries original ownership and independently hashes the published remote archive. / 回查原归属并独立散列已发布的远端归档。
     * @param workspace task-owned candidate / 任务所属候选
     * @param archive original frozen archive / 原冻结归档
     * @return verified candidate identity / 已核验候选身份
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public OperationResult<DeploymentRemoteWorkspace> verify(DeploymentRemoteWorkspace workspace,
                                                             DeploymentSourceArchive archive) throws ApiException {
        return verify(verification(workspace, archive));
    }

    /** Captures the fixed observer and its exact client consumer from the original handles; grants nothing. */
    public Verification verification(DeploymentRemoteWorkspace workspace, DeploymentSourceArchive archive) {
        requireWorkspace(workspace);
        Objects.requireNonNull(archive);
        String root = workspace.candidateRoot();
        String remote = root + "/mutable/source.tar.gz";
        String helper = quoted(layout.helperPath());
        String script = "set -euo pipefail\n" + helper + " candidate-query-task " + quoted(workspace.applicationId())
            + " " + quoted(workspace.candidateId()) + " " + quoted(task.id().toString()) + "\n" + "test -f "
            + quoted(remote) + " && test ! -L " + quoted(remote) + "\n" + "test \"$(realpath -e -- "
            + quoted(remote) + ")\" = " + quoted(remote) + "\n" + "printf 'BYTES='; stat -c '%s' -- "
            + quoted(remote) + "\n" + "printf 'SHA256='; sha256sum -- " + quoted(remote) + " | cut -d ' ' -f1\n"
            + "printf 'OWNER='; stat -c '%u' -- " + quoted(remote) + "\n";
        String expected = "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archive.byteCount() + "\nSHA256="
            + archive.sha256() + "\nOWNER=0\n";
        return new Verification(session, server, task, revision, layout, workspace, archive, script, expected);
    }

    /** Uses only a specification captured from these same original capabilities. */
    public OperationResult<DeploymentRemoteWorkspace> verify(Verification specification) throws ApiException {
        if (!Objects.requireNonNull(specification).matches(session, server, task, revision, layout,
            specification.workspace(), specification.archive()))
            throw new IllegalArgumentException("Candidate verification differs from its original capabilities");
        requireWorkspace(specification.workspace());
        var result = commands.send("deployment.candidate.verify.v1", "deployment-candidate",
            specification.workspace().candidateRoot(), specification.script(), new byte[0], Duration.ofSeconds(30), 4096);
        if (!complete(result))
            return unconfirmedVerification(result);
        return result.value().orElseThrow().stdout().equals(specification.expectedOutput())
            ? success(specification.workspace())
            : unknown("DEPLOYMENT_UPLOAD_REMOTE_CONFLICT");
    }

    private static OperationResult<DeploymentRemoteWorkspace> unconfirmedVerification(OperationResult<CommandResult> result) {
        var details = new LinkedHashMap<String, String>();
        details.put("causeOutcome", result.outcome().name());
        details.put("causeCode", result.problem().map(value -> value.code().name()).orElse("ABSENT"));
        details.put("causeEvent", result.problem().map(value -> value.eventCode().equals("AI_RESPONSE_INVALID")
            ? "AI_RESPONSE_INVALID" : "OTHER").orElse("ABSENT"));
        result.problem().filter(value -> value.code() == ProblemCode.CONTRACT_VIOLATION
            && value.eventCode().equals("AI_RESPONSE_INVALID")).ifPresent(value -> {
            String code = value.context().get("nativeValidationCode");
            // Deliberate finite wire domain; no dependency on the app-ai implementation enum.
            details.put("causeNativeValidationCode", code != null && code.length() <= 2 && code.matches("0|[1-9]|1[0-5]") ? code : "OTHER");
        });
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            "DEPLOYMENT_UPLOAD_REMOTE_UNCONFIRMED", "Reviewed candidate evidence could not be confirmed", details, Optional.empty())));
    }

    /** Immutable fixed protocol minted by the real producer, never an observation or permission. */
    public static final class Verification {
        private final RemoteSession session;

        private final ServerId server;

        private final TaskContext task;

        private final String revision;

        private final DeploymentRemoteLayout layout;

        private final DeploymentRemoteWorkspace workspace;

        private final DeploymentSourceArchive archive;

        private final String script;

        private final String expectedOutput;

        private Verification(RemoteSession session, ServerId server, TaskContext task, String revision,
                             DeploymentRemoteLayout layout, DeploymentRemoteWorkspace workspace,
                             DeploymentSourceArchive archive, String script, String expectedOutput) {
            this.session = session;
            this.server = server;
            this.task = task;
            this.revision = revision;
            this.layout = layout;
            this.workspace = workspace;
            this.archive = archive;
            this.script = script;
            this.expectedOutput = expectedOutput;
        }

        public boolean matches(RemoteSession session, ServerId server, TaskContext task, String revision,
                               DeploymentRemoteLayout layout, DeploymentRemoteWorkspace workspace,
                               DeploymentSourceArchive archive) {
            return this.session == session && this.server == server && this.task == task
                && this.revision.equals(revision) && this.layout.equals(layout)
                && this.workspace == workspace && this.archive == archive;
        }

        public DeploymentRemoteWorkspace workspace() {
            return workspace;
        }

        public DeploymentSourceArchive archive() {
            return archive;
        }

        public String script() {
            return script;
        }

        public String guardedScript() {
            return DeploymentManagedHelperBundle.verifiedCommand(script, layout);
        }

        public String expectedOutput() {
            return expectedOutput;
        }
    }

    private void requireWorkspace(DeploymentRemoteWorkspace workspace) {
        if (!Objects.requireNonNull(workspace).layout().equals(layout))
            throw new IllegalArgumentException("Candidate workspace differs from the captured layout");
    }

    /** Retains the first observation for an original flat capability field. / 为原扁平能力字段保留首次观测。
     * @param output bounded fixed probe output / 有界固定探测输出
     * @return first observed fields / 首次观测字段 */
    private static Map<String, String> firstValues(String output) {
        var fields = new LinkedHashMap<String, String>();
        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0)
                fields.putIfAbsent(trimmed.substring(0, equals), trimmed.substring(equals + 1));
        }
        return Map.copyOf(fields);
    }

    /** Confirms complete zero-exit output from the original host. / 确认原宿主完整零退出回执。
     * @param observed original command observation / 原命令观测
     * @return whether complete success was observed / 是否观察到完整成功 */
    private static boolean complete(OperationResult<CommandResult> observed) {
        return observed.outcome() == TaskResult.SUCCEEDED && observed.value().isPresent()
            && observed.value().orElseThrow().exitCode().equals(Optional.of(0))
            && !observed.value().orElseThrow().truncated();
    }

    /** Quotes one reviewed literal. / 引用一个已审阅字面量。
     * @param value exact literal / 精确字面量
     * @return safely quoted literal / 安全引用的字面量 */
    private static String quoted(String value) {
        return DeploymentBuildCommandChannel.shellQuote(value);
    }

    /** Preserves a public result without inventing a payload. / 不虚构载荷地保留公开结果。
     * @param result original result / 原结果
     * @param <T> desired payload / 目标载荷
     * @return same outcome and problem / 相同结果与问题 */
    private static <T> OperationResult<T> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports one confirmed observation. / 报告一项已确认观测。
     * @param value confirmed value / 已确认值
     * @param <T> observed type / 观测类型
     * @return successful result / 成功结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Reports a known pre-effect failure. / 报告已知副作用前失败。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param <T> expected payload / 预期载荷
     * @return failed result / 失败结果 */
    private static <T> OperationResult<T> failure(ProblemCode code, String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Keeps an effect unconfirmed without replay. / 不重放地保留未知副作用。
     * @param event stable event / 稳定事件
     * @param <T> expected payload / 预期载荷
     * @return unknown result / 未知结果 */
    private static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Builds a safe bounded diagnostic. / 构造安全有界诊断。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return public problem / 公开问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Reviewed candidate evidence could not be confirmed", Map.of(),
            Optional.empty());
    }
}
