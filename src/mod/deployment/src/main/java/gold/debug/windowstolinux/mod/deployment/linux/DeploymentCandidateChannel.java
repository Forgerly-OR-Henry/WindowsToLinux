package gold.debug.windowstolinux.mod.deployment.linux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPlatformCapabilityParser;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPlatformCapabilityProbe;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.source.DeploymentSourceArchive;

/** Sends fixed pre-upload probes, task-owned candidate creation and one exact archive transfer. / 发送固定上传前探测、任务所属候选创建及一次精确归档传输。 */
public final class DeploymentCandidateChannel {
    /** Original borrowed connection. / 原借用连接。 */
    private final RemoteSession session;

    /** Original host review capability. / 原宿主审核能力。 */
    private final InteractionService interaction;

    /** Exact saved server. / 精确已保存服务器。 */
    private final ServerRef server;

    /** Original admitted task. / 原准入任务。 */
    private final TaskContext task;

    /** New-domain reviewed plan identity. / 新域已审阅计划身份。 */
    private final String revision;

    /** Shared cumulative build execution budget. / 共用的累计构建执行预算。 */
    private final DeploymentBuildCommandChannel commands;

    /** Binds original capabilities without taking ownership of the session or archive. / 绑定原能力，不接管会话或归档所有权。
     * @param session original borrowed session / 原借用会话
     * @param interaction actual host review / 真实宿主审核
     * @param server exact saved server / 精确已保存服务器
     * @param task original task / 原任务
     * @param revision reviewed plan digest / 已审阅计划摘要
     * @param budget shared cumulative build budget / 共用累计构建预算 */
    public DeploymentCandidateChannel(RemoteSession session, InteractionService interaction, ServerRef server,
            TaskContext task, String revision, DeploymentBuildBudget budget) {
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        this.commands = new DeploymentBuildCommandChannel(session, interaction, server, task, revision, budget);
    }

