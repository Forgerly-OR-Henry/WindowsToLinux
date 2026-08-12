package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;

/** Renders the package-manager-specific portion shared by Node services and built static sites. / 渲染 Node 服务与构建型静态站点共享的包管理器部分。 */
final class NodePackageBuildScript {
    private NodePackageBuildScript() { }

    static String render(DeploymentBuildTool tool, int nodeMajorVersion, boolean staticSite, String outputDirectory) {
        String installAndBuild = switch (tool) {
            case NPM -> "test -f ./package-lock.json\nrun npm ci --ignore-scripts\nrun npm run build";
            case PNPM -> "test -f ./pnpm-lock.yaml\nrun pnpm install --frozen-lockfile --ignore-scripts\nrun pnpm run build";
            case YARN -> "test -f ./yarn.lock\nrun yarn install --immutable --ignore-scripts\nrun yarn run build";
            default -> throw new IllegalArgumentException("Node source requires one fixed package manager");
        };
        String artifact = staticSite
                ? "test -d " + SafeBuildScriptEnvelope.shellQuote("./" + outputDirectory) + "\nprintf 'ARTIFACT=%s\\n' "
                + SafeBuildScriptEnvelope.shellQuote("./" + outputDirectory)
                : "printf 'ARTIFACT=%s\\n' ./package.json";
        return """
                node --version | grep -Eq %s
                %s
                %s
                """.formatted(SafeBuildScriptEnvelope.shellQuote("^v" + nodeMajorVersion + "\\."), installAndBuild, artifact);
    }
}
