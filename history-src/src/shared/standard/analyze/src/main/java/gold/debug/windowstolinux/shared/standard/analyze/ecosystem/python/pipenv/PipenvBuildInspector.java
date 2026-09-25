package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.python.pipenv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;

/**
 * Inspects the Pipenv lockfile architecture. / 检查 Pipenv 锁文件架构。
 */
public final class PipenvBuildInspector {
    /**
     * Pattern recognizing Python version in Pipfile.
     * <p>用于识别Pipfile 中的 Python 版本的匹配模式。
     */
    private static final Pattern PIPFILE_PYTHON = Pattern
            .compile("(?m)^\\s*python_version\\s*=\\s*\"(3[.](?:10|11|12|13))\"\\s*$");

    /**
     * Pattern recognizing LOCK PYTHON.
     * <p>用于识别锁PYTHON的匹配模式。
     */
    private static final Pattern LOCK_PYTHON = Pattern
            .compile("(?s)\"requires\"\\s*:\\s*\\{[^{}]*\"python_version\"\\s*:\\s*\"(3[.](?:10|11|12|13))\"");

    /**
     * Returns Pipenv facts when Pipfile and its lock have a supported exact runtime. / 当 Pipfile 及其锁具有受支持的精确运行时时返回 Pipenv 事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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
                && content.matches("(?s).*\\\"develop\\\"\\s*:\\s*\\{.*") && pipfile.matches("(?s).*\\[packages].*")
                && configuredPython.find() && lockedPython.find()
                && configuredPython.group(1).equals(lockedPython.group(1))
                && (!content.matches("(?s).*\\\"default\\\"\\s*:\\s*\\{\\s*\\\".*")
                        || content.matches("(?s).*\\\"sha256:[0-9a-fA-F]{64}\\\".*"));
        return shape ? Optional.of(lockFile) : Optional.empty();
    }
}
