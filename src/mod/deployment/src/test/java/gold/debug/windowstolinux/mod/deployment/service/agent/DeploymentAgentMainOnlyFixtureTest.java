package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentAgentMainOnlyFixtureTest {
    private static final String REVISION = "a".repeat(64);

    private static final ServerId SERVER = new ServerId("diagnostic-host");

    private final AtomicBoolean cancelled = new AtomicBoolean();

    private String current = REVISION;

    private final UUID taskId = UUID.randomUUID();

    private final TaskContext task = proxy(TaskContext.class, (p, m, a) -> switch (m.getName()) {
        case "id" -> taskId;
        case "cancelToken" -> (CancelToken) cancelled::get;
        default -> throw new AssertionError(m.getName());
    });

    private final AiService ai = proxy(AiService.class, (p, m, a) -> {
        if (m.getName().equals("configurationRevision")) return current;
        throw new AssertionError("Diagnostic permit cannot call a model");
    });

    private final ModContext owner = proxy(ModContext.class, (p, m, a) -> switch (m.getName()) {
        case "identity" -> new ModIdentity("deployment", "1.0.0", Optional.of("1"));
        case "ai" -> ai;
        default -> throw new AssertionError("Diagnostic permit has no host authority: " + m.getName());
    });

    @Test
    void explicitPermitRejectsForeignOwnerTaskTargetSourceAndConfiguration() {
        var fixture = new DeploymentAgentMainOnlyFixture(owner, SERVER);
        var permit = fixture.bind(owner, task, SERVER, REVISION, REVISION);
        var foreignOwner = proxy(ModContext.class, (p, m, a) -> owner.identity());
        var replacementTask = proxy(TaskContext.class, (p, m, a) -> taskId);
        assertThrows(SecurityException.class, () -> fixture.bind(foreignOwner, task, SERVER, REVISION, REVISION));
        assertThrows(SecurityException.class, () -> permit.verify(foreignOwner, task, SERVER, REVISION, REVISION));
        assertThrows(SecurityException.class, () -> permit.verify(owner, replacementTask, SERVER, REVISION, REVISION));
        assertThrows(SecurityException.class, () -> permit.verify(owner, task, new ServerId("other"), REVISION, REVISION));
        assertThrows(SecurityException.class, () -> permit.verify(owner, task, SERVER, "b".repeat(64), REVISION));
        assertThrows(SecurityException.class, () -> permit.verify(owner, task, SERVER, REVISION, "b".repeat(64)));
        current = "b".repeat(64);
        assertThrows(SecurityException.class, () -> permit.verify(owner, task, SERVER, REVISION, REVISION));
        current = REVISION;
        cancelled.set(true);
        assertThrows(SecurityException.class, () -> permit.verify(owner, task, SERVER, REVISION, REVISION));
        assertEquals(0, fixture.decisions());
    }

    @Test
    void approvalRequestFailsBeforeTransportWhileLanguageRequestContinues() {
        var fixture = new DeploymentAgentMainOnlyFixture(owner, SERVER);
        fixture.beforeRequest(new AiRequest(AiPurpose.LANGUAGE, List.of(), Duration.ofSeconds(1), false));
        assertEquals(0, fixture.approvalAttempts());
        assertThrows(AssertionError.class, () -> fixture.beforeRequest(new AiRequest(AiPurpose.APPROVAL, List.of(), Duration.ofSeconds(1), false)));
        assertEquals(1, fixture.approvalAttempts());
        assertEquals(0, fixture.decisions());
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
