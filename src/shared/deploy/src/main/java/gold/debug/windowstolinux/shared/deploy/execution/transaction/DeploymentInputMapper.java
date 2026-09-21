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

/**
 * Projects reviewed configuration into execution inputs without exporting configuration policy. / 将审阅配置投影为执行输入，不向执行层输出配置策略。
 */
public final class DeploymentInputMapper {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DeploymentInputMapper() { }

    /**
     * Selects only build values while preserving application identity. / 仅选择构建值并保留应用身份。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return only build values while preserving application identity / 仅选择构建值并保留应用身份
     */
    public static RemoteBuildEnvironment build(ConfigurationSnapshot snapshot) {
        return new RemoteBuildEnvironment(snapshot.applicationId(), entries(snapshot, ConfigurationScope.BUILD));
    }

    /**
     * Selects runtime values and carries the original snapshot digest unchanged. / 选择运行值并原样携带快照摘要。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return runtime values and carries the original snapshot digest unchanged / 运行值并原样携带快照摘要
     */
    public static RemoteRuntimeConfiguration runtime(ConfigurationSnapshot snapshot) {
        return new RemoteRuntimeConfiguration(snapshot.applicationId(), snapshot.sha256(), entries(snapshot, ConfigurationScope.RUNTIME));
    }

    /**
     * Retains exact release metadata for both deployment and restore. / 为部署和恢复保留精确发布元数据。
     *
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @return constructed or resolved remote deployment inputs / 构造或解析得到的远端部署输入集合
     */
    public static RemoteDeploymentInputs manifest(DeploymentInputManifest inputs) {
        return new RemoteDeploymentInputs(inputs.configurationSha256(), inputs.secrets().stream()
                .map(DeploymentInputMapper::digest).toList());
    }

    /**
     * Retains managed file identities and logical paths in canonical order. / 以规范顺序保留受管文件身份和逻辑路径。
     *
     * @param bindings bindings / 绑定集合
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    public static List<RemoteManagedFileBinding> files(List<ManagedFileBinding> bindings) {
        return bindings.stream().sorted(Comparator.comparing(ManagedFileBinding::bindingId))
                .map(value -> new RemoteManagedFileBinding(value.bindingId(), value.dataPath(), value.location(), value.resourceType(), "", value.seedFile(), List.of(), value.contentSha256())).toList();
    }

    /**
     * Maps reviewed configuration, file and database resources to remote managed file bindings.
     * <p>将已审阅配置、文件及数据库资源映射为远端受管文件绑定。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param bindings bindings / 绑定集合
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return constructed or resolved list / 构造或解析得到的列表
     */
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

    /**
     * Owns and clears every projected secret, including partial conversion and failed staging. / 持有并清零每个投影秘密，包括转换中断和暂存失败路径。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @return constructed or resolved deployment input manifest / 构造或解析得到的部署输入清单
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
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

    /**
     * Selects configuration entries in the requested scope and projects their canonical string values.
     * <p>选择请求作用域内的配置项，并投影其规范字符串值。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @return configuration entries in the requested scope and projects their canonical string values / 请求作用域内的配置项，并投影其规范字符串值
     */
    private static Map<String, String> entries(ConfigurationSnapshot snapshot, ConfigurationScope scope) {
        return snapshot.entries().stream().filter(entry -> entry.scope() == scope)
                .collect(Collectors.toMap(ConfigurationEntry::key, entry -> entry.value().canonicalValue()));
    }

    /**
     * Computes or retrieves content identity for independent evidence checks.
     * <p>计算或取得用于独立证据检查的内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return or retrieves content identity for independent evidence checks / 或取得用于独立证据检查的内容身份
     */
    private static RemoteDeploymentInputs.SecretDigest digest(SecretRevisionDigest value) {
        return new RemoteDeploymentInputs.SecretDigest(value.reference().identifier(), value.reference().revision(),
                value.sha256(), value.byteCount());
    }
}
