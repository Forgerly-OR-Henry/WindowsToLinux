package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.kotlin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.message.DeploymentLocalizedMessage;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.contract.DeploymentTypeChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.contract.DeploymentTypeReport;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.kotlin.gradle.KotlinGradleDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.kotlin.kotlinc.KotlinCompilerDeploymentChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.service.ServiceMetadataChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;

/**
 * Selects exactly one reviewed Kotlin build architecture. / 恰好选择一个经审阅的 Kotlin 构建架构。
 */
public final class KotlinServiceDeploymentChecker implements DeploymentTypeChecker {
    /**
     * Bound kotlin gradle deployment inspector collaborator for gradle.
     * <p>处理Gradle 构建的KotlinGradle部署检查器协作对象。
     */
    private final KotlinGradleDeploymentChecker gradle = new KotlinGradleDeploymentChecker();

    /**
     * Bound kotlin compiler deployment inspector collaborator for kotlinc.
     * <p>处理Kotlin 编译器的Kotlin编译器部署检查器协作对象。
     */
    private final KotlinCompilerDeploymentChecker kotlinc = new KotlinCompilerDeploymentChecker();

    /**
     * Returns the Kotlin service project type. / 返回 Kotlin 服务项目类型。
     *
     * @return the Kotlin service project type /  Kotlin 服务项目类型
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.KOTLIN_SERVICE;
    }

    /**
     * Rejects architecture conflicts and delegates to one architecture inspector. / 拒绝架构冲突并委派给一个架构检查器。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment; null when no matching value is available / 构造或解析得到的部署类型评估；没有匹配值时为 null
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override
    public DeploymentTypeReport inspect(Path root, SourceCheckFacts source, ProjectLanguageFacts languageFacts,
                                        List<RejectionReason> rejections) throws IOException {
        boolean nativeMetadata = ServiceMetadataChecker.present(root, "windowstolinux-kotlin.properties");
        boolean gradleMetadata = ServiceMetadataChecker.present(root, "build.gradle.kts")
            || ServiceMetadataChecker.present(root, "gradlew");
        if (nativeMetadata && gradleMetadata) {
            rejections.add(new RejectionReason("KOTLIN_ARCHITECTURE_CONFLICT",
                DeploymentLocalizedMessage.of("analysis.kotlin.architectureConflict"), "deployment"));
            return null;
        }
        return nativeMetadata
            ? kotlinc.inspect(root, source, languageFacts, rejections)
            : gradle.inspect(root, source, languageFacts, rejections);
    }
}
