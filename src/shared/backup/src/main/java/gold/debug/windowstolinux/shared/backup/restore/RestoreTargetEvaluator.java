package gold.debug.windowstolinux.shared.backup.restore;

import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.backup.restore.RestoreTargetProfile;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Converts live read-only target evidence into the strict portable restore profile. / 将实时只读目标证据转换为严格可移植恢复资料。 */
public final class RestoreTargetEvaluator {
    public RestoreTargetProfile evaluate(
            BackupManifest manifest, boolean databasePresent,
            String serverId,
            ServerCapabilityFacts server,
            LinuxCapabilityFacts linux,
            RemoteRestoreActivationPort.PreflightEvidence activation,
            boolean existingOwnedApplication
    ) throws BackupException {
        requireHost(manifest, server, linux);
        Set<Integer> officialPorts = officialPorts(manifest, "tcp");
        Set<Integer> udpPorts = officialPorts(manifest, "udp");
        boolean portsAvailable = existingOwnedApplication
                || activation.occupiedTcpPorts().stream().noneMatch(officialPorts::contains) && activation.occupiedUdpPorts().stream().noneMatch(udpPorts::contains);
        BackupDatabaseType databaseType = manifest.inventory().database().type();
        String databaseVersion = databasePresent ? manifest.inventory().database().engineVersion() : "none";
        boolean databaseCompatible = databaseType == BackupDatabaseType.NONE || databasePresent;
        List<String> evidence = new ArrayList<>(List.of(
                "target managed-helper protocol and typed runtime capabilities were collected live",
                existingOwnedApplication
                        ? "existing target application identity permits its reviewed formal ports"
                        : "reviewed formal ports were absent from live target listeners"));
        if (databasePresent) evidence.add(
                "database artifact identity and engine evidence verified; credential-bound target inspection is candidate-scoped");
        evidence.addAll(activation.evidence());
        return new RestoreTargetProfile(serverId, linux.distro().name().toLowerCase(Locale.ROOT), linux.version(),
                linux.architecture(), manifest.inventory().runtime().runtimeKind(),
                "managed-helper-" + server.managedHelperProtocolVersion(), databaseType, databaseVersion,
                activation.availableBytes(), activation.managedRootWritable(), portsAvailable,
                activation.foreignApplicationConflict(), false, databaseCompatible, false,
                evidence.stream().distinct().toList());
    }

    private static void requireHost(
            BackupManifest manifest, ServerCapabilityFacts server, LinuxCapabilityFacts linux) throws BackupException {
        if (server.managedHelperProtocolVersion() != ManagedHelperProtocolVersion.CURRENT
                || !server.nonInteractiveSudoAvailable() || !server.tarAvailable()
                || !server.systemdAvailable() || !linux.systemdAvailable()) {
            throw failed("target lacks the exact managed helper, tar, sudo, or systemd restore boundary");
        }
        Set<String> required = new HashSet<>(
                manifest.inventory().runtime().capabilities());
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

    private static boolean runtimeReady(
            DeploymentRuntimeSpecification runtime, ServerCapabilityFacts server, LinuxCapabilityFacts linux) {
        return switch (runtime) {
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
                            ? linux.dockerOperational() : linux.podmanOperational();
            case DeploymentRuntimeSpecification.GoService ignored -> true;
            case DeploymentRuntimeSpecification.RustService ignored -> true;
            case DeploymentRuntimeSpecification.DotNetService value -> version(linux, runtime, value.version());
            case DeploymentRuntimeSpecification.PhpService value -> version(linux, runtime, value.version());
            case DeploymentRuntimeSpecification.RubyService value -> version(linux, runtime, value.version());
            case DeploymentRuntimeSpecification.CmakeService ignored -> true;
        };
    }

    private static boolean version(
            LinuxCapabilityFacts linux, DeploymentRuntimeSpecification runtime, String version) {
        return linux.serviceRuntimeVersions().getOrDefault(runtime.projectType(), Set.of()).contains(version);
    }

    private static Set<Integer> officialPorts(BackupManifest manifest, String transport) {
        Set<Integer> ports = new HashSet<>();
        manifest.inventory().components().forEach(component -> {
            var runtime = component.runtime().toSpecification();
            runtime.workload().endpoints().stream().filter(endpoint -> endpoint.protocol().transport().equals(transport))
                    .forEach(endpoint -> ports.add(endpoint.hostPort()));
            if ((runtime.healthCheck() instanceof HealthCheck.Udp ? "udp" : "tcp").equals(transport))
                runtime.healthCheck().portNumber().ifPresent(ports::add);
        });
        return Set.copyOf(ports);
    }

    private static BackupException failed(String diagnostic) {
        return BackupException.create(BackupFailureType.RESTORE_PREFLIGHT_FAILED, diagnostic);
    }
}
