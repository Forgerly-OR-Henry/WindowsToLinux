package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.node.npm.NpmBuildInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.node.pnpm.PnpmBuildInspector;
import gold.debug.windowstolinux.shared.standard.analyze.ecosystem.node.yarn.YarnBuildInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.ProjectIdentityResolver;

/**
 * Reads package.json, supported lockfiles, and fixed script names without invoking a package manager.
 *
 *  <p>读取 package.json、受支持锁文件和固定脚本名称，但不调用包管理器。
 */
public final class NodeBuildInspector {
    /**
     * Pattern recognizing NAME.
     * <p>用于识别名称的匹配模式。
     */
    private static final Pattern NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"");

    /**
     * Pattern recognizing SCRIPT.
     * <p>用于识别脚本的匹配模式。
     */
    private static final Pattern SCRIPT = Pattern.compile("\\\"(build|start)\\\"\\s*:\\s*\\\"([^\\\"\\r\\n]+)\\\"");

    /**
     * Bound npm build inspector collaborator for npm.
     * <p>处理npm 构建的Npm构建检查器协作对象。
     */
    private final NpmBuildInspector npm = new NpmBuildInspector();

    /**
     * Bound pnpm build inspector collaborator for pnpm.
     * <p>处理pnpm 构建的Pnpm构建检查器协作对象。
     */
    private final PnpmBuildInspector pnpm = new PnpmBuildInspector();

    /**
     * Bound yarn build inspector collaborator for yarn.
     * <p>处理Yarn 构建的Yarn构建检查器协作对象。
     */
    private final YarnBuildInspector yarn = new YarnBuildInspector();

    /**
     * Returns package facts when package.json exists. / 在 package.json 存在时返回包事实。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Optional<NodeBuildFacts> inspect(Path root) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (!BoundedMetadataInspector.regular(packageJson)) {
            return Optional.empty();
        }
        String json = BoundedMetadataInspector.read(packageJson);
        List<NodeBuildArchitectureFacts> selected = List
                .of(npm.inspect(root).map(lock -> new NodeBuildArchitectureFacts(DeploymentBuildToolType.NPM, lock)),
                        pnpm.inspect(root)
                                .map(lock -> new NodeBuildArchitectureFacts(DeploymentBuildToolType.PNPM, lock)),
                        yarn.inspect(root)
                                .map(lock -> new NodeBuildArchitectureFacts(DeploymentBuildToolType.YARN, lock)))
                .stream().flatMap(Optional::stream).toList();
        List<String> lockFiles = selected.stream().map(NodeBuildArchitectureFacts::lockFile).toList();
        DeploymentBuildToolType buildTool = selected.size() == 1
                ? selected.getFirst().buildTool()
                : DeploymentBuildToolType.NPM;
        return Optional.of(new NodeBuildFacts(ProjectIdentityResolver.applicationId(root, json, NAME), buildTool,
                lockFiles, hasScript(json, "build"), hasScript(json, "start")));
    }

    /**
     * Reports whether the build script path condition holds for this contract.
     * <p>判断当前契约是否满足构建脚本路径条件。
     *
     * @param json JSON serialization / JSON 序列化
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @return true when build script path condition holds for this contract, false otherwise / 当前契约是否满足构建脚本路径条件时为 true，否则为 false
     */
    private static boolean hasScript(String json, String expected) {
        Matcher matcher = SCRIPT.matcher(json);
        while (matcher.find()) {
            if (expected.equals(matcher.group(1)) && !matcher.group(2).isBlank()) {
                return true;
            }
        }
        return false;
    }

}
