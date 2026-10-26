package gold.debug.windowstolinux.mod.deployment.service.core.publish;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentPreparationDiagnosticsTest {
    @Test
    void preparationLocationsAreBoundedAndExcludeMessagesFilesAndExternalFrames() {
        var failure = new IllegalStateException("private-message-and-candidate");
        var frames = new java.util.ArrayList<StackTraceElement>();
        frames.add(new StackTraceElement("external.PrivateProvider", "invoke", "private-source-filename", 1));
        frames.add(new StackTraceElement("gold.debug.windowstolinux.mod.deployment.Bad\nOwner", "invoke", "private-source-filename", 2));
        frames.add(new StackTraceElement("gold.debug.windowstolinux.mod.deployment.Safe", "bad\nmethod", "private-source-filename", 3));
        IntStream.range(0, 20).forEach(index -> frames.add(new StackTraceElement(
            "gold.debug.windowstolinux.mod.deployment.file.Archive", "prepare", "private-source-filename", 40 + index)));
        failure.setStackTrace(frames.toArray(StackTraceElement[]::new));
        var facts = DeploymentRefFlow.preparationFailureFacts(failure);
        assertEquals("java.lang.IllegalStateException", facts.get("exceptionType"));
        assertEquals(7, facts.size());
        assertEquals("gold.debug.windowstolinux.mod.deployment.file.Archive#prepare:40", facts.get("exceptionFrame.0"));
        assertEquals("gold.debug.windowstolinux.mod.deployment.file.Archive#prepare:45", facts.get("exceptionFrame.5"));
        assertFalse(facts.toString().contains("private-"));
        assertFalse(facts.toString().contains("PrivateProvider"));
        assertFalse(facts.toString().contains("\n"));
        assertTrue(DeploymentRefFlow.preparationFailureFacts(null).isEmpty());
    }
}
