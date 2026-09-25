package gold.debug.windowstolinux.shared.standard.deploy.execution.environment;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort;
import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.*;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseRequirement;
import gold.debug.windowstolinux.shared.model.ecosystem.db.sql.DatabaseVersionRequirement;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Resolves an installed instance before considering installation, with operation-bound replacement decisions. / 先解析已安装实例，再考虑安装，并将替换决策绑定本次操作。
 */
final class DatabaseInstanceResolver {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DatabaseInstanceResolver() {
    }

    /**
     * Selects a compatible database instance or performs an explicitly approved installation or replacement through the native database port.
     * <p>选择兼容数据库实例，或通过原生数据库端口执行显式批准的安装或替换。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param requirement requirement / 要求
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param progress progress / 进度
     * @return a compatible database instance or performs an explicitly approved installation or replacement through the native database port / 兼容数据库实例，或通过原生数据库端口执行显式批准的安装或替换
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    static Instance resolve(NativeDatabasePort port, String serverId, DatabaseRequirement requirement,
            AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) throws Exception {
        var version = new DatabaseVersionRequirement(requirement.version());
        while (true) {
            progress.accept(LocalizedMessage.of("db.inspect", "database", requirement.engine().name()));
            Inventory inventory = port.inspectDatabase(requirement.engine());
            if (!inventory.conflicts().isEmpty()) {
                if (!interaction.confirm("db.conflict", Map.of("conflicts", inventory.conflicts())))
                    throw new CancellationException();
                continue;
            }
            if (inventory.instances().isEmpty()) {
                if (inventory.candidate().isEmpty()
                        || !version.accepts(inventory.candidate().orElseThrow().engineVersion())) {
                    if (!interaction.confirm("db.versionUnavailable", Map.of("required", requirement.version())))
                        throw new CancellationException();
                    continue;
                }
                progress.accept(LocalizedMessage.of("db.install", "database", requirement.engine().name()));
                inventory = port.installDatabase(requirement.engine(), inventory.candidate().orElseThrow(),
                        Optional.empty());
                if (!inventory.conflicts().isEmpty() || inventory.instances().isEmpty())
                    throw new NativeDatabaseException(NativeDatabaseFailureType.STATE_CHANGED);
            }
            Instance selected = choose(inventory.instances(), interaction);
            if (!version.accepts(selected.version())) {
                if (inventory.instances().size() > 1) {
                    if (!interaction.confirm("db.multipleReplacement",
                            Map.of("instance", selected.id(), "required", requirement.version())))
                        throw new CancellationException();
                    continue;
                }
                if (inventory.candidate().isEmpty()
                        || !version.accepts(inventory.candidate().orElseThrow().engineVersion())) {
                    if (!interaction.confirm("db.versionUnavailable", Map.of("required", requirement.version())))
                        throw new CancellationException();
                    continue;
                }
                PackageCandidate candidate = inventory.candidate().orElseThrow();
                Map<String, String> details = Map.of("server", serverId, "instance", selected.id(), "current",
                        selected.version(), "required", requirement.version(), "target", candidate.engineVersion(),
                        "data", selected.dataDirectory());
                if (!interaction.confirmDatabaseReplacement(details))
                    throw new CancellationException();
                Replacement approval = new Replacement(serverId, selected, candidate, UUID.randomUUID(), true, true);
                progress.accept(LocalizedMessage.of("db.replacing", "database", requirement.engine().name()));
                try {
                    port.installDatabase(requirement.engine(), candidate, Optional.of(approval));
                } catch (NativeDatabaseException changed) {
                    if (changed.reason() == NativeDatabaseFailureType.STATE_CHANGED)
                        continue;
                    throw changed;
                }
                selected = awaitRestoration(port, requirement, interaction, progress);
            }
            try {
                selected = port.startDatabase(selected);
            } catch (NativeDatabaseException failure) {
                if (failure.reason() == NativeDatabaseFailureType.MANUAL_RESTORE_REQUIRED)
                    selected = awaitRestoration(port, requirement, interaction, progress);
                else if (failure.reason() == NativeDatabaseFailureType.STATE_CHANGED)
                    continue;
                else
                    throw failure;
            }
            if (!version.accepts(selected.version()))
                throw new NativeDatabaseException(NativeDatabaseFailureType.STATE_CHANGED);
            progress.accept(LocalizedMessage.of("db.reused", "database",
                    requirement.engine().name() + " " + selected.version()));
            return selected;
        }
    }

    /**
     * Waits for restoration.
     * <p>等待恢复。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param requirement requirement / 要求
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param progress progress / 进度
     * @return constructed or resolved instance / 构造或解析得到的实例
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private static Instance awaitRestoration(NativeDatabasePort port, DatabaseRequirement requirement,
            AutomaticDeploymentInteraction interaction, Consumer<LocalizedMessage> progress) throws Exception {
        char[] admin = new char[0];
        try {
            while (true) {
                progress.accept(LocalizedMessage.of("db.waitingRestore", "database", requirement.engine().name()));
                if (!interaction.confirm("db.restoreConfirmed", Map.of("database", requirement.engine().name())))
                    throw new NativeDatabaseException(NativeDatabaseFailureType.MANUAL_RESTORE_REQUIRED);
                Inventory refreshed = port.inspectDatabase(requirement.engine());
                if (!refreshed.conflicts().isEmpty() || refreshed.instances().isEmpty())
                    continue;
                Instance selected = choose(refreshed.instances(), interaction);
                try {
                    return port.confirmDatabaseRestored(selected, admin);
                } catch (NativeDatabaseException failure) {
                    if (failure.reason() == NativeDatabaseFailureType.AUTH_REQUIRED) {
                        Arrays.fill(admin, '\0');
                        admin = interaction.requestSecret("db.adminPassword");
                    } else if (failure.reason() != NativeDatabaseFailureType.STATE_CHANGED
                            && failure.reason() != NativeDatabaseFailureType.MANUAL_RESTORE_REQUIRED)
                        throw failure;
                }
            }
        } finally {
            Arrays.fill(admin, '\0');
        }
    }

    /**
     * Chooses instance.
     * <p>选择实例。
     *
     * @param instances instances / 实例集合
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved instance / 构造或解析得到的实例
     */
    private static Instance choose(List<Instance> instances, AutomaticDeploymentInteraction interaction) {
        if (instances.size() == 1)
            return instances.getFirst();
        List<String> options = instances.stream()
                .map(instance -> instance.id() + " | " + instance.version() + " | " + instance.port()).toList();
        DeploymentInputField field = new DeploymentInputField("db/instance", "db.field.instance", "db.help.instance",
                "", options);
        String choice = NativeDatabasePreparationService.ask(List.of(field), interaction).get(field.id());
        return instances.get(options.indexOf(choice));
    }
}
