package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.pip.PipBuildInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.pipenv.PipenvBuildInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.poetry.PoetryBuildInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.python.uv.UvBuildInspector;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads pyproject.toml and fixed lockfile names without invoking Python.
 *
 *  <p>读取 pyproject.toml 与固定锁文件名称，但不调用 Python。
 */
public final class PythonBuildInspector {
    /**
     * Pattern recognizing NAME.
     * <p>用于识别名称的匹配模式。
     */
    private static final Pattern NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"\\s*$");
    /**
     * Bound pip build inspector collaborator for pip.
     * <p>处理pip 构建的Pip构建检查器协作对象。
     */
    private final PipBuildInspector pip = new PipBuildInspector();
    /**
     * Bound pipenv build inspector collaborator for pipenv.
     * <p>处理Pipenv 构建的Pipenv构建检查器协作对象。
     */
    private final PipenvBuildInspector pipenv = new PipenvBuildInspector();
    /**
     * Bound poetry build inspector collaborator for poetry.
     * <p>处理Poetry 构建的Poetry构建检查器协作对象。
     */
    private final PoetryBuildInspector poetry = new PoetryBuildInspector();
    /**
     * Bound uv build inspector collaborator for the supplied uv build inspector.
     * <p>处理所提供的Uv构建检查器的Uv构建检查器协作对象。
     */
    private final UvBuildInspector uv = new UvBuildInspector();

    /**
     * Returns Python project facts when pyproject.toml exists. / 在 pyproject.toml 存在时返回 Python 项目事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Optional<PythonBuildFacts> inspect(Path root) throws IOException {
        Path pyproject = root.resolve("pyproject.toml");
        if (!BoundedMetadataInspector.regular(pyproject)) {
            return Optional.empty();
        }
        String toml = BoundedMetadataInspector.read(pyproject);
        List<PythonBuildArchitectureFacts> selected = List.of(
                        pip.inspect(root).map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.PIP_LOCKED, lock)),
                        pipenv.inspect(root).map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.PIPENV_LOCKED, lock)),
                        poetry.inspect(root).map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.POETRY_LOCKED, lock)),
                        uv.inspect(root).map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.UV_LOCKED, lock)))
                .stream()
                .flatMap(Optional::stream)
                .toList();
        List<String> lockFiles = selected.stream().map(PythonBuildArchitectureFacts::lockFile).toList();
        boolean stdlib = selected.isEmpty() && toml.matches("(?s).*\n?\\s*dependencies\\s*=\\s*\\[\\s*]\\s*(?:\n|$).*")
                && !toml.contains("dynamic") && !toml.contains("[tool.poetry.dependencies]");
        DeploymentBuildToolType buildTool = stdlib ? DeploymentBuildToolType.PYTHON_STDLIB : selected.size() == 1
                ? selected.getFirst().buildTool() : DeploymentBuildToolType.PIP_LOCKED;
        return Optional.of(new PythonBuildFacts(ProjectIdentityResolver.applicationId(root, toml, NAME),
                buildTool, lockFiles));
    }
}
