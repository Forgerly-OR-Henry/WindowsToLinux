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
                %s
                """.formatted(SafeBuildScriptEnvelope.shellQuote("^v" + nodeMajorVersion + "\\."), installAndBuild,
                normalizeBinaryLinks(), artifact);
    }

    // npm creates executable links even with lifecycle scripts disabled; releases remain free of symlinks.
    // npm 在禁用生命周期脚本时仍创建命令链接；转换后发布目录仍不允许符号链接。
    static String normalizeBinaryLinks() {
        return """
                run python3 - <<'WTL_NODE_BIN_LINKS'
                import os, pathlib, shlex, tempfile
                root = pathlib.Path('node_modules').absolute()
                if root.is_symlink():
                    raise SystemExit('BUILD_REJECT=node-modules-symlink')
                for directory, directories, files in os.walk(root, followlinks=False):
                    for name in directories + files:
                        link = pathlib.Path(directory) / name
                        if not link.is_symlink():
                            continue
                        if link.parent.name != '.bin':
                            continue
                        try:
                            target = link.resolve(strict=True)
                        except (OSError, RuntimeError):
                            raise SystemExit('BUILD_REJECT=node-bin-link-invalid')
                        if not target.is_relative_to(root) or not target.is_file() or not os.access(target, os.X_OK):
                            raise SystemExit('BUILD_REJECT=node-bin-link-outside-or-nonexecutable')
                        relative = os.path.relpath(target, link.parent)
                        wrapper = '#!/bin/sh\\nexec "$(dirname -- "$0")"/' + shlex.quote(relative) + ' "$@"\\n'
                        temporary = None
                        try:
                            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=link.parent, delete=False) as output:
                                temporary = output.name
                                output.write(wrapper)
                            os.chmod(temporary, 0o755)
                            os.replace(temporary, link)
                        finally:
                            if temporary and os.path.exists(temporary):
                                os.unlink(temporary)
                WTL_NODE_BIN_LINKS
                """;
    }
}
