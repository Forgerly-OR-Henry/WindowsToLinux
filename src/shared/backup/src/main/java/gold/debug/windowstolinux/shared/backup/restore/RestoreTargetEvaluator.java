package gold.debug.windowstolinux.shared.backup.restore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.restore.RestoreTargetProfile;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;

/**
 * Converts live read-only target evidence into the strict portable restore profile. / 将实时只读目标证据转换为严格可移植恢复资料。
 */
public final class RestoreTargetEvaluator {
    /**
     * Combines observed host, runtime, storage and port facts into the target profile used by restore admission.
     * <p>将已观测主机、运行环境、存储及端口事实组合为恢复准入使用的目标资料。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param databasePresent database present / 数据库存在
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param linux linux / Linux 操作
     * @param activation activation / 激活
     * @param existingOwnedApplication existing owned application / 既有已持有应用
     * @return constructed or resolved restore target profile / 构造或解析得到的恢复目标配置资料
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    public RestoreTargetProfile evaluate(BackupManifest manifest, boolean databasePresent, String serverId,
            ServerCapabilityFacts server, LinuxCapabilityFacts linux,
            RemoteRestoreActivationPort.PreflightEvidence activation, boolean existingOwnedApplication)
            throws BackupException {
        requireHost(manifest, server, linux);
        Set<Integer> officialPorts = officialPorts(manifest, "tcp");
        Set<Integer> udpPorts = officialPorts(manifest, "udp");
        boolean portsAvailable = existingOwnedApplication
                || activation.occupiedTcpPorts().stream().noneMatch(officialPorts::contains)
                        && activation.occupiedUdpPorts().stream().noneMatch(udpPorts::contains);
        BackupDatabaseType databaseType = manifest.inventory().database().type();
        String databaseVersion = databasePresent ? manifest.inventory().database().engineVersion() : "none";
        boolean databaseCompatible = databaseType == BackupDatabaseType.NONE || databasePresent;
        List<String> evidence = new ArrayList<>(
                List.of("target managed-helper protocol and typed runtime capabilities were collected live",
                        existingOwnedApplication
                                ? "existing target application identity permits its reviewed formal ports"
                                : "reviewed formal ports were absent from live target listeners"));
        if (databasePresent)
            evidence.add(
                    "database artifact identity and engine evidence verified; credential-bound target inspection is candidate-scoped");
        evidence.addAll(activation.evidence());
        return new RestoreTargetProfile(serverId, linux.distro().name().toLowerCase(Locale.ROOT), linux.version(),
                linux.architecture(), manifest.inventory().runtime().runtimeKind(),
                "managed-helper-" + server.managedHelperProtocolVersion(), databaseType, databaseVersion,
                activation.availableBytes(), activation.managedRootWritable(), portsAvailable,
                activation.foreignApplicationConflict(), false, databaseCompatible, false,
                evidence.stream().distinct().toList());
    }

    /**
     * Requires reviewed server hostname or IP address.
     * <p>要求已审阅服务器主机名或 IP 地址。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param linux linux / Linux 操作
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static void requireHost(BackupManifest manifest, ServerCapabilityFacts server, LinuxCapabilityFacts linux)
            throws BackupException {
        if (server.managedHelperProtocolVersion() != ManagedHelperProtocolVersion.CURRENT
                || !server.nonInteractiveSudoAvailable() || !server.tarAvailable() || !server.systemdAvailable()
                || !linux.systemdAvailable()) {
            throw failed("target lacks the exact managed helper, tar, sudo, or systemd restore boundary");
        }
        Set<String> required = new HashSet<>(manifest.inventory().runtime().capabilities());
        if (required.contains("docker") && !linux.dockerOperational()
                || required.contains("podman") && !linux.podmanOperational()) {
            throw failed("target container runtime differs from the backup capability evidence");
        }
        for (var component : manifest.inventory().components()) {
            if (!runtimeReady(component.runtime().toSpecification(), server, linux)) {
                throw failed("target lacks the exact execution runtime for component " + component.componentId());
            }
        }
    }

    /**
     * Checks whether observed target capabilities satisfy the archived runtime's required tools and versions.
     * <p>检查已观测目标能力是否满足归档运行规格所需工具及版本。
     *
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param linux linux / Linux 操作
     * @return true when checks whether observed target capabilities satisfy the archived runtime's required tools and versions, false otherwise / 已观测目标能力是否满足归档运行规格所需工具及版本时为 true，否则为 false
     */
    private static boolean runtimeReady(DeploymentRuntimeSpecification runtime, ServerCapabilityFacts server,
            LinuxCapabilityFacts linux) {
        return switch (runtime) {
            case DeploymentRuntimeSpecification.ManagedProcess ignored -> false;
            case DeploymentRuntimeSpecification.SpringBoot ignored -> server.java21Available();
            case DeploymentRuntimeSpecification.JavaJar ignored -> server.java21Available();
            case DeploymentRuntimeSpecification.JavaSource ignored -> server.java21Available();
            case DeploymentRuntimeSpecification.KotlinService ignored -> server.java21Available();
            case DeploymentRuntimeSpecification.NodeService value ->
                linux.nodeMajorVersions().contains(value.nodeMajorVersion());
            case DeploymentRuntimeSpecification.PythonService value ->
                linux.pythonVersions().contains(value.pythonVersion());
            case DeploymentRuntimeSpecification.StaticSite ignored -> linux.python3Available();
            case DeploymentRuntimeSpecification.Container value ->
                value.engine() == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER
                        ? linux.dockerOperational()
                        : linux.podmanOperational();
            case DeploymentRuntimeSpecification.GoService ignored -> true;
            case DeploymentRuntimeSpecification.RustService ignored -> true;
            case DeploymentRuntimeSpecification.DotNetService value -> version(linux, runtime, value.version());
            case DeploymentRuntimeSpecification.PhpService value -> version(linux, runtime, value.version());
            case DeploymentRuntimeSpecification.RubyService value -> version(linux, runtime, value.version());
            case DeploymentRuntimeSpecification.CmakeService ignored -> true;
        };
    }

