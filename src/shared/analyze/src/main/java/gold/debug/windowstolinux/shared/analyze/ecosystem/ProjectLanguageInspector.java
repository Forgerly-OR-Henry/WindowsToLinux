package gold.debug.windowstolinux.shared.analyze.ecosystem;

import gold.debug.windowstolinux.shared.analyze.ecosystem.jvm.JavaLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.preview.PreviewLanguageMarkerCatalog;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.PythonLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.project.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Merges independent language inspectors without ranking or selecting a project type.
 *
 * <p>合并独立语言检查器，不进行排序或选择项目类型。
 */
public final class ProjectLanguageInspector {
    private final JavaLanguageInspector javaInspector = new JavaLanguageInspector();
    private final NodeLanguageInspector nodeInspector = new NodeLanguageInspector();
    private final PythonLanguageInspector pythonInspector = new PythonLanguageInspector();
    private final PreviewLanguageMarkerCatalog previewMarkerCatalog = new PreviewLanguageMarkerCatalog();

    /** Returns all deterministic language facts. / 返回全部确定性语言事实。 */
    public ProjectLanguageFacts inspect(Path root, SourceInspection source) throws IOException {
        return ProjectLanguageFacts.merge(javaInspector.inspect(root, source), nodeInspector.inspect(root, source),
                pythonInspector.inspect(root, source), previewMarkerCatalog.inspect(source));
    }
}
