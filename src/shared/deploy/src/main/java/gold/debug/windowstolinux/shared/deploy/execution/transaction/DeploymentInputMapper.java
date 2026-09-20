package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretRevisionDigest;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteSecretPayload;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteManagedFileBinding;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Projects reviewed configuration into execution inputs without exporting configuration policy. / 将审阅配置投影为执行输入，不向执行层输出配置策略。 */
public final class DeploymentInputMapper {
    private DeploymentInputMapper() { }

    /** Selects only build values while preserving application identity. / 仅选择构建值并保留应用身份。 */
    public static RemoteBuildEnvironment build(ConfigurationSnapshot snapshot) {
        return new RemoteBuildEnvironment(snapshot.applicationId(), entries(snapshot, ConfigurationScope.BUILD));
    }

    /** Selects runtime values and carries the original snapshot digest unchanged. / 选择运行值并原样携带快照摘要。 */
    public static RemoteRuntimeConfiguration runtime(ConfigurationSnapshot snapshot) {
        return new RemoteRuntimeConfiguration(snapshot.applicationId(), snapshot.sha256(), entries(snapshot, ConfigurationScope.RUNTIME));
    }

    /** Retains exact release metadata for both deployment and restore. / 为部署和恢复保留精确发布元数据。 */
    public static RemoteDeploymentInputs manifest(DeploymentInputManifest inputs) {
        return new RemoteDeploymentInputs(inputs.configurationSha256(), inputs.secrets().stream()
                .map(DeploymentInputMapper::digest).toList());
    }

    /** Retains managed file identities and logical paths in canonical order. / 以规范顺序保留受管文件身份和逻辑路径。 */
    public static List<RemoteManagedFileBinding> files(List<ManagedFileBinding> bindings) {
        return bindings.stream().sorted(Comparator.comparing(ManagedFileBinding::bindingId))
                .map(value -> new RemoteManagedFileBinding(value.bindingId(), value.dataPath(), value.location(), value.resourceType(), "", value.seedFile(), List.of(), value.contentSha256())).toList();
    }

    public static List<RemoteManagedFileBinding> storage(String applicationId,
            gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings bindings,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime) {
        var result = new ArrayList<RemoteManagedFileBinding>(files(bindings.fileBindings()));
        gold.debug.windowstolinux.shared.config.resource.ManagedStoragePlan.resolve(applicationId, bindings, runtime);
        for (var binding : bindings.databaseBindings().orElse(List.of())) {
            if (binding.connection() instanceof gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Sqlite sqlite) {
                var path = new gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath(
                        sqlite.accessPath().isEmpty() ? sqlite.physicalPath(applicationId,binding.databaseId()) : sqlite.accessPath(),
                        gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath.AccessMode.READ_WRITE, "sqlite", true);
                result.add(new RemoteManagedFileBinding(binding.databaseId(), path, sqlite.location(),
                        gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.DATABASE,
                        sqlite.fileName(), sqlite.seedFile(), sqlite.initializationFiles(), ""));
            }
        }
        return List.copyOf(result);
    }

    /** Owns and clears every projected secret, including partial conversion and failed staging. / 持有并清零每个投影秘密，包括转换中断和暂存失败路径。 */
    public static DeploymentInputManifest stage(DeploymentRemoteSession session, ManagedApplication application,
            ConfigurationSnapshot snapshot, List<ResolvedSecretRevision> secrets) throws LinuxOperationException {
        var runtime = runtime(snapshot);
        if (!application.id().equals(runtime.applicationId())) throw new IllegalArgumentException("configuration application mismatch");
        var ordered = secrets.stream().sorted(Comparator.comparing((ResolvedSecretRevision value) -> value.reference().identifier())
                .thenComparingLong(value -> value.reference().revision())).toList();
        var inputs = new DeploymentInputManifest(snapshot.sha256(), ordered.stream().map(ResolvedSecretRevision::digest).toList());
        var expected = manifest(inputs);
        List<RemoteSecretPayload> payloads = new ArrayList<>();
        try {
            for (ResolvedSecretRevision secret : ordered) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("input conversion interrupted");
                byte[] copy = secret.copyValue();
                try { payloads.add(new RemoteSecretPayload(digest(secret.digest()), copy)); }
                finally { Arrays.fill(copy, (byte) 0); }
            }
            if (!expected.equals(session.stageDeploymentInputs(application, runtime, List.copyOf(payloads))))
                throw new IllegalStateException("staged input binding differs from the reviewed inputs");
            return inputs;
        } finally {
            payloads.forEach(RemoteSecretPayload::close);
        }
    }

    private static Map<String, String> entries(ConfigurationSnapshot snapshot, ConfigurationScope scope) {
        return snapshot.entries().stream().filter(entry -> entry.scope() == scope)
                .collect(Collectors.toMap(ConfigurationEntry::key, entry -> entry.value().canonicalValue()));
    }

    private static RemoteDeploymentInputs.SecretDigest digest(SecretRevisionDigest value) {
        return new RemoteDeploymentInputs.SecretDigest(value.reference().identifier(), value.reference().revision(),
                value.sha256(), value.byteCount());
    }
}
