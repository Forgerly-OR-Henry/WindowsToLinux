package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Shares the invariant isolated-venv envelope across named Python architecture renderers. / 在具名 Python 架构 Renderer 间共享不变的隔离 venv 外壳。 */
final class PythonArchitectureBuildRenderer {
    private PythonArchitectureBuildRenderer() { }

    static String render(DeploymentBuildToolType expected, DeploymentProjectFacts facts,
                         DeploymentRuntimeSpecification runtime, RemoteWorkspace workspace,
                         BuildLimitConfiguration limits, String requiredTool, String installCommand) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.PythonService python) || facts.buildTool() != expected) {
            throw new IllegalArgumentException("Python architecture renderer requires matching reviewed inputs");
        }
        String executable = "\"${WTL_PYTHON:-python" + python.pythonVersion() + "}\"";
        String toolPreparation = dependencyTool(requiredTool, executable);
        String command = """
                command -v %s >/dev/null
                %s%s -m venv --copies ./.venv
                if [ -L ./.venv/lib64 ]; then
                  test "$(readlink ./.venv/lib64)" = lib
                  rm -- ./.venv/lib64
                fi
                export VIRTUAL_ENV="$PWD/.venv"
                export PATH="$VIRTUAL_ENV/bin:$PATH"
                export UV_PYTHON=%s UV_PYTHON_DOWNLOADS=never UV_NO_MANAGED_PYTHON=1
                export PIPENV_PYTHON="$VIRTUAL_ENV/bin/python" PIPENV_DONT_USE_PYENV=1 PIPENV_DONT_USE_ASDF=1
                export POETRY_VIRTUALENVS_CREATE=false

                %s
                test -x ./.venv/bin/python
                if [ -n "$(find ./.venv -xdev -type l -print -quit)" ]; then
                  printf 'BUILD_REJECT=python-venv-symlink\n'
                  exit 64
                fi
                printf 'ARTIFACT=%%s\n' ./.venv
                """.formatted(executable, toolPreparation, executable, executable, installCommand);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }

    // Build-only managers stay on the bounded volume, outside the application's released venv. / 构建专用管理器留在限容卷，不混入应用发布的 venv。
    private static String dependencyTool(String tool, String python) {
        if (tool == null) return "";
        String version = switch (tool) {
            case "pipenv" -> "2026.8.0";
            case "poetry" -> "2.4.3";
            case "uv" -> "0.12.11";
            default -> throw new IllegalArgumentException("unknown Python dependency tool");
        };
        int minor = tool.equals("uv") ? 8 : 10;
        return """
                if ! command -v %s >/dev/null; then
                  manager_python=%s
                  if ! "$manager_python" -c 'import sys; sys.exit(sys.version_info < (3, %d))'; then
                    manager_python=/usr/bin/python3
                  fi
                  if ! "$manager_python" -c 'import sys; sys.exit(sys.version_info < (3, %d))'; then
                    printf 'BUILD_REJECT=dependency-tool-bootstrap-python-version\n'
                    exit 64
                  fi
                  manager_root="$mutable/home/python-dependency-tool"
                  run "$manager_python" -m venv --copies "$manager_root"
                  run "$manager_root/bin/python" -m pip --isolated --disable-pip-version-check install --index-url=https://pypi.org/simple --only-binary=:all: --no-input --no-cache-dir %s
                  run "$manager_root/bin/python" -c 'import importlib.metadata, sys; assert importlib.metadata.version(sys.argv[1]) == sys.argv[2]' %s %s
                  export PATH="$manager_root/bin:$PATH"
                fi
                %s --version
                """.formatted(tool, python, minor, minor, SafeBuildScriptEnvelope.shellQuote(tool + "==" + version),
                SafeBuildScriptEnvelope.shellQuote(tool), SafeBuildScriptEnvelope.shellQuote(version), tool);
    }
}
