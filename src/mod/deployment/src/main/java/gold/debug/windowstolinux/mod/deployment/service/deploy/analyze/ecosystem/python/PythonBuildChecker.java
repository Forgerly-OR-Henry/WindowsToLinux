package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.python;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.python.pip.PipBuildChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.python.pipenv.PipenvBuildChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.python.poetry.PoetryBuildChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.python.uv.UvBuildChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.BoundedMetadataChecker;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.ProjectIdentityResolver;

/**
 * Reads pyproject.toml and fixed lockfile names without invoking Python.
 *
 *  <p>读取 pyproject.toml 与固定锁文件名称，但不调用 Python。
 */
public final class PythonBuildChecker {
    /**
     * Pattern recognizing NAME.
     * <p>用于识别名称的匹配模式。
     */
    private static final Pattern NAME = Pattern
        .compile("(?m)^\\s*name\\s*=\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"\\s*$");

    /**
     * Bound pip build inspector collaborator for pip.
     * <p>处理pip 构建的Pip构建检查器协作对象。
     */
    private final PipBuildChecker pip = new PipBuildChecker();

    /**
     * Bound pipenv build inspector collaborator for pipenv.
     * <p>处理Pipenv 构建的Pipenv构建检查器协作对象。
     */
    private final PipenvBuildChecker pipenv = new PipenvBuildChecker();

    /**
     * Bound poetry build inspector collaborator for poetry.
     * <p>处理Poetry 构建的Poetry构建检查器协作对象。
     */
    private final PoetryBuildChecker poetry = new PoetryBuildChecker();

    /**
     * Bound uv build inspector collaborator for the supplied uv build inspector.
     * <p>处理所提供的Uv构建检查器的Uv构建检查器协作对象。
     */
    private final UvBuildChecker uv = new UvBuildChecker();

    /** Inspects Python metadata with an explicit fallback source name. / 使用明确的源码回退名称检查Python元数据。
     * @param root physical source root / 物理源码根
     * @param logicalRootName reviewed source name / 经审阅源码名称
     * @return Python build facts when present / 存在时的Python构建事实
     * @throws IOException if reading fails / 读取失败时 */
    public Optional<PythonBuildFacts> inspect(Path root, String logicalRootName) throws IOException {
        Path pyproject = root.resolve("pyproject.toml");
        if (!BoundedMetadataChecker.regular(pyproject)) {
            return Optional.empty();
        }
        String toml = BoundedMetadataChecker.read(pyproject);
        List<PythonBuildArchitectureFacts> selected = List.of(
                pip.inspect(root)
                    .map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.PIP_LOCKED, lock)),
                pipenv.inspect(root)
                    .map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.PIPENV_LOCKED, lock)),
                poetry.inspect(root)
                    .map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.POETRY_LOCKED, lock)),
                uv.inspect(root).map(lock -> new PythonBuildArchitectureFacts(DeploymentBuildToolType.UV_LOCKED, lock)))
            .stream().flatMap(Optional::stream).toList();
        List<String> lockFiles = selected.stream().map(PythonBuildArchitectureFacts::lockFile).toList();
        boolean stdlib = selected.isEmpty() && toml.matches("(?s).*\n?\\s*dependencies\\s*=\\s*\\[\\s*]\\s*(?:\n|$).*")
            && !toml.contains("dynamic") && !toml.contains("[tool.poetry.dependencies]");
        DeploymentBuildToolType buildTool = stdlib
            ? DeploymentBuildToolType.PYTHON_STDLIB
            : selected.size() == 1 ? selected.getFirst().buildTool() : DeploymentBuildToolType.PIP_LOCKED;
        return Optional.of(new PythonBuildFacts(
            ProjectIdentityResolver.applicationId(root, toml, NAME, logicalRootName), buildTool, lockFiles));
    }
}
