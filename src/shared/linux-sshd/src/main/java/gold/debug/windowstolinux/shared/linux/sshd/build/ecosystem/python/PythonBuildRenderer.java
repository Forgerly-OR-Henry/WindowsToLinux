package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders a fixed-lock Python virtual-environment build. / 渲染固定锁文件的 Python 虚拟环境构建。 */
public final class PythonBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }

    /** Renders the controlled output. / 渲染受控输出。 */
    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.PythonService python)
                || facts.buildTool() != DeploymentBuildToolType.PYTHON_VENV) {
            throw new IllegalArgumentException("Python renderer requires reviewed virtual-environment inputs");
        }
        String executable = SafeBuildScriptEnvelope.shellQuote("python" + python.pythonVersion());
        String command = """
                command -v %s >/dev/null
                %s -m venv --copies ./.venv
                if [ -L ./.venv/lib64 ]; then
                  test "$(readlink ./.venv/lib64)" = lib
                  rm -- ./.venv/lib64
                fi
                test -z "$(find ./.venv -xdev -type l -print -quit)"
                if [ -f ./requirements.lock ]; then
                  run ./.venv/bin/python -m pip install --disable-pip-version-check --require-hashes -r ./requirements.lock
                elif [ -f ./poetry.lock ]; then
                  command -v poetry >/dev/null
                  POETRY_VIRTUALENVS_IN_PROJECT=true run poetry install --only main --sync --no-root
                elif [ -f ./uv.lock ]; then
                  command -v uv >/dev/null
                  run uv sync --frozen --no-dev
                elif [ -f ./Pipfile.lock ]; then
                  command -v pipenv >/dev/null
                  PIPENV_VENV_IN_PROJECT=1 run pipenv sync --deploy
                else
                  exit 64
                fi
                test -x ./.venv/bin/python
                printf 'ARTIFACT=%%s\n' ./.venv
                """.formatted(executable, executable);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
