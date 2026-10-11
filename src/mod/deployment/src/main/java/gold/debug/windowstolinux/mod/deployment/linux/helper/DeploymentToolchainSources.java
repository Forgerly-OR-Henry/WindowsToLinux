package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;

/** Shares fixed metadata and archive validation without privileged installation orchestration. / 共享固定元数据与归档校验，不共享特权安装编排。 */
public final class DeploymentToolchainSources {
    private static final String ROOT = "/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain/";

    private DeploymentToolchainSources() {
    }

    /** Renders the same shipped catalog and official download verifier as the helper. / 渲染与 helper 相同的内置目录及官方下载校验。 */
    public static String metadata() {
        return metadata(DeploymentRemoteLayout.original());
    }

    public static String metadata(DeploymentRemoteLayout layout) {
        return DeploymentManagedHelperBundle.catalogLiteral(DeploymentToolchainCatalog.defaults())
            + DeploymentRemoteLayoutTemplate.render(resource("10-release-metadata.py"), layout) + "\n";
    }

    /** Renders only fixed filesystem and archive checks. / 仅渲染固定文件系统及归档检查。 */
    public static String archives() {
        return resource("archive-boundaries.py");
    }

    private static String resource(String name) {
        try (var input = DeploymentToolchainSources.class.getResourceAsStream(ROOT + name)) {
            if (input == null)
                throw new IllegalStateException("Toolchain source resource unavailable: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Toolchain source resource unreadable: " + name, failure);
        }
    }
}
