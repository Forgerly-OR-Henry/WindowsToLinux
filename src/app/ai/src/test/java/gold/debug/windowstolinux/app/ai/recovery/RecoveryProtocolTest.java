package gold.debug.windowstolinux.app.ai.recovery;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Preserves strict observation and action evidence in the actual platform protocol. / 在真实平台协议保留严格观察与动作依据。 */
class RecoveryProtocolTest {
    @Test
    void observationCannotSmuggleActionsAndCommandsNeedStrictSchemas() throws Exception {
        assertThrows(Exception.class, () -> RecoveryProtocol.parseObservation("{\"observation\":\"ok\",\"command\":\"rm\"}"));
        assertThrows(Exception.class, () -> RecoveryProtocol.parseObservation("{\"observation\":\"a\",\"observation\":\"b\"}"));
        assertThrows(Exception.class, () -> RecoveryProtocol.parseObservation("{\"observation\":\"ok\"} {}"));
        assertThrows(Exception.class, () -> RecoveryProtocol.parseAction("{\"command\":\"id\\nreboot\",\"reason\":\"x\",\"expected\":\"x\",\"highImpact\":false}"));
        var action = RecoveryProtocol.parseAction("{\"command\":\"reboot\",\"reason\":\"x\",\"expected\":\"restart\",\"highImpact\":false}");
        assertTrue(action.highImpact());
        assertFalse(action.toString().contains("reboot"));
    }
}
