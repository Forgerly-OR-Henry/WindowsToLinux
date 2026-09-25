package gold.debug.windowstolinux.shared.deploy.approval;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.linux.command.*;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;
import org.junit.jupiter.api.Test;

class CommandApprovalAuditTest {
    @Test
    void bothEnginesUseIdenticalThreePolicySemanticsAtActualCommandBoundary() {
        for (String engine : List.of("ASSISTED", "AGENT"))
            for (var mode : AgentApprovalMode.values())
                for (var risk : List.of(AgentRiskLevel.NORMAL, AgentRiskLevel.HIGH)) {
                    var human = new AtomicInteger();
                    var ai = new AtomicInteger();
                    var events = new ArrayList<Map<String, String>>();
                    var audit = new CommandApprovalAudit(mode, (g, a, r) -> {
                        ai.incrementAndGet();
                        return allowed(a, r);
                    }, new AgentTaskControl(s -> {
                    }), (request, r) -> {
                        human.incrementAndGet();
                        return true;
                    }, r -> risk, events::add, r -> {
                    });
                    try (var scope = new CommandExecutionScope(engine, "host", () -> "revision", audit)) {
                        var dispatch = CommandExecutionScope
                                .before("host", "helper action", "printf hi", new byte[0], Duration.ofSeconds(10), 4096)
                                .orElseThrow();
                        assertEquals(
                                mode == AgentApprovalMode.MANUAL_REVIEW
                                        || mode == AgentApprovalMode.AUTOMATIC && risk == AgentRiskLevel.HIGH ? 1 : 0,
                                human.get());
                        assertEquals(1, ai.get());
                        assertTrue(events.stream().anyMatch(e -> e.get("type").equals("COMMAND_EXECUTING")));
                        dispatch.completed(new RemoteCommandResult(true, false, "ok", "ok", "", 0));
                        assertThrows(SecurityException.class, () -> audit.before(dispatch.request()));
                    }
                }
    }

    @Test
    void staticDispatchHasNoModelOrApprovalScope() {
        assertTrue(CommandExecutionScope.before("host", "printf hi", "", null, Duration.ofSeconds(10), 4096).isEmpty());
    }

    @Test
    void changedEvidenceAndUnavailableApprovalNeverProduceAnExecutionIntent() {
        var revision = new AtomicReference<>("one");
        var events = new ArrayList<Map<String, String>>();
        var audit = new CommandApprovalAudit(AgentApprovalMode.FULL_CONTROL, (g, a, r) -> {
            revision.set("two");
            return allowed(a, r);
        }, new AgentTaskControl(s -> {
        }), (q, r) -> false, r -> AgentRiskLevel.NORMAL, events::add, r -> {
        });
        try (var scope = new CommandExecutionScope("task", "host", revision::get, audit)) {
            assertThrows(SecurityException.class,
                    () -> CommandExecutionScope.before("host", "true", "", null, Duration.ofSeconds(10), 4096));
            assertTrue(events.stream().noneMatch(e -> e.get("type").equals("COMMAND_EXECUTING")));
            assertThrows(SecurityException.class,
                    () -> CommandExecutionScope.before("other", "true", "", null, Duration.ofSeconds(10), 4096));
        }
        var unavailable = new CommandApprovalAudit(AgentApprovalMode.FULL_CONTROL, (g, a, r) -> {
            throw new IllegalStateException("offline");
        }, new AgentTaskControl(s -> {
        }), (q, r) -> {
            fail();
            return false;
        }, r -> AgentRiskLevel.NORMAL, events::add, r -> {
        });
        try (var scope = new CommandExecutionScope("task", "host", () -> "one", unavailable)) {
            assertThrows(SecurityException.class,
                    () -> CommandExecutionScope.before("host", "true", "", null, Duration.ofSeconds(10), 4096));
        }
    }

    @Test
    void timeoutOrMissingExitBlocksContinuation() {
        var control = new AgentTaskControl(s -> {
        });
        var audit = new CommandApprovalAudit(AgentApprovalMode.FULL_CONTROL, (g, a, r) -> allowed(a, r), control,
                (q, r) -> false, r -> AgentRiskLevel.NORMAL, e -> {
                }, r -> {
                });
        try (var scope = new CommandExecutionScope("task", "host", () -> "one", audit)) {
            var dispatch = CommandExecutionScope.before("host", "build", "", null, Duration.ofSeconds(10), 4096)
                    .orElseThrow();
            assertThrows(IllegalStateException.class,
                    () -> dispatch.completed(new RemoteCommandResult(false, true, "", "", "", null)));
            assertEquals(AgentTaskState.UNKNOWN, control.state());
            assertThrows(IllegalStateException.class,
                    () -> CommandExecutionScope.before("host", "retry", "", null, Duration.ofSeconds(10), 4096));
        }
    }

    private static AgentReview allowed(AgentAction action, AgentRiskLevel risk) {
        return new AgentReview(AgentReviewDecision.ALLOW, risk, action.binding(), "exact command reviewed",
                List.of("scope"));
    }
}
