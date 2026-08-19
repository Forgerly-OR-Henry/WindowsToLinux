package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;

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

    /** Returns Python project facts when pyproject.toml exists. / 在 pyproject.toml 存在时返回 Python 项目事实。 */
    public Optional<PythonBuildFacts> inspect(Path root) throws IOException {
        Path pyproject = root.resolve("pyproject.toml");
        if (!BoundedMetadataInspector.regular(pyproject)) {
            return Optional.empty();
        }
        String toml = BoundedMetadataInspector.read(pyproject);
        return Optional.of(new PythonBuildFacts(ProjectIdentityResolver.applicationId(root, toml, NAME),
                BoundedMetadataInspector.existingNames(root, "requirements.lock", "poetry.lock", "uv.lock", "Pipfile.lock")));
    }
}
