package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;

class ManagedRuntimeObservationTest {
    private final ManagedApplication app = ManagedApplication.forManaged("demo",
            new ServerIdentity("fixture", "127.0.0.1", 22, "SHA256:fixture"), "a".repeat(64));
    private Map<String, String> state(String active, String sub, String result, String status, String pid) {
        return new HashMap<>(Map.of("OWNER", "1", "ENABLED", "disabled", "QUERY_OK", "1", "ActiveState", active,
                "SubState", sub, "Result", result, "ExecMainCode", "1", "ExecMainStatus", status, "MainPID", pid));
    }

    private RuntimeState observe(Map<String, String> values) {
        return ManagedRuntimeProtocolExecutor.nativeObservation(app, values).runtimeState();
    }

    @Test
    void distinguishesHealthyStoppedFailedAndRestartWaiting() {
        assertEquals(RuntimeState.RUNNING, observe(state("active", "running", "success", "0", "123")));
        assertEquals(RuntimeState.STOPPED, observe(state("inactive", "dead", "success", "0", "0")));
        assertEquals(RuntimeState.STOPPED, observe(state("inactive", "dead", "success", "129", "0")));
        for (String status : new String[]{"1", "37", "129"}) {
            var values = state("failed", "failed", "exit-code", status, "0");
            assertEquals(RuntimeState.ERROR, observe(values));
            assertTrue(ManagedRuntimeProtocolExecutor.nativeObservation(app, values).evidence()
                    .contains("ExecMainStatus=" + status));
            assertEquals(RuntimeState.ERROR, observe(state("activating", "auto-restart", "exit-code", status, "0")));
        }
    }

    @Test
    void rejectsIncompleteQueriesLegacyHelperAndTransitions() {
        var values = state("inactive", "dead", "success", "0", "0");
        for (String key : values.keySet()) {
            var missing = new HashMap<>(values);
            missing.remove(key);
            if (!key.equals("ENABLED"))
                assertEquals(RuntimeState.UNKNOWN, observe(missing), key);
            if (!key.equals("OWNER") && !key.equals("ENABLED")) {
                assertTrue(ManagedRuntimeProtocolExecutor.nativeObservation(app, missing).evidence()
                        .contains("environment preparation"), key);
            }
        }
        var old = Map.of("OWNER", "1", "RUNNING", "0", "ENABLED", "disabled");
        assertEquals(RuntimeState.UNKNOWN, observe(old));
        assertTrue(ManagedRuntimeProtocolExecutor.nativeObservation(app, old).evidence()
                .contains("environment preparation"));
        values.put("QUERY_OK", "0");
        assertEquals(RuntimeState.UNKNOWN, observe(values));
        assertEquals(RuntimeState.UNKNOWN, observe(state("deactivating", "stop-sigterm", "success", "0", "123")));
        assertEquals(RuntimeState.UNKNOWN, observe(state("inactive", "dead", "success", "0", "123")));
        assertEquals(RuntimeState.UNKNOWN, observe(state("activating", "auto-restart", "success", "0", "0")));
        assertEquals(RuntimeState.UNKNOWN, observe(state("future-state", "future", "success", "0", "0")));
        values.put("OWNER", "0");
        assertFalse(ManagedRuntimeProtocolExecutor.nativeObservation(app, values).ownershipVerified());
    }

    @Test
    void preservesStopEvidenceButRejectsUncontrolledText() {
        var evidence = ManagedRuntimeProtocolExecutor.stopEvidence(
                "STOP_BEFORE_QUERY_OK=1\nSTOP_BEFORE_Result=exit-code\nSTOP_BEFORE_ExecMainStatus=129\nSTOP_AFTER_ExecMainStatus=129\nSECRET=private\nSTOP_AFTER_Result=private value\n");
        assertTrue(evidence.contains("STOP_BEFORE_ExecMainStatus=129"));
        assertTrue(evidence.contains("STOP_AFTER_ExecMainStatus=129"));
        assertFalse(evidence.contains("private"));
        assertEquals("", ManagedRuntimeProtocolExecutor.stopEvidence("uncontrolled output"));
    }
}
