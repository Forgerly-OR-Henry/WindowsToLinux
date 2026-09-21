package gold.debug.windowstolinux.shared.deploy.contract;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fully reviewed typed deployment input that contains identities and typed definitions, never a shell command.
 *
 *  <p>经过完整审阅的部署输入，包含身份和类型化定义，绝不包含 Shell 命令。
 *
 * @param server the trusted target server / 可信目标服务器
 * @param facts the complete static project facts / 完整静态项目事实
 * @param sourceRevision the bound source revision / 绑定的源码修订
 * @param archive the deterministic source archive / 确定性源码归档
 * @param configuration the immutable normal configuration / 不可变普通配置
 * @param secretReferences the opaque secret revisions / 透明秘密修订引用
 * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
 * @param fileBindings file bindings / 文件绑定集合
 * @param runtime the type-specific runtime definition / 类型专属运行定义
 * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
 * @param limits the target-host build limits / 目标机构建限制
 * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
 * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
 * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
 */
public record ReviewedDeploymentRequest(
        ServerIdentity server,
        DeploymentProjectFacts facts,
        SourceRevision sourceRevision,
        SourceArchiveDescriptor archive,
        ConfigurationSnapshot configuration,
        List<SecretReference> secretReferences,
        Optional<List<ManagedDatabaseBinding>> databaseBindings,
        List<gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding> fileBindings,
        DeploymentRuntimeSpecification runtime,
        Optional<UserAccessUrl> userAccessUrl,
        BuildLimitConfiguration limits,
        DeploymentApproval approval,
        boolean containerDaemonRiskAccepted,
        boolean experimentalAdapterRiskAccepted
) {
    /**
     * Validates and binds the inputs required by reviewed deployment request.
     * <p>校验并绑定已审阅部署请求所需输入。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param sourceRevision the bound source revision / 绑定的源码修订
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @param fileBindings file bindings / 文件绑定集合
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedDeploymentRequest {
        server = Objects.requireNonNull(server, "server");
        facts = Objects.requireNonNull(facts, "facts");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        archive = Objects.requireNonNull(archive, "archive");
        configuration = Objects.requireNonNull(configuration, "configuration");
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        databaseBindings = Objects.requireNonNull(databaseBindings, "databaseBindings")
                .map(values -> List.copyOf(values.stream()
                        .map(value -> Objects.requireNonNull(value, "database binding"))
                        .sorted(java.util.Comparator.comparing(ManagedDatabaseBinding::databaseId)).toList()));
        fileBindings = List.copyOf(Objects.requireNonNull(fileBindings, "fileBindings"));
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) {
            var mutable = new java.util.ArrayList<>(fileBindings);
            for (var volume : container.volumes()) {
                if (mutable.stream().noneMatch(binding -> binding.bindingId().equals(volume.bindingId())))
                    mutable.add(new gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding(volume.bindingId(),
                            new gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath(volume.containerPath(),volume.readOnly()
                                    ? gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath.AccessMode.READ_ONLY
                                    : gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath.AccessMode.READ_WRITE,"files",true),
                            gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.defaults()));
            }
            for (var volume : container.volumes()) {
                var binding = mutable.stream().filter(file -> file.bindingId().equals(volume.bindingId())).findFirst().orElseThrow();
                if (!binding.dataPath().path().equals(volume.containerPath())
                        || binding.resourceType() != gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.FILE
                        || (binding.dataPath().access() == gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath.AccessMode.READ_ONLY) != volume.readOnly())
                    throw new IllegalArgumentException("container volume and storage binding differ");
            }
            fileBindings = List.copyOf(mutable);
        }
        gold.debug.windowstolinux.shared.config.resource.ManagedStoragePlan.resolve(facts.applicationId(),
                new gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings(fileBindings, databaseBindings), runtime);
        userAccessUrl = Objects.requireNonNull(userAccessUrl, "userAccessUrl");
        limits = Objects.requireNonNull(limits, "limits");
        approval = Objects.requireNonNull(approval, "approval");
        if (!facts.readyForPlanning()) {
            throw new IllegalArgumentException("typed deployment requests require complete deterministic project facts");
        }
        if (!facts.buildDirectory().equals(runtime.workload().buildDirectory()))
            throw new IllegalArgumentException("runtime build directory differs from analyzed source ownership");
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("runtime specification must match the analyzed project type");
        }
        new gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration(
                runtime.healthCheck(), userAccessUrl);
        if (!facts.applicationId().equals(configuration.applicationId())
                || !facts.applicationId().equals(approval.applicationId())
                || !server.id().equals(approval.serverId())) {
            throw new IllegalArgumentException("application, configuration, server, and approval identities must match");
        }
        if (!archive.contentSha256().equals(sourceRevision.sourceSha256())
                || !archive.contentSha256().equals(approval.sourceSha256())) {
            throw new IllegalArgumentException("archive, source revision, and approval must bind the same SHA-256");
        }
        if (runtime.identityPolicy() == gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException("New deployment requires an explicit runtime identity policy");
        }
        if (limits.runAsRoot() || approval.rootBuildAccepted()) {
            throw new IllegalArgumentException("Root builds are no longer supported; root manages the project and a restricted identity builds it");
        }
        if (runtime instanceof DeploymentRuntimeSpecification.Container container
                && container.engine() == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER && !containerDaemonRiskAccepted) {
            throw new IllegalArgumentException("Docker deployments require a fresh explicit daemon-risk approval");
        }
        if ((facts.support().level()
                == gold.debug.windowstolinux.shared.model.project.DeploymentSupportLevel.EXPERIMENTAL_ADAPTER
                || !runtime.workload().companions().isEmpty()) && !experimentalAdapterRiskAccepted) {
            throw new IllegalArgumentException(
                    "experimental adapters require a fresh explicit test-environment approval");
        }
        if (secretReferences.stream().distinct().count() != secretReferences.size()) {
            throw new IllegalArgumentException("secret references must be unique");
        }
        if (databaseBindings.isPresent() && databaseBindings.orElseThrow().stream()
                .map(ManagedDatabaseBinding::databaseId).distinct().count() != databaseBindings.orElseThrow().size()) {
            throw new IllegalArgumentException("database binding identities must be unique");
        }
        List<SecretReference> reviewedSecretReferences = secretReferences;
        if (databaseBindings.isPresent() && databaseBindings.orElseThrow().stream()
                .map(ManagedDatabaseBinding::connection)
                .filter(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::isInstance)
                .map(gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection.Server.class::cast)
                .anyMatch(connection -> !reviewedSecretReferences.contains(connection.passwordReference()))) {
            throw new IllegalArgumentException("server database password references must belong to the reviewed deployment");
        }
    }

    /**
     * Initializes reviewed deployment request through its shared constructor contract.
     * <p>通过共享构造契约初始化已审阅部署请求。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param sourceRevision the bound source revision / 绑定的源码修订
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     */
    public ReviewedDeploymentRequest(ServerIdentity server, DeploymentProjectFacts facts, SourceRevision sourceRevision,
            SourceArchiveDescriptor archive, ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
            Optional<List<ManagedDatabaseBinding>> databaseBindings, DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimitConfiguration limits, DeploymentApproval approval,
            boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted) {
        this(server, facts, sourceRevision, archive, configuration, secretReferences, databaseBindings, List.of(), runtime,
                userAccessUrl, limits, approval, containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /**
     * Returns the contract with the supplied files applied.
     * <p>返回应用所提供文件集合后的契约。
     *
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @return the contract with the supplied files applied / 应用所提供文件集合后的契约
     */
    public ReviewedDeploymentRequest withFiles(List<gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding> files) {
        return new ReviewedDeploymentRequest(server, facts, sourceRevision, archive, configuration, secretReferences,
                databaseBindings, files, runtime, userAccessUrl, limits, approval, containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /**
     * Creates a request without reviewed database scope for existing callers.
     *
     *  <p>为现有调用方创建尚未审阅数据库范围的请求。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param sourceRevision the bound source revision / 绑定的源码修订
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     */
    public ReviewedDeploymentRequest(
            ServerIdentity server,
            DeploymentProjectFacts facts,
            SourceRevision sourceRevision,
            SourceArchiveDescriptor archive,
            ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences,
            DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl,
            BuildLimitConfiguration limits,
            DeploymentApproval approval,
            boolean containerDaemonRiskAccepted,
            boolean experimentalAdapterRiskAccepted
    ) {
        this(server, facts, sourceRevision, archive, configuration, secretReferences, Optional.empty(), runtime,
                userAccessUrl, limits, approval, containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /**
     * Creates a request without reviewed database scope or experimental-adapter permission.
     *
     *  <p>创建不含数据库范围审阅或试验适配器许可的请求。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param sourceRevision the bound source revision / 绑定的源码修订
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     */
    public ReviewedDeploymentRequest(
            ServerIdentity server,
            DeploymentProjectFacts facts,
            SourceRevision sourceRevision,
            SourceArchiveDescriptor archive,
            ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences,
            DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl,
            BuildLimitConfiguration limits,
            DeploymentApproval approval,
            boolean containerDaemonRiskAccepted
    ) {
        this(server, facts, sourceRevision, archive, configuration, secretReferences, Optional.empty(), runtime,
                userAccessUrl, limits, approval, containerDaemonRiskAccepted, false);
    }
}