    /**
     * Tests the version predicate against the supplied evidence.
     * <p>根据所提供证据检查版本条件。
     *
     * @param linux linux / Linux 操作
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return true when version predicate against the supplied evidence, false otherwise / 根据所提供证据检查版本条件时为 true，否则为 false
     */
    private static boolean version(LinuxCapabilityFacts linux, DeploymentRuntimeSpecification runtime, String version) {
        return linux.serviceRuntimeVersions().getOrDefault(runtime.projectType(), Set.of()).contains(version);
    }

    /**
     * Collects the application's declared endpoint ports for the selected transport.
     * <p>采集应用针对所选传输协议声明的端点端口。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param transport transport / 传输
     * @return constructed or resolved set / 构造或解析得到的集合
     */
    private static Set<Integer> officialPorts(BackupManifest manifest, String transport) {
        Set<Integer> ports = new HashSet<>();
        manifest.inventory().components().forEach(component -> {
            var runtime = component.runtime().toSpecification();
            runtime.workload().endpoints().stream()
                    .filter(endpoint -> endpoint.protocol().transport().equals(transport))
                    .forEach(endpoint -> ports.add(endpoint.hostPort()));
            if ((runtime.healthCheck() instanceof HealthCheck.Udp ? "udp" : "tcp").equals(transport))
                runtime.healthCheck().portNumber().ifPresent(ports::add);
        });
        return Set.copyOf(ports);
    }

    /**
     * Builds the failure outcome while retaining available classified evidence.
     * <p>构建失败结果并保留可用的分类证据。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return the failure outcome while retaining available classified evidence / 失败结果并保留可用的分类证据
     */
    private static BackupException failed(String diagnostic) {
        return BackupException.create(BackupFailureType.RESTORE_PREFLIGHT_FAILED, diagnostic);
    }
}
