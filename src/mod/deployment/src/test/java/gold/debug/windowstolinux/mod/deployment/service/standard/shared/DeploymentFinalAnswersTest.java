package gold.debug.windowstolinux.mod.deployment.service.standard.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentLaunchSettings;
import org.junit.jupiter.api.Test;

class DeploymentFinalAnswersTest {
    @Test
    void oldBooleanConfirmationRetainsItsActualOutcomeAndUnchangedAnswers() throws Exception {
        var decision = new AtomicReference<>(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(true), Optional.empty()));
        var reviewer = (DeploymentStandardInputs.Reviewer) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{DeploymentStandardInputs.Reviewer.class}, (proxy, method, args) -> {
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                assertEquals("confirm", method.getName());
                return decision.get();
            });
        var supplied = Map.of("web", answer(prompt().defaults()));
        var accepted = reviewer.confirmAnswers(new ServerId("server"), "source", Map.of("web", "archive"), supplied, Map.of(), Optional.empty(), null);
        assertEquals(TaskResult.SUCCEEDED, accepted.outcome());
        assertEquals(supplied, accepted.value().orElseThrow());
        decision.set(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(false), Optional.empty()));
        assertEquals(TaskResult.CANCELLED, reviewer.confirmAnswers(new ServerId("server"), "source", Map.of(), supplied, Map.of(), Optional.empty(), null).outcome());
        decision.set(new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(
            ProblemCode.UNAVAILABLE, "FIXTURE_UNCONFIRMED", "Unconfirmed", Map.of(), Optional.empty()))));
        assertEquals(TaskResult.UNKNOWN, reviewer.confirmAnswers(new ServerId("server"), "source", Map.of(), supplied, Map.of(), Optional.empty(), null).outcome());
    }

    @Test
    void launchEditsRetainDatabaseResourcesRisksAndExactComponentScope() {
        var prompt = prompt();
        var original = Map.of("web", answer(prompt.defaults()));
        var editedValues = DeploymentLaunchSettings.edit(prompt, "9001", "https://app.example.invalid/", "src/start.mjs", "--mode production").defaults();
        var edited = answer(editedValues);
        assertEquals(edited, DeploymentStandardWorkflow.confirmedAnswers(List.of(prompt), original, Map.of("web", edited)).get("web"));
        assertThrows(SecurityException.class, () -> DeploymentStandardWorkflow.confirmedAnswers(List.of(prompt), original, Map.of("other", edited)));
        var changedDatabase = new LinkedHashMap<>(editedValues);
        changedDatabase.put("databaseMode", "SQLITE");
        assertThrows(SecurityException.class, () -> DeploymentStandardWorkflow.confirmedAnswers(List.of(prompt), original, Map.of("web", answer(changedDatabase))));
        var changedConfiguration = new LinkedHashMap<>(editedValues);
        changedConfiguration.put("configuration", "SECRET_REF=changed");
        assertThrows(SecurityException.class, () -> DeploymentStandardWorkflow.confirmedAnswers(List.of(prompt), original, Map.of("web", answer(changedConfiguration))));
        var changedRisk = new DeploymentStandardInputs.Answers(editedValues, edited.files(), edited.limits(), true, false,
            edited.workspaceRequest(), edited.workspaceSelection());
        assertThrows(SecurityException.class, () -> DeploymentStandardWorkflow.confirmedAnswers(List.of(prompt), original, Map.of("web", changedRisk)));
    }

    private static DeploymentStandardInputs.Prompt prompt() {
        return new DeploymentStandardInputs.Prompt("web", "application", DeploymentProjectKind.NODE_SERVICE,
            Map.of("version", "22", "port", "8080", "accessUrl", "http://example.invalid:8080/", "exposure", "EXTERNAL",
                "healthMode", "HTTP", "databaseMode", "NONE", "executionMode", "DAEMON", "configuration", "PORT=8080"), List.of());
    }

    private static DeploymentStandardInputs.Answers answer(Map<String, String> values) {
        return new DeploymentStandardInputs.Answers(values, List.of(), DeploymentConfigLoader.current().build().limits(), false, false);
    }
}
