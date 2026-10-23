package gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainSelectionPolicy;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;

/** Prepares at most one reviewed catalog branch at a time and seals its exact installed binding. / 每次至多准备一个已审阅目录分支，并封存精确安装绑定。 */
public final class DeploymentToolchainSetup {
    /** Task-owned preparation and binding effects. / 任务持有的准备与绑定副作用端口。 */
    public interface Commands {
        /** Prepares one exact catalog branch. / 准备一个精确目录分支。 */
        OperationResult<CommandResult> prepare(DeploymentToolchainType ecosystem, String branch, String exact,
                                               int seconds, String javaHome) throws ApiException;

        /** Confirms the original immutable binding. / 确认原不可变绑定。 */
        OperationResult<CommandResult> bind(String encoded) throws ApiException;
    }

    /** Physical protocol adapters return data without manufacturing legacy command output. */
    public interface VerifiedCommands extends Commands {
        OperationResult<VerifiedSelection> prepareVerified(DeploymentToolchainType ecosystem, String branch, String exact,
                                                           int seconds, String javaHome) throws ApiException;
        OperationResult<String> bindVerified(String encoded) throws ApiException;
    }

    public record VerifiedSelection(DeploymentToolchainType ecosystem, String version, String directory,
                                    DeploymentResolvedToolchains.OriginType origin, String source, String sha256) { }

    /** Original helper command channel. / 原 helper 命令通道。 */
    private final Commands commands;

    /** Fixed catalog used by planning and verification. / 规划与核验共用的固定目录。 */
    private final DeploymentToolchainCatalog catalog;

    @FunctionalInterface
    private interface PreparedObserver {
        void record(DeploymentResolvedToolchains tools) throws ApiException;
    }

    private final PreparedObserver preparedObserver;

    private final DeploymentBuildCommandChannel trackedCommands;

    private final VerifiedCommands verifiedCommands;

    /** Binds a single original remote task and catalog. / 绑定单一原远端任务与目录。
     * @param commands original command channel / 原命令通道
     * @param catalog reviewed supported releases / 已审阅支持发布 */
    public DeploymentToolchainSetup(DeploymentBuildCommandChannel commands, DeploymentToolchainCatalog catalog) {
        this(new Commands() {
            @Override
            public OperationResult<CommandResult> prepare(DeploymentToolchainType ecosystem, String branch,
                                                          String exact, int seconds, String javaHome) throws ApiException {
                return commands.prepare(ecosystem, branch, exact, seconds, javaHome);
            }

            @Override
            public OperationResult<CommandResult> bind(String encoded) throws ApiException {
                return commands.bind(encoded);
            }
        }, catalog, commands::toolchainsPrepared, commands.trackedToolchains() ? commands : null);
        Objects.requireNonNull(commands);
    }

    /** Selects effects without changing catalog or receipt validation. / 选择副作用实现，不改变目录或回执校验。 */
    public DeploymentToolchainSetup(Commands commands, DeploymentToolchainCatalog catalog) {
        this(commands, catalog, tools -> {
        }, null);
    }

    private DeploymentToolchainSetup(Commands commands, DeploymentToolchainCatalog catalog, PreparedObserver preparedObserver,
                                     DeploymentBuildCommandChannel trackedCommands) {
        this.commands = Objects.requireNonNull(commands);
        this.catalog = Objects.requireNonNull(catalog);
        this.preparedObserver = Objects.requireNonNull(preparedObserver);
        this.trackedCommands = trackedCommands;
        this.verifiedCommands = commands instanceof VerifiedCommands verified ? verified : null;
    }

