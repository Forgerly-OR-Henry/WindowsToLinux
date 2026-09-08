package gold.debug.windowstolinux.shared.analyze.core;

import gold.debug.windowstolinux.shared.analyze.ecosystem.java.JavaLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.c.CLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.dotnet.DotNetLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.go.GoLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.KotlinLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.php.PhpLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.ruby.RubyLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.rust.RustLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.preview.PreviewLanguageMarkerCatalog;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.NodeLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.PythonLanguageInspector;
import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Merges independent language inspectors without ranking or selecting a project type.
 *
 * <p>合并独立语言检查器，不进行排序或选择项目类型。
 */
public final class ProjectLanguageInspector {
    private final CLanguageInspector cInspector = new CLanguageInspector();
    private final DotNetLanguageInspector dotNetInspector = new DotNetLanguageInspector();
    private final GoLanguageInspector goInspector = new GoLanguageInspector();
    private final JavaLanguageInspector javaInspector = new JavaLanguageInspector();
    private final KotlinLanguageInspector kotlinInspector = new KotlinLanguageInspector();
    private final NodeLanguageInspector nodeInspector = new NodeLanguageInspector();
    private final PhpLanguageInspector phpInspector = new PhpLanguageInspector();
    private final PythonLanguageInspector pythonInspector = new PythonLanguageInspector();
    private final RubyLanguageInspector rubyInspector = new RubyLanguageInspector();
    private final RustLanguageInspector rustInspector = new RustLanguageInspector();
    private final PreviewLanguageMarkerCatalog previewMarkerCatalog = new PreviewLanguageMarkerCatalog();

    /** Returns all deterministic language facts. / 返回全部确定性语言事实。 */
    public ProjectLanguageFacts inspect(Path root, SourceInspectionFacts source) throws IOException {
        return ProjectLanguageFacts.merge(cInspector.inspect(source), dotNetInspector.inspect(source),
                goInspector.inspect(source), javaInspector.inspect(source), kotlinInspector.inspect(source),
                nodeInspector.inspect(root, source), phpInspector.inspect(source), pythonInspector.inspect(root, source),
                rubyInspector.inspect(source), rustInspector.inspect(source), previewMarkerCatalog.inspect(source));
    }
}
