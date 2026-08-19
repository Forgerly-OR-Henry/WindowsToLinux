package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin;

import gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.gradle.KotlinGradleDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.kotlinc.KotlinCompilerDeploymentInspector;
import gold.debug.windowstolinux.shared.analyze.service.ServiceMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeAssessment;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Selects exactly one reviewed Kotlin build architecture. / 恰好选择一个经审阅的 Kotlin 构建架构。 */
public final class KotlinServiceDeploymentInspector implements DeploymentTypeInspector {
    private final KotlinGradleDeploymentInspector gradle = new KotlinGradleDeploymentInspector();
    private final KotlinCompilerDeploymentInspector kotlinc = new KotlinCompilerDeploymentInspector();

    /** Returns the Kotlin service project type. / 返回 Kotlin 服务项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }

    /** Rejects architecture conflicts and delegates to one architecture inspector. / 拒绝架构冲突并委派给一个架构检查器。 */
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
        return nativeMetadata ? kotlinc.inspect(root, source, languageFacts, rejections)
                : gradle.inspect(root, source, languageFacts, rejections);
    }
}