    /** Collects the original typed read-only host matrix with the actual SSH fingerprint. / 以实际SSH指纹采集原类型化只读主机矩阵。
     * @return typed host evidence or an unconfirmed probe / 类型化主机证据或未确认探测
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public OperationResult<DeploymentLinuxCapabilityFacts> hostFacts() throws ApiException {
        var observed = commands.send("deployment.candidate.host-probe.v1", "deployment-host", server.id(),
                DeploymentPlatformCapabilityProbe.render(), new byte[0], Duration.ofMinutes(2), 65536);
        if (observed.outcome() != Outcome.SUCCEEDED)
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
    public OperationResult<Long> availableBytes() throws ApiException {
        String script = "set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\n"
                + "stat -f -c '%a %S' -- '/var/lib'\n";
        var observed = commands.send("deployment.candidate.capacity.v1", "deployment-workspace", "/var/lib", script,
                new byte[0], Duration.ofSeconds(20), 1024);
        if (observed.outcome() != Outcome.SUCCEEDED)
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
            return success(Math.multiplyExact(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
        } catch (ArithmeticException | NumberFormatException invalid) {
            return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_CAPACITY_INVALID");
        }
    }

    /** Exclusively creates and then re-queries one original-task candidate. / 排他创建并回查一份原任务候选。
     * @param workspace reviewed candidate identity / 已审阅候选身份
     * @param maxWorkspaceBytes reviewed capacity / 已审阅容量
     * @return confirmed task-owned candidate / 已确认任务所属候选
     * @throws ApiException if host admission fails before dispatch / 宿主发送前准入失败时 */
    public OperationResult<DeploymentRemoteWorkspace> create(DeploymentRemoteWorkspace workspace,
            long maxWorkspaceBytes) throws ApiException {
        Objects.requireNonNull(workspace);
        if (maxWorkspaceBytes < 64L * 1024 * 1024 || maxWorkspaceBytes > 128L * 1024 * 1024 * 1024)
            throw new IllegalArgumentException("Candidate budget is outside helper limits");
        String original = task.id().toString();
        String args = quoted(workspace.applicationId()) + " " + quoted(workspace.candidateId()) + " "
                + maxWorkspaceBytes + " " + quoted(original);
        String helper = quoted(DeploymentManagedHelperBundle.PATH);
        var created = commands.send("deployment.candidate.create.v1", "deployment-candidate", workspace.candidateRoot(),
                helper + " candidate-create-task " + args, new byte[0], Duration.ofMinutes(5), 4096);
        if (created.outcome() == Outcome.CANCELLED || created.outcome() == Outcome.UNKNOWN)
            return withoutValue(created);
        if (created.outcome() == Outcome.FAILED && created.value().isEmpty())
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
        Objects.requireNonNull(workspace);
        Path local = archive.path();
        String remote = workspace.candidateRoot() + "/mutable/source.tar.gz";
        var request = new TransferRequest(local, remote, archive.byteCount());
        var intent = RemoteActions.transfer(server, revision, request, archive.sha256(), true)
                .withSemantics(new ActionSemantics("deployment.candidate.upload.v1",
                        Set.of(new ActionTarget(server.id(), "deployment-candidate", remote))));
        var grant = interaction.approve(intent, task);
        if (grant.outcome() != Outcome.SUCCEEDED)
            return withoutValue(grant);
        final OperationResult<TransferResult> result;
        try {
            result = session.upload(request, grant.value().orElseThrow());
        } catch (ApiException failure) {
            if (failure.problem().code() == ProblemCode.ACCESS_DENIED
                    || failure.problem().code() == ProblemCode.INVALID_INPUT)
                throw failure;
            return unknown("DEPLOYMENT_UPLOAD_DISPATCH_UNCONFIRMED");
        }
        if (result.outcome() != Outcome.SUCCEEDED)
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
        String root = workspace.candidateRoot();
        String remote = root + "/mutable/source.tar.gz";
        String helper = quoted(DeploymentManagedHelperBundle.PATH);
        String script = "set -euo pipefail\n" + helper + " candidate-query-task " + quoted(workspace.applicationId())
                + " " + quoted(workspace.candidateId()) + " " + quoted(task.id().toString()) + "\n" + "test -f "
                + quoted(remote) + " && test ! -L " + quoted(remote) + "\n" + "test \"$(realpath -e -- "
                + quoted(remote) + ")\" = " + quoted(remote) + "\n" + "printf 'BYTES='; stat -c '%s' -- "
                + quoted(remote) + "\n" + "printf 'SHA256='; sha256sum -- " + quoted(remote) + " | cut -d ' ' -f1\n"
                + "printf 'OWNER='; stat -c '%u' -- " + quoted(remote) + "\n";
        var result = commands.send("deployment.candidate.verify.v1", "deployment-candidate", root, script, new byte[0],
                Duration.ofSeconds(30), 4096);
        if (!complete(result))
            return unknown("DEPLOYMENT_UPLOAD_REMOTE_UNCONFIRMED");
        String expected = "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archive.byteCount() + "\nSHA256="
                + archive.sha256() + "\nOWNER=0\n";
        return result.value().orElseThrow().stdout().equals(expected)
                ? success(workspace)
                : unknown("DEPLOYMENT_UPLOAD_REMOTE_CONFLICT");
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
        return observed.outcome() == Outcome.SUCCEEDED && observed.value().isPresent()
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
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Reports a known pre-effect failure. / 报告已知副作用前失败。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param <T> expected payload / 预期载荷
     * @return failed result / 失败结果 */
    private static <T> OperationResult<T> failure(ProblemCode code, String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Keeps an effect unconfirmed without replay. / 不重放地保留未知副作用。
     * @param event stable event / 稳定事件
     * @param <T> expected payload / 预期载荷
     * @return unknown result / 未知结果 */
    private static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
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
