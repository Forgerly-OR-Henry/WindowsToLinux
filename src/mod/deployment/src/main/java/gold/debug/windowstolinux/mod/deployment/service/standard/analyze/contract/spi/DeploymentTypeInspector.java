package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.contract.spi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceInspectionFacts;

/**
 * One bounded inspector for one user-selected deployment type.
 *
 *  <p>面向一个用户选定部署类型的单一有界检查器。
 */
public interface DeploymentTypeInspector {
    /**
     * Returns the only supported project type. / 返回唯一支持的项目类型。
     *
     * @return the only supported project type / 唯一支持的项目类型
     */
    DeploymentProjectKind projectType();

    /**
     * Inspects type-local facts and suggestions. / 检查类型局部事实与建议。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param languageFacts language facts / 语言事实
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return constructed or resolved deployment type assessment / 构造或解析得到的部署类型评估
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    DeploymentTypeAssessment inspect(Path root, SourceInspectionFacts source, ProjectLanguageFacts languageFacts,
            List<RejectionReason> rejections) throws IOException;

}
