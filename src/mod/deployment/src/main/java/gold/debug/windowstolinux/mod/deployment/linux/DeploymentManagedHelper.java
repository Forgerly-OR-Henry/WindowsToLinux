package gold.debug.windowstolinux.mod.deployment.linux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.ActionTarget;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.InteractionService;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/** Runs the fixed helper installation and probe through one borrowed, host-authorized remote session. / 通过借用的宿主授权远端会话执行固定 helper 安装与探测。 */
public final class DeploymentManagedHelper {
    /** Durable transition immediately before the reviewed installer is sent. / 经审核安装发送紧前的持久阶段转换。 */
    @FunctionalInterface
    public interface BeforeExecute {
        /** Persists the original dispatch phase. / 持久保存原发送阶段。
         * @throws ApiException if the original transition fails / 原阶段转换失败时 */
        void run() throws ApiException;
    }

    /** Exact immutable helper protocol identity. / 精确不可变 helper 协议身份。
     * @param protocol observed protocol version / 已观察协议版本
     * @param sha256 observed helper byte digest / 已观察 helper 字节摘要 */
    public record Identity(int protocol, String sha256) {
        /** Checks a complete immutable identity. / 校验完整不可变身份。
         * @param protocol observed protocol version / 已观察协议版本
         * @param sha256 observed helper byte digest / 已观察 helper 字节摘要 */
        public Identity {
            if (protocol != DeploymentManagedHelperBundle.PROTOCOL_VERSION
                    || !DeploymentManagedHelperBundle.EXPECTED_SHA256.equals(sha256))
                throw new IllegalArgumentException("Helper identity differs from the packaged contract");
        }
    }

    /** Borrowed session; the caller retains and closes its original connection. / 借用会话；调用方保留并关闭原连接。 */
    private final RemoteSession session;

    /** Host-owned exact-effect review capability. / 宿主持有的精确副作用审核能力。 */
    private final InteractionService interaction;

    /** Original admitted server, never normalized. / 原准入服务器，不作规范化。 */
    private final ServerRef server;

    /** Original accepted task. / 原已接纳任务。 */
    private final TaskContext task;

    /** Reviewed business revision used by both exact actions. / 两项精确动作共用的已审阅业务修订。 */
    private final String revision;

    /** Caller-owned check immediately before remote dispatch. / 远端发送紧前调用方持有的核对。 */
    private final DeploymentSendGuard beforeSend;

    /** Binds one original task and its borrowed session. / 绑定一项原任务及其借用会话。
     * @param session original authenticated session / 原已认证会话
     * @param interaction host review capability / 宿主审核能力
     * @param server original admitted server / 原准入服务器
     * @param task original accepted task / 原已接纳任务
     * @param revision reviewed business revision / 已审阅业务修订 */
    public DeploymentManagedHelper(RemoteSession session, InteractionService interaction, ServerRef server,
            TaskContext task, String revision) {
        this(session, interaction, server, task, revision, DeploymentSendGuard.NONE);
    }

