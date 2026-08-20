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
        String executable = SafeBuildScriptEnvelope.shellQuote("python" + python.pythonVersion());
        String toolCheck = requiredTool == null ? "" : "command -v " + requiredTool + " >/dev/null\n";
        String command = """
                command -v %s >/dev/null
                %s%s -m venv --copies ./.venv
                if [ -L ./.venv/lib64 ]; then
                  test "$(readlink ./.venv/lib64)" = lib
                  rm -- ./.venv/lib64
                fi
                export VIRTUAL_ENV="$PWD/.venv"
                export PATH="$VIRTUAL_ENV/bin:/usr/local/bin:/usr/bin:/bin"
                %s
                test -x ./.venv/bin/python
                test -z "$(find ./.venv -xdev -type l -print -quit)"
                printf 'ARTIFACT=%%s\n' ./.venv
                """.formatted(executable, toolCheck, executable, installCommand);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
