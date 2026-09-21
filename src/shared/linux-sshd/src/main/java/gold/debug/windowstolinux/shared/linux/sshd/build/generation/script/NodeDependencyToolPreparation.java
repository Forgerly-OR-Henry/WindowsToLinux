package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;

/**
 * Prepares the project's exact manager without changing its manifest or lock. / 准备项目固定包管理器，不改声明或锁文件。
 */
final class NodeDependencyToolPreparation {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private NodeDependencyToolPreparation() { }

    /**
     * Renders node dependency tool preparation as text without executing the rendered command.
     * <p>渲染节点依赖工具准备为文本，不执行所渲染命令。
     *
     * @param tool tool / 工具
     * @return render text / 渲染文本
     */
    static String render(DeploymentBuildToolType tool) {
        return """
                manager=%s
                manager_spec="$(node - "$manager" <<'WTL_MANAGER'
                const fs = require('fs');
                const p = JSON.parse(fs.readFileSync('package.json', 'utf8'));
                const name = process.argv[2];
                const declaration = p.packageManager || (name === 'npm' ? '' : null);
                if (declaration === null || (declaration && !new RegExp('^' + name + '@[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:\\\\+sha(?:224|256|384|512)\\\\.[a-f0-9]+)?$').test(declaration))) {
                  console.error('BUILD_REJECT=package-manager-exact-version-required'); process.exit(64);
                }
                const config = fs.existsSync('.npmrc') ? fs.readFileSync('.npmrc', 'utf8') : '';
                if (/^\\s*(use-node-version|manage-package-manager-versions)\\s*=/m.test(config)
                    || p.pnpm && p.pnpm.executionEnv && p.pnpm.executionEnv.nodeVersion) {
                  console.error('BUILD_REJECT=implicit-node-toolchain'); process.exit(64);
                }
                console.log(declaration ? declaration.split('+')[0] : '');
                WTL_MANAGER
                )"
                export COREPACK_ENABLE_AUTO_PIN=0 YARN_IGNORE_PATH=1
                export npm_config_manage_package_manager_versions=false npm_config_engine_strict=true
                if [ -n "$manager_spec" ]; then
                  manager_version="${manager_spec#*@}"
                  manager_root="$PWD/.w2l/package-manager"
                  mkdir -p "$manager_root/bin"
                  package="$manager_spec"
                  if [ "$manager" = yarn ]; then package="@yarnpkg/cli-dist@$manager_version"; fi
                  run npm install --prefix "$manager_root" --registry=https://registry.npmjs.org --ignore-scripts --engine-strict --no-audit --no-fund --bin-links=false --save-exact "$package"
                  case "$manager" in
                    npm) entry=node_modules/npm/bin/npm-cli.js ;;
                    pnpm) entry=node_modules/pnpm/bin/pnpm.cjs ;;
                    yarn) entry=node_modules/@yarnpkg/cli-dist/bin/yarn.js ;;
                  esac
                  test -f "$manager_root/$entry"
                  printf '#!/bin/sh\\nexec node "$(dirname -- "$0")/../%%s" "$@"\\n' "$entry" > "$manager_root/bin/$manager"
                  chmod 755 "$manager_root/bin/$manager"
                  export PATH="$manager_root/bin:$PATH"
                  [ "$("$manager" --version)" = "$manager_version" ]
                  printf 'PACKAGE_MANAGER=%%s\\n' "$manager_spec"
                else
                  npm --version
                fi
                """.formatted(tool.name().toLowerCase(java.util.Locale.ROOT));
    }
}