    /** Prepares one of at most two admitted branches and confirms its immutable binding. / 准备最多两个准入分支之一，并确认其不可变绑定。
     * @param requirements exact original requirements / 精确原需求
     * @param attempt zero or one catalog candidate / 第零或第一目录候选
     * @param seconds remaining cumulative remote execution budget / 剩余累计远端执行预算秒数
     * @return confirmed installed tools or a known/uncertain outcome / 已确认安装工具或已知及未知结果
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<DeploymentResolvedToolchains> prepare(List<ToolchainRequirement> requirements, int attempt,
                                                                 int seconds) throws ApiException {
        if (attempt < 0 || attempt > 1)
            throw new IllegalArgumentException("Only two catalog candidates are allowed");
        var selections = new ArrayList<DeploymentResolvedToolchains.Selection>();
        for (ToolchainRequirement requirement : List.copyOf(requirements)) {
            var candidates = catalog.candidates(requirement);
            if (candidates.isEmpty())
                return failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TOOLCHAIN_UNSUPPORTED");
            var branch = candidates.get(Math.min(attempt, candidates.size() - 1));
            if (branch.installation() == DeploymentToolchainCatalog.InstallationType.SYSTEM_COMPILER)
                continue;
            String exact = requirement.constraint() == ToolchainRequirement.ConstraintType.EXACT
                && requirement.version().isPresent() && !requirement.version().orElseThrow().preview()
                && requirement.version().orElseThrow().branch().equals(branch.version())
                ? requirement.version().orElseThrow().text()
                : "-";
            String javaHome = selections.stream().filter(s -> s.version().ecosystem() == DeploymentToolchainType.JAVA)
                .map(DeploymentResolvedToolchains.Selection::directory).findFirst().orElse("-");
            String[] fields;
            if (trackedCommands != null) {
                var observed = trackedCommands.prepareTracked(requirement.ecosystem(), branch.version(), exact,
                    Math.max(30, Math.min(7200, seconds)), javaHome);
                if (observed.outcome() != TaskResult.SUCCEEDED)
                    return observed.outcome() == TaskResult.FAILED && observed.value().isPresent()
                        && "unavailable".equals(observed.value().orElseThrow().failureCategory())
                        ? failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TOOLCHAIN_UNSUPPORTED") : withoutValue(observed);
                if (observed.value().isEmpty() || !observed.value().orElseThrow().prepared()
                    || observed.value().orElseThrow().cleanupState() == gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.CleanupState.REQUIRED)
                    return unknown("DEPLOYMENT_TOOLCHAIN_RECEIPT_UNCONFIRMED");
                var selection = observed.value().orElseThrow().selection();
                fields = new String[]{selection.ecosystem().name(), selection.version(), selection.directory(),
                    selection.origin().name(), selection.source(), selection.sha256()};
            } else if (verifiedCommands != null) {
                var observed = verifiedCommands.prepareVerified(requirement.ecosystem(), branch.version(), exact,
                    Math.max(30, Math.min(7200, seconds)), javaHome);
                if (observed.outcome() != TaskResult.SUCCEEDED) return withoutValue(observed);
                if (observed.value().isEmpty()) return unknown("DEPLOYMENT_TOOLCHAIN_RECEIPT_UNCONFIRMED");
                var selection = observed.value().orElseThrow();
                fields = new String[]{selection.ecosystem().name(), selection.version(), selection.directory(),
                    selection.origin().name(), selection.source(), selection.sha256()};
            } else {
            var observed = commands.prepare(requirement.ecosystem(), branch.version(), exact,
                Math.max(30, Math.min(7200, seconds)), javaHome);
            if (observed.outcome() == TaskResult.UNKNOWN || observed.outcome() == TaskResult.CANCELLED)
                return withoutValue(observed);
            if (observed.outcome() == TaskResult.FAILED && observed.value().isEmpty())
                return withoutValue(observed);
            if (!complete(observed))
                return observed.outcome() == TaskResult.FAILED && knownExit(observed)
                    ? observed.value().orElseThrow().stdout().startsWith("TOOLCHAIN_FAILURE=unavailable:")
                    ? preparationFailure(observed, ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TOOLCHAIN_UNSUPPORTED")
                    : preparationFailure(observed, ProblemCode.IO_FAILURE, "DEPLOYMENT_TOOLCHAIN_PREPARE_FAILED")
                    : unknown("DEPLOYMENT_TOOLCHAIN_PREPARE_UNCONFIRMED");
            String stdout = observed.value().orElseThrow().stdout();
            if (!stdout.startsWith("TOOLCHAIN=") || !stdout.endsWith("\n")
                || stdout.indexOf('\n') != stdout.length() - 1)
                return unknown("DEPLOYMENT_TOOLCHAIN_RECEIPT_UNCONFIRMED");
            fields = stdout.substring(10, stdout.length() - 1).split("\\|", -1);
            }
            if (fields.length != 6 || !fields[0].equals(requirement.ecosystem().name()))
                return unknown("DEPLOYMENT_TOOLCHAIN_RECEIPT_UNCONFIRMED");
            try {
                var version = DeploymentToolchainVersion.parse(requirement.ecosystem(), fields[1]).orElseThrow();
                if (!catalog.permits(version) || !version.branch().equals(branch.version()))
                    return unknown("DEPLOYMENT_TOOLCHAIN_IDENTITY_UNCONFIRMED");
                if (requirement.constraint() == ToolchainRequirement.ConstraintType.MINIMUM
                    && version.compareTo(requirement.version().orElseThrow()) < 0)
                    return attempt == 0 && candidates.size() > 1
                        ? failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TOOLCHAIN_MINIMUM")
                        : failure(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_TOOLCHAIN_UNSUPPORTED");
                new DeploymentToolchainSelectionPolicy(catalog).resolve(requirement, branch, List.of(version));
                selections.add(new DeploymentResolvedToolchains.Selection(requirement, version, fields[2],
                    DeploymentResolvedToolchains.OriginType.valueOf(fields[3]), fields[4], fields[5]));
            } catch (IllegalArgumentException | java.util.NoSuchElementException invalid) {
                return unknown("DEPLOYMENT_TOOLCHAIN_IDENTITY_UNCONFIRMED");
            }
        }
        var set = new DeploymentResolvedToolchains(catalog.revision(), selections);
        if (!selections.isEmpty()) {
            preparedObserver.record(set);
            String encoded = Base64.getEncoder()
                .encodeToString(DeploymentToolchainBindingCodec.encode(set).getBytes(StandardCharsets.UTF_8));
            if (trackedCommands != null) {
                var bound = trackedCommands.bindTracked(encoded);
                if (bound.outcome() != TaskResult.SUCCEEDED) return withoutValue(bound);
                if (bound.value().isEmpty() || !bound.value().orElseThrow().bound()
                    || bound.value().orElseThrow().cleanupState() == gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.CleanupState.REQUIRED
                    || !DeploymentToolchainBindingCodec.identity(set).equals(bound.value().orElseThrow().bindingSha256()))
                    return unknown("DEPLOYMENT_TOOLCHAIN_BINDING_UNCONFIRMED");
            } else if (verifiedCommands != null) {
                var bound = verifiedCommands.bindVerified(encoded);
                if (bound.outcome() != TaskResult.SUCCEEDED) return withoutValue(bound);
                if (bound.value().isEmpty() || !DeploymentToolchainBindingCodec.identity(set).equals(bound.value().orElseThrow()))
                    return unknown("DEPLOYMENT_TOOLCHAIN_BINDING_UNCONFIRMED");
            } else {
            var bound = commands.bind(encoded);
            if (bound.outcome() == TaskResult.UNKNOWN || bound.outcome() == TaskResult.CANCELLED)
                return withoutValue(bound);
            if (bound.outcome() == TaskResult.FAILED && bound.value().isEmpty())
                return withoutValue(bound);
            if (!complete(bound))
                return bound.outcome() == TaskResult.FAILED && knownExit(bound)
                    ? failure(ProblemCode.IO_FAILURE, "DEPLOYMENT_TOOLCHAIN_BIND_FAILED")
                    : unknown("DEPLOYMENT_TOOLCHAIN_BIND_UNCONFIRMED");
            if (!bound.value().orElseThrow().stdout()
                .equals("TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(set) + "\n"))
                return unknown("DEPLOYMENT_TOOLCHAIN_BINDING_UNCONFIRMED");
            }
        }
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(set), Optional.empty());
    }

    /** Requires a complete, confirmed successful helper command. / 要求完整且已确认成功的 helper 命令。
     * @param result actual host observation / 实际宿主观测
     * @return true only for untruncated exit zero / 仅未截断且退出零时为真 */
    private static boolean complete(OperationResult<CommandResult> result) {
        return result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
            && result.value().orElseThrow().exitCode().equals(Optional.of(0))
            && !result.value().orElseThrow().truncated();
    }

