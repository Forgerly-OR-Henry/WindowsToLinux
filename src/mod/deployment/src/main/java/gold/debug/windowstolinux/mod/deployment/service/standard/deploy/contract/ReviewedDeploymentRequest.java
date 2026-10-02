package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract;

import java.util.*;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentSupportLevel;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStoragePlan;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;

/** Complete reviewed standard candidate using two distinct source digests and original platform handles. / 使用两种独立来源摘要及原平台句柄的完整已审阅标准候选。
 * @param server exact selected platform server / 精确选定的平台服务器
 * @param facts analyzed static facts / 已分析静态事实
 * @param material still-owned source material / 仍受管的源码素材
 * @param archive archive actually built from that material / 从该素材实际构建的归档
 * @param configuration immutable normal configuration / 不可变普通配置
 * @param secretReferences exact secret revisions / 精确秘密修订
 * @param resources reviewed file and database scopes / 已审阅文件及数据库范围
 * @param runtime reviewed runtime / 已审阅运行定义
 * @param userAccessUrl optional user access URL / 可选用户访问URL
 * @param limits reviewed build limits / 已审阅构建限制
 * @param review source and server review, not a remote grant / 来源与服务器审阅，非远端授权
 * @param containerDaemonRiskAccepted explicit container risk decision / 显式容器风险决定
 * @param experimentalAdapterRiskAccepted explicit experimental decision / 显式试验适配器决定 */
public record ReviewedDeploymentRequest(ServerRef server, DeploymentProjectFacts facts,
                                        DeploymentSourceMaterial material, DeploymentSourceArchive archive,
                                        DeploymentConfigurationSnapshot configuration,
                                        List<DeploymentSecretReference> secretReferences,
                                        DeploymentResourceBindings resources, DeploymentRuntimeDefinition runtime,
                                        Optional<DeploymentAccessUrl> userAccessUrl, DeploymentBuildLimits limits,
                                        DeploymentReviewConsent review,
                                        boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted) {
    /** Checks every source, identity, risk and runtime binding before a deterministic plan is returned. / 返回确定性计划前核对全部来源、身份、风险及运行绑定。
     * @param server exact platform server / 精确平台服务器
     * @param facts analyzed facts / 已分析事实
     * @param material original material / 原素材
     * @param archive generated archive / 已生成归档
     * @param configuration reviewed configuration / 已审阅配置
     * @param secretReferences reviewed secrets / 已审阅秘密
     * @param resources reviewed resources / 已审阅资源
     * @param runtime reviewed runtime / 已审阅运行定义
     * @param userAccessUrl optional access URL / 可选访问URL
     * @param limits reviewed limits / 已审阅限制
     * @param review review record / 审阅记录
     * @param containerDaemonRiskAccepted container decision / 容器决定
     * @param experimentalAdapterRiskAccepted experimental decision / 试验决定 */
    public ReviewedDeploymentRequest {
        Objects.requireNonNull(server);
        Objects.requireNonNull(facts);
        Objects.requireNonNull(material);
        Objects.requireNonNull(archive);
        Objects.requireNonNull(configuration);
        secretReferences = List.copyOf(secretReferences);
        Objects.requireNonNull(resources);
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(userAccessUrl);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(review);
        if (runtime instanceof DeploymentRuntimeDefinition.Container container) {
            var files = new ArrayList<>(resources.fileBindings());
            for (var volume : container.volumes())
                if (files.stream().noneMatch(file -> file.bindingId().equals(volume.bindingId())))
                    files.add(new DeploymentFileBinding(volume.bindingId(),
                        new DeploymentStoragePath(volume.containerPath(),
                            volume.readOnly()
                                ? DeploymentStoragePath.AccessMode.READ_ONLY
                                : DeploymentStoragePath.AccessMode.READ_WRITE,
                            "files", true),
                        DeploymentStorageLocation.defaults()));
            resources = new DeploymentResourceBindings(files, resources.databaseBindings());
            for (var volume : container.volumes()) {
                var binding = resources.fileBindings().stream()
                    .filter(file -> file.bindingId().equals(volume.bindingId())).findFirst().orElseThrow();
                if (!binding.dataPath().path().equals(volume.containerPath())
                    || binding.resourceType() != DeploymentStorageLocation.StorageResourceType.FILE
                    || (binding.dataPath().access() == DeploymentStoragePath.AccessMode.READ_ONLY) != volume
                    .readOnly())
                    throw new IllegalArgumentException("Container volume and storage binding differ");
            }
        }
        var source = material.snapshot();
        var root = source.directory();
        archive.path();
        if (!facts.sourceRoot().equals(root.resolve(archive.relativeRoot()).normalize())
            || !archive.originSnapshotId().equals(source.id())
            || !archive.sourceManifestSha256().equals(source.sha256()))
            throw new IllegalArgumentException("Reviewed candidate is not bound to the original frozen source");
        if (!facts.readyForPlanning() || !facts.projectType().deployable()
            || facts.projectType() != runtime.projectType()
            || !facts.buildDirectory().equals(runtime.workload().buildDirectory()))
            throw new IllegalArgumentException("Runtime differs from complete analyzed facts");
        if (!facts.applicationId().equals(configuration.applicationId())
            || !facts.applicationId().equals(review.applicationId()) || !server.id().equals(review.serverId())
            || !source.sha256().equals(review.sourceManifestSha256())
            || !archive.sha256().equals(review.archiveSha256()))
            throw new IllegalArgumentException("Reviewed application, server or source identity differs");
        if (runtime.identityPolicy() == DeploymentIdentityMode.LEGACY_UNSPECIFIED || limits.runAsRoot())
            throw new IllegalArgumentException("New deployment requires a restricted build identity");
        if (runtime instanceof DeploymentRuntimeDefinition.Container container
            && container.engine() == DeploymentRuntimeDefinition.ContainerEngineType.DOCKER
            && !containerDaemonRiskAccepted)
            throw new IllegalArgumentException("Docker daemon risk requires explicit review");
        if ((facts.support().level() == DeploymentSupportLevel.EXPERIMENTAL_ADAPTER
            || !runtime.workload().companions().isEmpty()) && !experimentalAdapterRiskAccepted)
            throw new IllegalArgumentException("Experimental adapter requires explicit review");
        if (secretReferences.size() != new HashSet<>(secretReferences).size())
            throw new IllegalArgumentException("Reviewed secret revisions must be unique");
        if (resources.databaseBindings().isPresent())
            for (var database : resources.databaseBindings().orElseThrow())
                if (database.connection() instanceof DeploymentDatabaseConnection.Server connection
                    && !secretReferences.contains(connection.passwordReference()))
                    throw new IllegalArgumentException("Database password differs from reviewed secrets");
        DeploymentStoragePlan.resolve(facts.applicationId(), resources, runtime);
    }
}
