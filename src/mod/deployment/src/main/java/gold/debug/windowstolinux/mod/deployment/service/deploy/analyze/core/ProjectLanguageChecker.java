package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.core;

import java.io.IOException;
import java.nio.file.Path;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.c.CLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.dotnet.DotNetLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.go.GoLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.java.JavaLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.kotlin.KotlinLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.node.NodeLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.php.PhpLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.python.PythonLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.ruby.RubyLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.rust.RustLanguageChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.preview.PreviewLanguageMarkerCatalog;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;

/**
 * Merges independent language inspectors without ranking or selecting a project type.
 *
 *  <p>合并独立语言检查器，不进行排序或选择项目类型。
 */
public final class ProjectLanguageChecker {
    /**
     * Bound C language inspector collaborator for c inspector.
     * <p>处理c检查器的C语言检查器协作对象。
     */
    private final CLanguageChecker cInspector = new CLanguageChecker();

    /**
     * Bound dot net language inspector collaborator for dot net inspector.
     * <p>处理dotNet检查器的DotNet语言检查器协作对象。
     */
    private final DotNetLanguageChecker dotNetInspector = new DotNetLanguageChecker();

    /**
     * Bound go language inspector collaborator for go inspector.
     * <p>处理go检查器的Go语言检查器协作对象。
     */
    private final GoLanguageChecker goInspector = new GoLanguageChecker();

    /**
     * Bound java language inspector collaborator for java inspector.
     * <p>处理Java检查器的Java语言检查器协作对象。
     */
    private final JavaLanguageChecker javaInspector = new JavaLanguageChecker();

    /**
     * Bound kotlin language inspector collaborator for kotlin inspector.
     * <p>处理kotlin检查器的Kotlin语言检查器协作对象。
     */
    private final KotlinLanguageChecker kotlinInspector = new KotlinLanguageChecker();

    /**
     * Bound node language inspector collaborator for node inspector.
     * <p>处理节点检查器的节点语言检查器协作对象。
     */
    private final NodeLanguageChecker nodeInspector = new NodeLanguageChecker();

    /**
     * Bound php language inspector collaborator for php inspector.
     * <p>处理PHP检查器的PHP语言检查器协作对象。
     */
    private final PhpLanguageChecker phpInspector = new PhpLanguageChecker();

    /**
     * Bound python language inspector collaborator for python inspector.
     * <p>处理python检查器的Python语言检查器协作对象。
     */
    private final PythonLanguageChecker pythonInspector = new PythonLanguageChecker();

    /**
     * Bound ruby language inspector collaborator for ruby inspector.
     * <p>处理Ruby检查器的Ruby语言检查器协作对象。
     */
    private final RubyLanguageChecker rubyInspector = new RubyLanguageChecker();

    /**
     * Bound rust language inspector collaborator for rust inspector.
     * <p>处理rust检查器的Rust语言检查器协作对象。
     */
    private final RustLanguageChecker rustInspector = new RustLanguageChecker();

    /**
     * Preview marker catalog.
     * <p>预览标记目录。
     */
    private final PreviewLanguageMarkerCatalog previewMarkerCatalog = new PreviewLanguageMarkerCatalog();

    /**
     * Returns all deterministic language facts. / 返回全部确定性语言事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return all deterministic language facts / 全部确定性语言事实
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ProjectLanguageFacts inspect(Path root, SourceCheckFacts source) throws IOException {
        return ProjectLanguageFacts.merge(cInspector.inspect(source), dotNetInspector.inspect(source),
            goInspector.inspect(source), javaInspector.inspect(source), kotlinInspector.inspect(source),
            nodeInspector.inspect(root, source), phpInspector.inspect(source),
            pythonInspector.inspect(root, source), rubyInspector.inspect(source), rustInspector.inspect(source),
            previewMarkerCatalog.inspect(source));
    }
}