    /** Binds an additional caller-owned pre-dispatch check. / 绑定调用方附加的发送前核对。
     * @param session original authenticated session / 原已认证会话
     * @param interaction host review capability / 宿主审核能力
     * @param server original admitted server / 原准入服务器
     * @param task original accepted task / 原任务
     * @param revision reviewed business revision / 已审阅业务修订
     * @param beforeSend caller-owned check / 调用方持有的核对 */
    public DeploymentManagedHelper(RemoteSession session, InteractionService interaction, ServerRef server,
            TaskContext task, String revision, DeploymentSendGuard beforeSend) {
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        this.beforeSend = Objects.requireNonNull(beforeSend);
        if (revision.isBlank() || revision.length() > 512 || revision.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid reviewed revision");
    }

    /** Installs the complete packaged helper and confirms its exact bytes and protocol without replaying uncertain effects. / 安装完整随附 helper，并在不重放未知副作用的前提下核实精确字节和协议。
     * @return confirmed helper identity or retained uncertainty / 已确认 helper 身份或保留的不确定性
     * @throws ApiException if review fails before dispatch / 发送前审核失败时 */
    public OperationResult<Identity> install() throws ApiException {
        byte[] body = DeploymentHelperInstallerScript.render().getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", body, Duration.ofHours(2),
                131072)) {
            java.util.Arrays.fill(body, (byte) 0);
            var grant = interaction.approve(intent("deployment.helper.install.v1", request), task);
            if (grant.outcome() != Outcome.SUCCEEDED)
                return withoutValue(grant);
            final OperationResult<CommandResult> response;
            beforeSend.verify();
            try {
                response = session.execute(request, grant.value().orElseThrow());
            } catch (ApiException uncertain) {
                if (uncertain.problem().code() == ProblemCode.ACCESS_DENIED
                        || uncertain.problem().code() == ProblemCode.INVALID_INPUT)
                    throw uncertain;
                return unknown("DEPLOYMENT_HELPER_INSTALL_UNCONFIRMED");
            }
            if (response.outcome() == Outcome.CANCELLED)
                return withoutValue(response);
            if (!complete(response, installTail(), false))
                return unknown("DEPLOYMENT_HELPER_INSTALL_UNCONFIRMED");
            try {
                var observed = probe();
                return observed.outcome() == Outcome.SUCCEEDED
                        ? observed
                        : unknown("DEPLOYMENT_HELPER_POSTINSTALL_UNCONFIRMED");
            } catch (ApiException uncertain) {
                return unknown("DEPLOYMENT_HELPER_POSTINSTALL_UNCONFIRMED");
            }
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    /** Sends a caller-owned operation-specific installer after exact approval. / 精确审核后发送调用方持有的操作专属安装程序。
     * @param script complete caller-owned installer bytes / 调用方持有的完整安装字节
     * @param beforeExecute original durable dispatch transition / 原持久发送阶段转换
     * @return original platform command observation / 原平台命令观察
     * @throws ApiException if review, transition or transport fails / 审核、阶段转换或传输失败时 */
    public OperationResult<CommandResult> installTracked(byte[] script, BeforeExecute beforeExecute)
            throws ApiException {
        Objects.requireNonNull(script);
        Objects.requireNonNull(beforeExecute);
        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", script, Duration.ofHours(2),
                131072)) {
            var grant = interaction.approve(intent("deployment.helper.install.tracked.v1", request), task);
            if (grant.outcome() != Outcome.SUCCEEDED)
                return new OperationResult<>(grant.outcome(), Optional.empty(), grant.problem());
            if (task.cancellation().requested())
                return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                        Optional.of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_HELPER_INSTALL_UNSENT")));
            beforeExecute.run();
            beforeSend.verify();
            return session.execute(request, grant.value().orElseThrow());
        }
    }

    /** Reads an original root-owned installer receipt without running the installed helper. / 不运行已安装 helper 而读取原 root 回执。
     * @param operationId original installer UUID / 原安装 UUID
     * @return bounded original status observation / 有界原状态观察
     * @throws ApiException if review or transport fails / 审核或传输失败时 */
    public OperationResult<CommandResult> installationStatus(UUID operationId) throws ApiException {
        byte[] script = DeploymentHelperInstallerScript.renderStatus(operationId).getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", script, Duration.ofSeconds(30),
                2048)) {
            java.util.Arrays.fill(script, (byte) 0);
            var grant = interaction.approve(intent("deployment.helper.install.status.v1", request), task);
            if (grant.outcome() != Outcome.SUCCEEDED)
                return new OperationResult<>(grant.outcome(), Optional.empty(), grant.problem());
            beforeSend.verify();
            return session.execute(request, grant.value().orElseThrow());
        } finally {
            java.util.Arrays.fill(script, (byte) 0);
        }
    }

    /** Reads the installed helper's exact protocol and SHA without changing its contents. / 仅读取已安装 helper 的精确协议和 SHA，不修改其内容。
     * @return verified helper identity or an explicit mismatch / 经核实的 helper 身份或明确不匹配
     * @throws ApiException if review or original remote access fails / 审核或原远端访问失败时 */
    public OperationResult<Identity> probe() throws ApiException {
        try (var request = new CommandRequest(probeScript(), new byte[0], Duration.ofSeconds(30), 4096)) {
            var grant = interaction.approve(intent("deployment.helper.probe.v1", request), task);
            if (grant.outcome() != Outcome.SUCCEEDED)
                return withoutValue(grant);
            beforeSend.verify();
            var response = session.execute(request, grant.value().orElseThrow());
            if (response.outcome() == Outcome.CANCELLED || response.outcome() == Outcome.UNKNOWN)
                return withoutValue(response);
            if (complete(response, probeOutput(), true))
                return new OperationResult<>(Outcome.SUCCEEDED,
                        Optional.of(new Identity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
                                DeploymentManagedHelperBundle.EXPECTED_SHA256)),
                        Optional.empty());
            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                    Optional.of(problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HELPER_IDENTITY")));
        }
    }

    /** Produces a stable, exact platform review for one fixed helper operation. / 为固定 helper 操作生成稳定且精确的平台审核。
     * @param operation versioned operation identity / 带版本的操作身份
     * @param request exact command and input digest / 精确命令及输入摘要
     * @return classified exact review intent / 已分类的精确审核意图 */
    private gold.debug.windowstolinux.appapi.task.ActionIntent intent(String operation, CommandRequest request) {
        return RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(operation,
                Set.of(new ActionTarget(server.id(), "deployment-helper", DeploymentManagedHelperBundle.PATH))));
    }

    /** Requires a known exit, untruncated clean output and an exact terminal protocol. / 要求已知退出、未截断的干净输出及精确末尾协议。
     * @param response host-observed command result / 宿主观测命令结果
     * @param expected expected terminal output / 预期末尾输出
     * @param exact whether the whole output must match / 是否要求全部输出匹配
     * @return whether every success fact was observed / 是否已观察到全部成功事实 */
    private static boolean complete(OperationResult<CommandResult> response, String expected, boolean exact) {
        if (response.outcome() != Outcome.SUCCEEDED || response.value().isEmpty())
            return false;
        CommandResult value = response.value().orElseThrow();
        return value.exitCode().equals(Optional.of(0)) && !value.truncated() && (!exact || value.stderr().isEmpty())
                && (exact ? value.stdout().equals(expected) : value.stdout().endsWith(expected));
    }

    /** Converts a rejected review or uncertain command without inventing a payload. / 转换被拒审核或未知命令，不虚构载荷。
     * @param result original public result / 原公开结果
     * @return same outcome and problem without a value / 相同结果与问题但不带值 */
    private static OperationResult<Identity> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports the effect as unconfirmed after a possible installation. / 可能安装后将副作用报告为未确认。
     * @param event stable event identity / 稳定事件身份
     * @return retained uncertainty / 保留的不确定性 */
    private static OperationResult<Identity> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Builds a non-secret diagnostic. / 构造不含秘密的诊断。
     * @param code stable category / 稳定类别
     * @param event stable event identity / 稳定事件身份
     * @return bounded public problem / 有界公开问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Managed helper evidence did not match the packaged contract", Map.of(),
                Optional.empty());
    }

    /** Returns the fixed install receipt suffix. / 返回固定安装回执尾部。
     * @return exact receipt suffix / 精确回执尾部 */
    private static String installTail() {
        return "HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
    }

    /** Returns the exact read-only probe output. / 返回精确只读探测输出。
     * @return expected probe bytes as text / 文本形式的预期探测字节 */
    private static String probeOutput() {
        return "HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
    }

    /** Renders a fixed read-only probe with a shell-verified byte digest. / 渲染以 shell 核验字节摘要的固定只读探测。
     * @return exact Bash script / 精确 Bash 脚本 */
    private static String probeScript() {
        return "set -euo pipefail\n" + "printf '%s  %s\\n' '" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "' '"
                + DeploymentManagedHelperBundle.PATH + "' | /usr/bin/sha256sum --check --status\n" + "'"
                + DeploymentManagedHelperBundle.PATH + "' probe\n" + "printf 'SHA256="
                + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n";
    }
}
