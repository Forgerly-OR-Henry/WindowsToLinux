package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.util.List;
import java.util.Objects;

/**
 * Fully reviewed typed deployment deployment input that contains identities and typed definitions, never a shell command.
 *
 * <p>经过完整审阅的部署部署输入，包含身份和类型化定义，绝不包含 Shell 命令。
 *
 * @param server the trusted target server / 可信目标服务器
 * @param facts the complete static project facts / 完整静态项目事实
 * @param sourceRevision the bound source revision / 绑定的源码修订
 * @param archive the deterministic source archive / 确定性源码归档
 * @param configuration the immutable normal configuration / 不可变普通配置
 * @param secretReferences the opaque secret revisions / 透明秘密修订引用
 * @param runtime the type-specific runtime definition / 类型专属运行定义
 * @param limits the target-host build limits / 目标机构建限制
 * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
 * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
 */
public record ReviewedDeploymentRequest(
        ServerIdentity server,
        DeploymentProjectFacts facts,
        SourceRevision sourceRevision,
        SourceArchiveDescriptor archive,
        ConfigurationSnapshot configuration,
        List<SecretReference> secretReferences,
        DeploymentRuntimeSpecification runtime,
        BuildLimits limits,
        DeploymentApproval approval,
        boolean containerDaemonRiskAccepted
) {
    /**
     * Creates a {@code ReviewedDeploymentRequest} instance.
     *
     * <p>创建 {@code ReviewedDeploymentRequest} 实例。
     */
    public ReviewedDeploymentRequest {
        server = Objects.requireNonNull(server, "server");
        facts = Objects.requireNonNull(facts, "facts");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        archive = Objects.requireNonNull(archive, "archive");
        configuration = Objects.requireNonNull(configuration, "configuration");
        secretReferences = List.copyOf(Objects.requireNonNull(secretReferences, "secretReferences"));
        runtime = Objects.requireNonNull(runtime, "runtime");
        limits = Objects.requireNonNull(limits, "limits");
        approval = Objects.requireNonNull(approval, "approval");
        if (!facts.readyForPlanning()) {
            throw new IllegalArgumentException("typed deployment deployment requests require complete deterministic project facts");
        }
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("runtime specification must match the analyzed project type");
        }
        if (!facts.applicationId().equals(configuration.applicationId())
                || !facts.applicationId().equals(approval.applicationId())
                || !server.id().equals(approval.serverId())) {
            throw new IllegalArgumentException("application, configuration, server, and approval identities must match");
        }
        if (!archive.contentSha256().equals(sourceRevision.sourceSha256())
                || !archive.contentSha256().equals(approval.sourceSha256())) {
            throw new IllegalArgumentException("archive, source revision, and approval must bind the same SHA-256");
        }
        if (limits.runAsRoot() != approval.rootBuildAccepted()) {
            throw new IllegalArgumentException("root-build approval must match the requested build mode");
        }
        if (runtime instanceof DeploymentRuntimeSpecification.Container container
                && container.engine() == DeploymentRuntimeSpecification.ContainerEngine.DOCKER && !containerDaemonRiskAccepted) {
            throw new IllegalArgumentException("Docker deployments require a fresh explicit daemon-risk approval");
        }
        if (secretReferences.stream().distinct().count() != secretReferences.size()) {
            throw new IllegalArgumentException("secret references must be unique");
        }
    }
}
