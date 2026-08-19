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
 * <p>读取 pyproject.toml 与固定锁文件名称，但不调用 Python。
 */
public final class PythonBuildInspector {
    private static final Pattern NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"\\s*$");
    private final PipBuildInspector pip = new PipBuildInspector();
    private final PipenvBuildInspector pipenv = new PipenvBuildInspector();
    private final PoetryBuildInspector poetry = new PoetryBuildInspector();
    private final UvBuildInspector uv = new UvBuildInspector();

    /** Returns Python project facts when pyproject.toml exists. / 在 pyproject.toml 存在时返回 Python 项目事实。 */
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
        DeploymentBuildToolType buildTool = selected.size() == 1
                ? selected.getFirst().buildTool() : DeploymentBuildToolType.PIP_LOCKED;
        return Optional.of(new PythonBuildFacts(ProjectIdentityResolver.applicationId(root, toml, NAME),
                buildTool, lockFiles));
    }
}
