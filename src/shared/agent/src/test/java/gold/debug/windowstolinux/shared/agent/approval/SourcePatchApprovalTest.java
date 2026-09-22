package gold.debug.windowstolinux.shared.agent.approval;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.linux.workspace.RemoteSourcePatch;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;
import org.junit.jupiter.api.Test;

class SourcePatchApprovalTest {
    static final String SHA = "a".repeat(64);
    static RemoteSourcePatch patch(String path) {
        return new RemoteSourcePatch(path, SHA, SHA,
                List.of(new RemoteSourcePatch.Edit(1, List.of("old"), List.of("new", "extra"))));
    }

    @Test
    void manualConfirmsEveryChangedLineWhileFullControlNeverAsks() throws Exception {
        for (var mode : List.of(AgentApprovalMode.MANUAL_REVIEW, AgentApprovalMode.FULL_CONTROL)) {
            var calls = new ArrayList<String>();
            var port = port(mode, calls);
            assertEquals(patch("src/main.zig").binding(), port.approve(patch("src/main.zig"), SHA));
            assertEquals(mode == AgentApprovalMode.MANUAL_REVIEW ? 3 : 0, calls.size());
            assertTrue(calls.stream().allMatch(k -> k.equals("deployment.agent.sourceLine")));
        }
    }

    @Test
    void automaticConsentIsTaskScopedAndHighRiskStillAsks() throws Exception {
        var calls = new ArrayList<String>();
        var port = port(AgentApprovalMode.AUTOMATIC, calls);
        port.approve(patch("src/main.zig"), SHA);
        port.approve(patch("src/other.zig"), SHA);
        port.approve(patch("src/auth.zig"), SHA);
        assertEquals(List.of("deployment.agent.sourceAuthorization", "deployment.agent.sourceHighRisk"), calls);
    }

    @Test
    void staleRevisionProtectedFilesAndTraversalCannotBeApproved() {
        var calls = new ArrayList<String>();
        var port = port(AgentApprovalMode.FULL_CONTROL, calls);
        assertThrows(SecurityException.class, () -> port.approve(patch("main.zig"), "b".repeat(64)));
        assertThrows(SecurityException.class, () -> port.approve(patch(".env"), SHA));
        assertThrows(IllegalArgumentException.class, () -> patch("../outside"));
        assertTrue(calls.isEmpty());
    }

    @Test
    void reviewerFailureNeverFallsBackToHumanOrExecution() {
        var port = new SourcePatchApproval("task", "host", AgentApprovalMode.FULL_CONTROL, (g, a, r) -> {
            throw new IllegalStateException("offline");
        }, (k, d) -> {
            fail();
            return true;
        }, e -> {
        }, new AgentTaskControl(s -> {
        }), () -> "v1");
        assertThrows(IllegalStateException.class, () -> port.approve(patch("main.zig"), SHA));
    }

    @Test
    void changedModelConfigurationInvalidatesPendingPatchAndConsentDoesNotCrossTasks() {
        var revision = new java.util.concurrent.atomic.AtomicReference<>("v1");
        var events = new ArrayList<Map<String, String>>();
        var port = new SourcePatchApproval("task", "host", AgentApprovalMode.MANUAL_REVIEW, (g, a,
                r) -> new AgentReview(AgentReviewDecision.ALLOW, r, a.binding(), "reviewed", List.of("sourceRevision")),
                (k, d) -> {
                    revision.set("v2");
                    return true;
                }, events::add, new AgentTaskControl(s -> {
                }), revision::get);
        assertThrows(SecurityException.class, () -> port.approve(patch("main.zig"), SHA));
        assertTrue(events.stream().noneMatch(e -> e.get("type").equals("PATCH_APPROVED")));
        var confirmations = new ArrayList<String>();
        assertDoesNotThrow(() -> port(AgentApprovalMode.AUTOMATIC, confirmations).approve(patch("one.zig"), SHA));
        assertDoesNotThrow(() -> port(AgentApprovalMode.AUTOMATIC, confirmations).approve(patch("two.zig"), SHA));
        assertEquals(List.of("deployment.agent.sourceAuthorization", "deployment.agent.sourceAuthorization"),
                confirmations);
    }

    private SourcePatchApproval port(AgentApprovalMode mode, List<String> calls) {
        return new SourcePatchApproval("task", "host", mode, (g, a, r) -> new AgentReview(AgentReviewDecision.ALLOW, r,
                a.binding(), "reviewed", List.of("sourceRevision")), (k, d) -> {
                    calls.add(k);
                    return true;
                }, e -> {
                }, new AgentTaskControl(s -> {
                }), () -> "v1");
    }
}
