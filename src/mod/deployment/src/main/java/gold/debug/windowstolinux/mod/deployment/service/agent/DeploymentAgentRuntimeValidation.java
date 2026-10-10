package gold.debug.windowstolinux.mod.deployment.service.agent;

import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;

/** Exact candidate invocation, without claiming deployment environment or published health. */
final class DeploymentAgentRuntimeValidation {
    private DeploymentAgentRuntimeValidation() {
    }

    static String inapplicable(DeploymentAgentPlan.Part part) {
        var runtime = part.runtime();
        var workload = runtime.workload();
        if (!(runtime instanceof DeploymentRuntimeDefinition.ManagedProcess)
            || workload.mode() != DeploymentWorkload.ExecutionMode.ON_DEMAND
            || !(runtime.healthCheck() instanceof DeploymentHealthCheck.Command))
            return "Only direct ManagedProcess ON_DEMAND COMMAND verification has candidate semantics";
        var health = (DeploymentHealthCheck.Command) runtime.healthCheck();
        if (health.command().entrypoint().isEmpty()
            || !workload.verification().equals(java.util.Optional.of(health.command()))
            || !workload.expectedOutput().equals(health.expectedOutput()))
            return "A nonempty direct entrypoint and matching explicit verification are required; no interpreter or primary command is inferred";
        if (!part.configuration().isEmpty() || !part.secretReferences().isEmpty()
            || !workload.inputs().isEmpty() || !workload.companions().isEmpty() || !workload.workers().isEmpty()
            || !part.resources().fileBindings().isEmpty()
            || part.resources().databaseBindings().map(bindings -> !bindings.isEmpty()).orElse(true)
            || part.databaseIds().map(ids -> !ids.isEmpty()).orElse(true))
            return "Deployment configuration, secrets, inputs or resource bindings cannot be reproduced by this candidate invocation";
        if (render(part).length() > 24000)
            return "The exact quoted invocation exceeds the existing candidate command budget; published health remains required";
        return "";
    }

    static String script(DeploymentAgentPlan.Part part) {
        String reason = inapplicable(part);
        if (!reason.isEmpty()) throw new IllegalArgumentException("RUNTIME_VALIDATION_NOT_APPLICABLE: " + reason);
        return render(part);
    }

    private static String render(DeploymentAgentPlan.Part part) {
        var health = (DeploymentHealthCheck.Command) part.runtime().healthCheck();
        var command = health.command();
        var argv = new StringBuilder("\"$WTL_OUTPUT\"/").append(quote(command.entrypoint()));
        for (String argument : command.arguments()) argv.append(' ').append(quote(argument));
        String directory = part.runtime().workload().workingDirectory();
        return "set -euo pipefail\n"
            + "agent_runtime_stdout=$(mktemp \"$WTL_OUTPUT/.w2l-runtime-validation.XXXXXX\")\n"
            + "trap 'rm -f -- \"$agent_runtime_stdout\"' EXIT\n"
            + "set +e\n"
            + "( cd -- \"$WTL_OUTPUT\"/" + quote(directory.isEmpty() ? "." : directory) + " && " + argv + " ) | head -c 65537 >\"$agent_runtime_stdout\"\n"
            + "agent_runtime_status=(\"${PIPESTATUS[@]}\")\n"
            + "set -e\n"
            + "head -c 65536 -- \"$agent_runtime_stdout\"\n"
            + "[ \"$(wc -c <\"$agent_runtime_stdout\")\" -le 65536 ] || { printf '%s\\n' 'RUNTIME_VALIDATION_STDOUT_TOO_LARGE' >&2; exit 65; }\n"
            + "[ \"${agent_runtime_status[0]}\" -eq 0 ] || exit \"${agent_runtime_status[0]}\"\n"
            + "[ \"${agent_runtime_status[1]}\" -eq 0 ] || exit 64\n"
            + "agent_runtime_observed=$(cat -- \"$agent_runtime_stdout\")\n"
            + "case \"$agent_runtime_observed\" in *" + quote(health.expectedOutput()) + "*) ;; *) printf '%s\\n' 'RUNTIME_VALIDATION_EXPECTED_STDOUT_MISSING' >&2; exit 65 ;; esac\n";
    }

    private static String quote(String value) {
        return SafeBuildScriptEnvelope.shellQuote(value);
    }
}
