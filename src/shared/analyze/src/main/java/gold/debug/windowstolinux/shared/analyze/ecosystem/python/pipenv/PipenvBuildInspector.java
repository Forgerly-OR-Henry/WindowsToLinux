package gold.debug.windowstolinux.shared.analyze.ecosystem.python.pipenv;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects the Pipenv lockfile architecture. / 检查 Pipenv 锁文件架构。 */
public final class PipenvBuildInspector {
    private static final Pattern PIPFILE_PYTHON = Pattern.compile(
            "(?m)^\\s*python_version\\s*=\\s*\"(3[.](?:10|11|12|13))\"\\s*$");
    private static final Pattern LOCK_PYTHON = Pattern.compile(
            "(?s)\"requires\"\\s*:\\s*\\{[^{}]*\"python_version\"\\s*:\\s*\"(3[.](?:10|11|12|13))\"");

    /** Returns Pipenv facts when Pipfile and its lock have a supported exact runtime. / 当 Pipfile 及其锁具有受支持的精确运行时时返回 Pipenv 事实。 */
    public Optional<String> inspect(Path root) throws IOException {
        String lockFile = "Pipfile.lock";
        Path pipfilePath = root.resolve("Pipfile");
        Path lockPath = root.resolve(lockFile);
        if (!BoundedMetadataInspector.regular(pipfilePath) || !BoundedMetadataInspector.regular(lockPath)) {
            return Optional.empty();
        }
        String pipfile = BoundedMetadataInspector.read(pipfilePath);
        String content = BoundedMetadataInspector.read(lockPath);
        Matcher configuredPython = PIPFILE_PYTHON.matcher(pipfile);
        Matcher lockedPython = LOCK_PYTHON.matcher(content);
        boolean shape = content.matches("(?s).*\\\"_meta\\\"\\s*:\\s*\\{.*")
                && content.matches("(?s).*\\\"default\\\"\\s*:\\s*\\{.*")
                && content.matches("(?s).*\\\"develop\\\"\\s*:\\s*\\{.*")
                && pipfile.matches("(?s).*\\[packages].*")
                && configuredPython.find()
                && lockedPython.find()
                && configuredPython.group(1).equals(lockedPython.group(1))
                && (!content.matches("(?s).*\\\"default\\\"\\s*:\\s*\\{\\s*\\\".*")
                || content.matches("(?s).*\\\"sha256:[0-9a-fA-F]{64}\\\".*"));
        return shape ? Optional.of(lockFile) : Optional.empty();
    }
}
