package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.app.service.contract.definition.DeploymentSourceInput;
import gold.debug.windowstolinux.shared.git.GitRemote;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Detects source notation without cloning or sending project content. / 识别源码表示，不克隆仓库或发送项目内容。
 */
public final class SourceSelectionService {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SourceSelectionService() { }

    /**
     * Validates one local directory or a remote within the existing Git URI boundary. / 校验单个本地目录或既有 Git URI 边界内的远端。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved deployment source input / 构造或解析得到的部署源码输入
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static DeploymentSourceInput identify(String input) {
        String value = input.trim();
        if (value.isEmpty() || value.length() > 4096 || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException("select one directory or Git URI");
        if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
        if (value.matches("(?i)^(https|ssh)://.*")) {
            return new DeploymentSourceInput(Optional.empty(), GitRemote.parse(value).location().toString(), 0, "");
        }
        Path path = value.regionMatches(true, 0, "file:", 0, 5) ? Path.of(java.net.URI.create(value)) : Path.of(value);
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("source must be an existing directory");
        return new DeploymentSourceInput(Optional.of(path.toAbsolutePath().normalize()), "", 0, "");
    }
}
