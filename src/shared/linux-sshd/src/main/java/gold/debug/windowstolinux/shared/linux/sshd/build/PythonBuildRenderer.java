package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders a fixed-lock Python virtual-environment build. / 渲染固定锁文件的 Python 虚拟环境构建。 */
public final class PythonBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.PYTHON_SERVICE; }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.PythonService python)
                || facts.buildTool() != DeploymentBuildTool.PYTHON_VENV) {
            throw new IllegalArgumentException("Python renderer requires reviewed virtual-environment inputs");
        }
        String executable = SafeBuildScriptEnvelope.shellQuote("python" + python.pythonVersion());
        String command = """
                command -v %s >/dev/null
                %s -m venv ./.venv
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
