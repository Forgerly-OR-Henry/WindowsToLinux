package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One bounded inspector for one user-selected deployment type.
 *
 * <p>面向一个用户选定部署类型的单一有界检查器。
 */
public interface DeploymentTypeInspector {
    /** Returns the only supported project type. / 返回唯一支持的项目类型。 */
    DeploymentProjectType projectType();

    /** Inspects type-local facts and suggestions. / 检查类型局部事实与建议。 */
    DeploymentTypeInspection inspect(Path root, SourceInspection source, ProjectLanguageFacts languageFacts,
                                     List<RejectionReason> rejections) throws IOException;
}
