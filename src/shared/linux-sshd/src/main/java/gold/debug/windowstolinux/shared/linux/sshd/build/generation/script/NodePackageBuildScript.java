package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

/** Renders the package-manager-specific portion shared by Node services and built static sites. / 渲染 Node 服务与构建型静态站点共享的包管理器部分。 */
public final class NodePackageBuildScript {
    private NodePackageBuildScript() { }

    /** Renders the controlled output. / 渲染受控输出。 */
    public static String render(DeploymentBuildToolType tool, int nodeMajorVersion, boolean staticSite, String outputDirectory) {
        String installAndBuild = switch (tool) {
            case NPM -> "test -f ./package-lock.json\nrun npm ci --ignore-scripts\nrun npm run build";
            case PNPM -> "test -f ./pnpm-lock.yaml\nrun pnpm install --frozen-lockfile --ignore-scripts\nrun pnpm run build";
            case YARN -> "test -f ./yarn.lock\nexport YARN_ENABLE_SCRIPTS=false\n"
                    + "run yarn install --immutable\nrun yarn run build";
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
