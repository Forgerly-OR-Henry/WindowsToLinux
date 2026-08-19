package gold.debug.windowstolinux.shared.analyze.ecosystem.node;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.npm.NpmBuildInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.pnpm.PnpmBuildInspector;
import gold.debug.windowstolinux.shared.analyze.ecosystem.node.yarn.YarnBuildInspector;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads package.json, supported lockfiles, and fixed script names without invoking a package manager.
 *
 * <p>读取 package.json、受支持锁文件和固定脚本名称，但不调用包管理器。
 */
public final class NodeBuildInspector {
    private static final Pattern NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([a-z0-9][a-z0-9._-]{0,62})\\\"");
    private static final Pattern SCRIPT = Pattern.compile("\\\"(build|start)\\\"\\s*:\\s*\\\"([^\\\"\\r\\n]+)\\\"");
    private final NpmBuildInspector npm = new NpmBuildInspector();
    private final PnpmBuildInspector pnpm = new PnpmBuildInspector();
    private final YarnBuildInspector yarn = new YarnBuildInspector();

    /** Returns package facts when package.json exists. / 在 package.json 存在时返回包事实。 */
    public Optional<NodeBuildFacts> inspect(Path root) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (!BoundedMetadataInspector.regular(packageJson)) {
            return Optional.empty();
        }
        String json = BoundedMetadataInspector.read(packageJson);
        List<NodeBuildArchitectureFacts> selected = List.of(
                        npm.inspect(root).map(lock -> new NodeBuildArchitectureFacts(DeploymentBuildToolType.NPM, lock)),
                        pnpm.inspect(root).map(lock -> new NodeBuildArchitectureFacts(DeploymentBuildToolType.PNPM, lock)),
                        yarn.inspect(root).map(lock -> new NodeBuildArchitectureFacts(DeploymentBuildToolType.YARN, lock)))
                .stream()
                .flatMap(Optional::stream)
                .toList();
        List<String> lockFiles = selected.stream().map(NodeBuildArchitectureFacts::lockFile).toList();
        DeploymentBuildToolType buildTool = selected.size() == 1
                ? selected.getFirst().buildTool() : DeploymentBuildToolType.NPM;
        return Optional.of(new NodeBuildFacts(ProjectIdentityResolver.applicationId(root, json, NAME),
                buildTool, lockFiles, hasScript(json, "build"), hasScript(json, "start")));
    }

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
