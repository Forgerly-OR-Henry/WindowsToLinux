package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
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
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;

/** Runs the fixed helper installation and probe through one borrowed, host-authorized remote session. / 通过借用的宿主授权远端会话执行固定 helper 安装与探测。 */
public final class DeploymentManagedHelper {
    private final gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout;
    /** Actual original-mode approval for the complete helper request. / 完整 helper 请求的实际原模式审核。 */
    @FunctionalInterface
    public interface CommandReview {
        /** Reviews actual script and complete input before original dispatch. / 原发送前审核实际脚本及完整输入。
         * @param intent original helper semantics and revision / 原 helper 语义及修订
         * @param request complete actual request / 完整实际请求
         * @param task original authentic task / 原真实任务
         * @return actual host grant after required reviews / 所需审核后的实际宿主授权
         * @throws ApiException if original approval fails / 原审核失败时 */
        OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant> approve(
            gold.debug.windowstolinux.appapi.task.action.ActionIntent intent, CommandRequest request, TaskContext task)
            throws ApiException;
    }

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
    public record Identity(int protocol, String sha256, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        public Identity(int protocol, String sha256) {
            this(protocol, sha256, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
        }
        /** Checks a complete immutable identity. / 校验完整不可变身份。
         * @param protocol observed protocol version / 已观察协议版本
         * @param sha256 observed helper byte digest / 已观察 helper 字节摘要 */
        public Identity {
            if (protocol != DeploymentManagedHelperBundle.PROTOCOL_VERSION
                || !DeploymentManagedHelperBundle.helperSha256(layout).equals(sha256))
                throw new IllegalArgumentException("Helper identity differs from the packaged contract");
        }
    }

    /** Borrowed session; the caller retains and closes its original connection. / 借用会话；调用方保留并关闭原连接。 */
    private final RemoteSession session;

    /** Host-owned exact-effect review capability. / 宿主持有的精确副作用审核能力。 */
    private final InteractionService interaction;

    /** Original admitted server, never normalized. / 原准入服务器，不作规范化。 */
    private final ServerId server;

    /** Original accepted task. / 原已接纳任务。 */
    private final TaskContext task;

    /** Reviewed business revision used by both exact actions. / 两项精确动作共用的已审阅业务修订。 */
    private final String revision;

    /** Caller-owned check immediately before remote dispatch. / 远端发送紧前调用方持有的核对。 */
    private final DeploymentSendGuard beforeSend;

    /** Optional actual-mode reviewer; null preserves the existing host path. / 可选实际模式审核器，null 保留既有宿主路径。 */
    private final CommandReview commandReview;

    /** Binds one original task and its borrowed session. / 绑定一项原任务及其借用会话。
     * @param session original authenticated session / 原已认证会话
     * @param interaction host review capability / 宿主审核能力
     * @param server original admitted server / 原准入服务器
     * @param task original accepted task / 原已接纳任务
     * @param revision reviewed business revision / 已审阅业务修订 */
    public DeploymentManagedHelper(RemoteSession session, InteractionService interaction, ServerId server,
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
    public DeploymentManagedHelper(RemoteSession session, InteractionService interaction, ServerId server,
                                   TaskContext task, String revision, DeploymentSendGuard beforeSend) {
        this(session, interaction, server, task, revision, beforeSend, null);
    }

    /** Binds an exact request reviewer without changing existing default approval semantics. / 绑定精确请求审核器，不改变既有默认审核语义。
     * @param session original authenticated session / 原认证会话
     * @param interaction original host review capability / 原宿主审核能力
     * @param server original server / 原服务器
     * @param task original task / 原任务
     * @param revision exact original revision / 精确原修订
     * @param beforeSend original pre-dispatch check / 原发送前核对
     * @param commandReview optional complete actual request reviewer / 可选完整实际请求审核器 */
    public DeploymentManagedHelper(RemoteSession session, InteractionService interaction, ServerId server,
                                   TaskContext task, String revision, DeploymentSendGuard beforeSend, CommandReview commandReview) {
        this(session, interaction, server, task, revision, beforeSend, commandReview,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original());
    }

    public DeploymentManagedHelper(RemoteSession session, InteractionService interaction, ServerId server,
                                   TaskContext task, String revision,
                                   gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        this(session, interaction, server, task, revision, DeploymentSendGuard.NONE, null, layout);
    }

    public DeploymentManagedHelper(RemoteSession session, InteractionService interaction, ServerId server,
                                   TaskContext task, String revision, DeploymentSendGuard beforeSend, CommandReview commandReview,
                                   gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.revision = Objects.requireNonNull(revision);
        this.beforeSend = Objects.requireNonNull(beforeSend);
        this.commandReview = commandReview;
        if (revision.isBlank() || revision.length() > 512 || revision.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid reviewed revision");
    }

    /** Installs the complete packaged helper and confirms its exact bytes and protocol without replaying uncertain effects. / 安装完整随附 helper，并在不重放未知副作用的前提下核实精确字节和协议。
     * @return confirmed helper identity or retained uncertainty / 已确认 helper 身份或保留的不确定性
     * @throws ApiException if review fails before dispatch / 发送前审核失败时 */
    public OperationResult<Identity> install() throws ApiException {
        return install(DeploymentHelperInstallerScript.render(layout), "deployment.helper.install.v1");
    }

    /** Installs missing fixed files or prerequisites through a fresh exact review. / 经新一次精确审阅补齐固定文件或依赖。
     * @return independently observed packaged identity or retained uncertainty / 独立观测的打包身份或保留的不确定性
     * @throws ApiException if the host review or dispatch guard rejects / 宿主审阅或发送守卫拒绝时 */
    public OperationResult<Identity> installFiles() throws ApiException {
        return install(DeploymentHelperInstallerScript.renderFiles(layout), "deployment.helper.install.files.v1");
    }

    /** Installs fixed files with a caller-owned complete prerequisite implementation, through exact independent review. */
    public OperationResult<Identity> installFilesWithPrerequisites(String prerequisitesScript) throws ApiException {
        return installFilesWithPrerequisites("exec /bin/bash --noprofile --norc -s", prerequisitesScript);
    }

    /** Installs caller-owned prerequisites with an exact caller-owned launcher and independent review. */
    public OperationResult<Identity> installFilesWithPrerequisites(String launcher, String prerequisitesScript) throws ApiException {
        return install(DeploymentHelperInstallerScript.renderFiles(prerequisitesScript, layout), "deployment.helper.install.files.custom.v1", launcher);
    }

    private OperationResult<Identity> install(String script, String operation) throws ApiException {
        return install(script, operation, "exec /bin/bash --noprofile --norc -s");
    }

    private OperationResult<Identity> install(String script, String operation, String launcher) throws ApiException {
        byte[] body = script.getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest(launcher, body, Duration.ofHours(2),
            131072)) {
            java.util.Arrays.fill(body, (byte) 0);
            var grant = approve(operation, request);
            if (grant.outcome() != TaskResult.SUCCEEDED)
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
            if (response.outcome() == TaskResult.CANCELLED)
                return withoutValue(response);
            if (!complete(response, installTail(), false))
                return unknown("DEPLOYMENT_HELPER_INSTALL_UNCONFIRMED");
            try {
                var observed = probePlatform();
                return observed.outcome() == TaskResult.SUCCEEDED
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
        UUID operationId = DeploymentHelperInstallerScript.trackedOperation(
            new String(script, StandardCharsets.UTF_8), task.id(), layout);
        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", script, Duration.ofHours(2),
            131072)) {
            var grant = approve("deployment.helper.install.tracked.v1", request,
                trackedInstallationTargets(server, layout, operationId));
            if (grant.outcome() != TaskResult.SUCCEEDED)
                return new OperationResult<>(grant.outcome(), Optional.empty(), grant.problem());
            if (task.cancelToken().requested())
                return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
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
        byte[] script = DeploymentHelperInstallerScript.renderStatus(operationId, layout).getBytes(StandardCharsets.UTF_8);
        try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", script, Duration.ofSeconds(30),
            2048)) {
            java.util.Arrays.fill(script, (byte) 0);
            var grant = approve("deployment.helper.install.status.v1", request,
                installationStatusTargets(server, layout, operationId));
            if (grant.outcome() != TaskResult.SUCCEEDED)
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
        return probe(false);
    }

    /** Checks base platform prerequisites before deployment preparation or after installation. / 部署准备前或安装后检查基础平台依赖。
     * @return confirmed identity and required client presence / 已核身份及所需客户端存在
     * @throws ApiException if original review or remote access fails / 原审核或远端访问失败时 */
    public OperationResult<Identity> probePlatform() throws ApiException {
        return probe(true);
    }

    /** Keeps pure identity/recovery probes separate from platform readiness. / 将纯身份及恢复探测与平台就绪检查分开。
     * @param requirePlatform whether deployment prerequisites are required / 是否要求部署依赖
     * @return original known observation / 原已知观测
     * @throws ApiException if original approval or access fails / 原审核或访问失败时 */
    private OperationResult<Identity> probe(boolean requirePlatform) throws ApiException {
        try (var request = new CommandRequest(probeScript(requirePlatform), new byte[0], Duration.ofSeconds(30), 4096)) {
            var grant = approve("deployment.helper.probe.v1", request);
            if (grant.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(grant);
            beforeSend.verify();
            var response = session.execute(request, grant.value().orElseThrow());
            if (response.outcome() == TaskResult.CANCELLED || response.outcome() == TaskResult.UNKNOWN)
                return withoutValue(response);
            if (complete(response, probeOutput(), true))
                return new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(new Identity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
                        DeploymentManagedHelperBundle.helperSha256(layout), layout)),
                    Optional.empty());
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                Optional.of(problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_HELPER_IDENTITY")));
        }
    }

    /** Uses the exact original default or required mode callback before any dispatch. / 任何发送前使用精确原默认或所需模式回调。
     * @param operation original helper action / 原 helper 动作
     * @param request complete actual command / 完整实际命令
     * @return actual single-use host grant / 实际单次宿主授权
     * @throws ApiException if original review fails / 原审阅失败时 */
    private OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant> approve(
        String operation, CommandRequest request) throws ApiException {
        return approve(operation, request, helperTargets(operation));
    }

    private OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant> approve(
        String operation, CommandRequest request, Set<ActionTarget> targets) throws ApiException {
        var intent = RemoteActions.command(server, revision, request)
            .withSemantics(new ActionSemantics(operation, targets));
        return commandReview == null ? DeploymentCommandReviewRouter.approve(interaction, intent, request, task) : commandReview.approve(intent, request, task);
    }

    /** Produces a stable, exact platform review for one fixed helper operation. / 为固定 helper 操作生成稳定且精确的平台审核。
     * @param operation versioned operation identity / 带版本的操作身份
     * @param request exact command and input digest / 精确命令及输入摘要
     * @return classified exact review intent / 已分类的精确审核意图 */
    private gold.debug.windowstolinux.appapi.task.action.ActionIntent intent(String operation, CommandRequest request) {
        return RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(operation,
            helperTargets(operation)));
    }

    /** Requires a known exit, untruncated clean output and an exact terminal protocol. / 要求已知退出、未截断的干净输出及精确末尾协议。
     * @param response host-observed command result / 宿主观测命令结果
     * @param expected expected terminal output / 预期末尾输出
     * @param exact whether the whole output must match / 是否要求全部输出匹配
     * @return whether every success fact was observed / 是否已观察到全部成功事实 */
    private Set<ActionTarget> helperTargets(String operation) {
        if (!operation.startsWith("deployment.helper.install") || operation.contains("status"))
            return Set.of(new ActionTarget(server.id(), "deployment-helper", layout.helperPath()));
        return installationTargets(server, layout);
    }

    public static Set<ActionTarget> installationTargets(ServerId server,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) {
        var targets = new java.util.HashSet<ActionTarget>();
        targets.add(new ActionTarget(server.id(), "deployment-helper", layout.helperPath()));
        targets.add(new ActionTarget(server.id(), "file", layout.buildEntry()));
        for (String root : java.util.List.of(layout.paths().helperDirectory(), layout.paths().applicationsRoot(),
                layout.paths().stateRoot(), layout.paths().configurationRoot(), layout.paths().dataRoot(),
                "/etc/systemd/system/multi-user.target.wants"))
            for (String directory : DeploymentRemoteLayoutAdmission.ancestors(root))
                targets.add(new ActionTarget(server.id(), "directory", directory));
        targets.add(new ActionTarget(server.id(), "file", DeploymentRemoteLayoutAdmission.UNIT_PATH));
        targets.add(new ActionTarget(server.id(), "file", "/etc/systemd/system/multi-user.target.wants/"
            + DeploymentRemoteLayoutAdmission.UNIT_NAME));
        targets.add(new ActionTarget(server.id(), "systemd-service", DeploymentRemoteLayoutAdmission.UNIT_NAME));
        targets.add(new ActionTarget(server.id(), "service-manager", "systemd"));
        for (String manager : java.util.List.of("apt-get", "dnf")) {
            targets.add(new ActionTarget(server.id(), "package-manager", manager));
            targets.add(new ActionTarget(server.id(), "system-package-index", manager + ":configured-sources"));
        }
        targets.add(new ActionTarget(server.id(), "package-set", "apt-get:" + DeploymentHelperInstallerScript.APT_PACKAGES));
        targets.add(new ActionTarget(server.id(), "package-set", "dnf:" + DeploymentHelperInstallerScript.DNF_PACKAGES));
        return Set.copyOf(targets);
    }

    /** Adds only the original receipt and its checked/createable root-owned directories. */
    public static Set<ActionTarget> trackedInstallationTargets(ServerId server,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout, UUID operationId) {
        var targets = new java.util.HashSet<>(installationTargets(server, layout));
        targets.addAll(installationStatusTargets(server, layout, operationId));
        return Set.copyOf(targets);
    }

    /** Declares every directory inspected and the one original receipt read by the status script. */
    public static Set<ActionTarget> installationStatusTargets(ServerId server,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout, UUID operationId) {
        java.util.Objects.requireNonNull(operationId);
        var targets = new java.util.HashSet<ActionTarget>();
        for (String directory : DeploymentRemoteLayoutAdmission.ancestors(layout.paths().stateRoot()))
            targets.add(new ActionTarget(server.id(), "directory", directory));
        targets.add(new ActionTarget(server.id(), "directory", layout.helperInstallOperationsRoot()));
        targets.add(new ActionTarget(server.id(), "file",
            layout.helperInstallOperationsRoot() + "/" + operationId + ".receipt"));
        return Set.copyOf(targets);
    }

    private static boolean complete(OperationResult<CommandResult> response, String expected, boolean exact) {
        if (response.outcome() != TaskResult.SUCCEEDED || response.value().isEmpty())
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
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
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
    private String installTail() {
        return "HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
            + DeploymentManagedHelperBundle.helperSha256(layout) + "\n";
    }

    /** Returns the exact read-only probe output. / 返回精确只读探测输出。
     * @return expected probe bytes as text / 文本形式的预期探测字节 */
    private String probeOutput() {
        return "HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
            + DeploymentManagedHelperBundle.helperSha256(layout) + "\n";
    }

    /** Renders a fixed read-only probe with a shell-verified byte digest. / 渲染以 shell 核验字节摘要的固定只读探测。
     * @param requirePlatform whether to check the SQLite client without executing it / 是否只检查SQLite客户端而不执行
     * @return exact Bash script / 精确 Bash 脚本 */
    private String probeScript(boolean requirePlatform) {
        return DeploymentManagedHelperBundle.verifiedCommand(
            (requirePlatform ? "if [ ! -f /usr/bin/sqlite3 ] || [ ! -x /usr/bin/sqlite3 ]; then exit 3; fi\n" : "")
                + "'"
                + layout.helperPath() + "' probe\n" + "printf 'SHA256="
                + DeploymentManagedHelperBundle.helperSha256(layout) + "\\n'\n", layout);
    }
}
