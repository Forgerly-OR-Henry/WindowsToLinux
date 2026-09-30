package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Confirmed bounded build identity, separate from eventual publication. / 已确认的有界构建身份，区别于最终发布。
 * @param archiveSha256 actual compressed source bytes used by the candidate / 候选使用的实际压缩来源字节摘要
 * @param candidateId exact candidate verified by the helper / helper 核实的精确候选
 * @param toolchains exact installed tools used for this build / 本次构建使用的精确已安装工具
 * @param toolchainBindingSha256 verified binding identity / 已核实的绑定身份
 * @param operation explicit later publication operation, absent for the original candidate / 明确后续发布操作；原候选为空 */
public record DeploymentVerifiedBuildResult(String archiveSha256, String candidateId,
        DeploymentResolvedToolchains toolchains, String toolchainBindingSha256, Optional<UUID> operation) {
    /** Retains original build result naming and source validation. / 保留原构建结果命名及来源校验。
     * @param archiveSha256 original source archive / 原来源归档
     * @param candidateId original candidate / 原候选
     * @param toolchains actual installed tools / 实际安装工具
     * @param toolchainBindingSha256 exact tool binding / 精确工具绑定 */
    public DeploymentVerifiedBuildResult(String archiveSha256, String candidateId,
            DeploymentResolvedToolchains toolchains, String toolchainBindingSha256) {
        this(archiveSha256, candidateId, toolchains, toolchainBindingSha256, Optional.empty());
    }
    /** Requires the source and binding identities to match their original values. / 要求来源与绑定身份吻合原值。
     * @param archiveSha256 compressed source byte digest / 压缩来源字节摘要
     * @param candidateId exact candidate / 精确候选
     * @param toolchains exact tool selections / 精确工具选择
     * @param toolchainBindingSha256 verified binding digest / 已核实绑定摘要
     * @param operation explicit later operation or original absence / 明确后续操作或原空值 */
    public DeploymentVerifiedBuildResult {
        if (!Objects.requireNonNull(archiveSha256).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid archive digest");
        if (candidateId == null || !candidateId.matches("[a-z0-9][a-z0-9-]{0,62}-[0-9a-f]{16}"))
            throw new IllegalArgumentException("Invalid candidate identity");
        operation = Objects.requireNonNull(operation);
        String application = candidateId.substring(0, candidateId.length() - 17);
        if (!candidateId.equals(new gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace(
                application, archiveSha256, operation).candidateId()))
            throw new IllegalArgumentException("Candidate differs from source archive");
        if (!DeploymentToolchainBindingCodec.identity(Objects.requireNonNull(toolchains))
                .equals(toolchainBindingSha256))
            throw new IllegalArgumentException("Toolchain binding differs from selections");
    }
}
