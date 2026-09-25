package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.kotlin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.standard.analyze.contract.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.kotlin.gradle.KotlinGradleDeploymentInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.kotlin.kotlinc.KotlinCompilerDeploymentInspector;
import gold.debug.windowstolinux.shared.standard.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;

/**
 * Selects exactly one reviewed Kotlin build architecture. / 恰好选择一个经审阅的 Kotlin 构建架构。
 */
public final class KotlinServiceDeploymentInspector implements DeploymentTypeInspector {
    /**
     * Bound kotlin gradle deployment inspector collaborator for gradle.
     * <p>处理Gradle 构建的KotlinGradle部署检查器协作对象。
     */
    private final KotlinGradleDeploymentInspector gradle = new KotlinGradleDeploymentInspector();

    /**
     * Bound kotlin compiler deployment inspector collaborator for kotlinc.
     * <p>处理Kotlin 编译器的Kotlin编译器部署检查器协作对象。
     */
    private final KotlinCompilerDeploymentInspector kotlinc = new KotlinCompilerDeploymentInspector();

    /**
     * Returns the Kotlin service project type. / 返回 Kotlin 服务项目类型。
     *
     * @return the Kotlin service project type /  Kotlin 服务项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.KOTLIN_SERVICE;
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
    public DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections) throws IOException {
        boolean nativeMetadata = ServiceMetadataInspector.present(root, "windowstolinux-kotlin.properties");
        boolean gradleMetadata = ServiceMetadataInspector.present(root, "build.gradle.kts")
                || ServiceMetadataInspector.present(root, "gradlew");
        if (nativeMetadata && gradleMetadata) {
            rejections.add(new RejectionReason("KOTLIN_ARCHITECTURE_CONFLICT",
                    LocalizedMessage.of("analysis.kotlin.architectureConflict"), "deployment"));
            return null;
        }
        return nativeMetadata
                ? kotlinc.inspect(root, source, languageFacts, rejections)
                : gradle.inspect(root, source, languageFacts, rejections);
    }
}
