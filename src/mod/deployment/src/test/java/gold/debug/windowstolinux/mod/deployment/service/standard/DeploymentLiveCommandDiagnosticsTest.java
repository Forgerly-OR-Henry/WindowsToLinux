package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class DeploymentLiveCommandDiagnosticsTest {
    @Test
    void keepsTypedSshClosureAndReadFactsIncludingNestedProblems() {
        var facts = List.of(
            "context.transportFailureType=org.apache.sshd.common.SshException",
            "problem[0].context.transportFailureType=-",
            "context.disconnectCode=-1",
            "problem[1].context.disconnectCode=11",
            "context.disconnectInitiatorLocal=false",
            "context.sessionCloseObserved=true",
            "context.transportKeyExchangeState=DONE",
            "context.transportReadCallbacks=206",
            "context.transportReadHandlerActive=false",
            "context.transportReadCompletedAgeMillis=48923",
            "context.transportReadHandlerAgeMillis=UNKNOWN",
            "context.transportReadThreadState=WAITING",
            "context.transportReadStack[0]=org.apache.sshd.common.session.helpers.AbstractSession#handleMessage:42",
            "context.inputState=COMPLETED",
            "context.commandPhase=WAITING_FOR_EXIT",
            "context.elapsedMillis=260512",
            "context.timeoutMillis=1796000");
        assertEquals(facts, DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(String.join("\n", facts)));
    }

    @Test
    void excludesPeerMessagesPathsCredentialsAndMalformedTypedValues() {
        var privateFacts = List.of(
            "context.transportFailureMessage=password=private-value",
            "context.peerAddress=private-host:22",
            "context.transportFailureType=org.apache.sshd.common.SshException: private-message",
            "context.transportFailureType=external.PrivateException",
            "context.disconnectCode=11 private-value",
            "context.disconnectCode=1234567890123",
            "context.disconnectInitiatorLocal=private-value",
            "context.sessionCloseObserved=private-value",
            "context.transportKeyExchangeState=PRIVATE_VALUE",
            "context.transportReadCallbacks=private-value",
            "context.transportReadCallbacks=1234567890123",
            "context.transportReadCompletedAgeMillis=-1",
            "context.transportReadThreadState=private-value",
            "context.transportReadStack[6]=org.apache.sshd.common.Session#read:42",
            "context.transportReadStack[0]=org.apache.sshd.common.Session#read:/private/source.java:42",
            "context.transportReadStack[0]=external.PrivateProvider#read:42");
        assertTrue(DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(String.join("\n", privateFacts)).isEmpty());
    }
}