    /** Distinguishes an observed nonzero helper exit from lost confirmation. / 区分已观察非零 helper 退出与确认丢失。
     * @param result host observation / 宿主观测
     * @return whether an untruncated nonzero exit is known / 是否已知未截断非零退出 */
    private static boolean knownExit(OperationResult<CommandResult> result) {
        return result.value().isPresent() && !result.value().orElseThrow().truncated()
            && result.value().orElseThrow().exitCode().isPresent()
            && result.value().orElseThrow().exitCode().orElseThrow() != 0;
    }

    /** Removes an absent tool payload without changing outcome or problem. / 移除不存在的工具载荷而不改变结果或问题。
     * @param result original host outcome / 原宿主结果
     * @return same outcome without tools / 无工具载荷的同一结果 */
    private static OperationResult<DeploymentResolvedToolchains> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports a known unsupported or failed helper operation. / 报告已知不支持或失败的 helper 操作。
     * @param code public category / 公开分类
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static OperationResult<DeploymentResolvedToolchains> failure(ProblemCode code, String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    private static OperationResult<DeploymentResolvedToolchains> preparationFailure(OperationResult<?> original, ProblemCode code, String event) {
        var problem = problem(code, event);
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(problem.code(), problem.eventCode(),
            problem.message(), original.problem().map(Problem::context).orElse(Map.of()), original.problem().flatMap(Problem::diagnosticId))));
    }

    /** Retains uncertain installation or binding effects. / 保留未知的安装或绑定副作用。
     * @param event stable event / 稳定事件
     * @return uncertain outcome / 未知结果 */
    private static OperationResult<DeploymentResolvedToolchains> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Builds a bounded non-secret failure. / 构造有界且不含秘密的失败。
     * @param code category / 分类
     * @param event stable event / 稳定事件
     * @return public problem / 公开问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Managed toolchain could not be confirmed", Map.of(), Optional.empty());
    }
}
