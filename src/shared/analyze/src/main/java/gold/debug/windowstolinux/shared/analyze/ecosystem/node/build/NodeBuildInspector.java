package gold.debug.windowstolinux.shared.analyze.ecosystem.node.build;

import gold.debug.windowstolinux.shared.analyze.source.metadata.BoundedMetadataReader;
import gold.debug.windowstolinux.shared.analyze.source.metadata.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;

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

    /** Returns package facts when package.json exists. / 在 package.json 存在时返回包事实。 */
    public Optional<NodeBuildFacts> inspect(Path root) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (!BoundedMetadataReader.regular(packageJson)) {
            return Optional.empty();
        }
        String json = BoundedMetadataReader.read(packageJson);
        List<String> lockFiles = BoundedMetadataReader.existingNames(root,
                "package-lock.json", "pnpm-lock.yaml", "yarn.lock");
        return Optional.of(new NodeBuildFacts(ProjectIdentityResolver.applicationId(root, json, NAME),
                buildTool(lockFiles), lockFiles, hasScript(json, "build"), hasScript(json, "start")));
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

    private static DeploymentBuildTool buildTool(List<String> lockFiles) {
        if (lockFiles.equals(List.of("pnpm-lock.yaml"))) {
            return DeploymentBuildTool.PNPM;
        }
        if (lockFiles.equals(List.of("yarn.lock"))) {
            return DeploymentBuildTool.YARN;
        }
        return DeploymentBuildTool.NPM;
    }
}
