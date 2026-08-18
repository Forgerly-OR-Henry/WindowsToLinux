package gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.project.kotlin;

import gold.debug.windowstolinux.shared.analyze.ecosystem.service.ServiceDeploymentInspectorSupport;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspection;
import gold.debug.windowstolinux.shared.analyze.spi.DeploymentTypeInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Provides the {@code KotlinServiceDeploymentInspector} type. / 提供 {@code KotlinServiceDeploymentInspector} 类型。 */
public final class KotlinServiceDeploymentInspector implements DeploymentTypeInspector {
    private final ServiceDeploymentInspectorSupport support = new ServiceDeploymentInspectorSupport(DeploymentProjectType.KOTLIN_SERVICE);
    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }
    /** Inspects source facts for this deployment type. / 检查此部署类型的源码事实。 */
    @Override public DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts, List<RejectionReason> rejections) throws IOException { return support.inspect(root, source, languageFacts, rejections); }
}
