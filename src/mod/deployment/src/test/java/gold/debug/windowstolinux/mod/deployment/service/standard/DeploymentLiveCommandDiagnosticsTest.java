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
    void keepsTypedFrameAndWindowFactsIncludingZeroAndNestedProblems() {
        var facts = List.of(
            "context.scriptBytes=16777216",
            "problem[0].context.stdinBytes=0",
            "context.frameWritePhase=NOT_STARTED",
            "problem[0].context.frameWritePhase=WRITING_LENGTH",
            "context.frameWritePhase=WRITING_SCRIPT",
            "context.frameWritePhase=WRITING_STDIN",
            "context.frameWritePhase=FLUSHING",
            "context.frameWritePhase=WRITTEN",
            "context.authorPayloadState=NOT_SENT",
            "problem[0].context.authorPayloadState=MAY_HAVE_BEEN_SENT",
            "context.inputSenderCreated=false",
            "problem[0].context.inputSenderCreated=true",
            "context.channelPeerAssigned=false",
            "problem[0].context.channelPeerAssigned=true",
            "context.remoteWindowOpenMaxSize=4294967295",
            "context.remoteWindowOpenSize=0",
            "context.remoteWindowOpenPacketSize=32768",
            "problem[0].context.remoteWindowFailureMaxSize=4294967295",
            "problem[0].context.remoteWindowFailureSize=0",
            "problem[0].context.remoteWindowFailurePacketSize=999999999999");
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
            "context.transportReadStack[0]=external.PrivateProvider#read:42",
            "context.script=echo private-value",
            "context.stdin=private-value",
            "context.scriptBytes=-1",
            "context.scriptBytes=1234567890123",
            "context.stdinBytes=",
            "problem[0].context.stdinBytes=1 private-value",
            "context.frameWritePhase=WRITING_PRIVATE_VALUE",
            "problem[0].context.frameWritePhase=WRITTEN private-value",
            "context.authorPayloadState=SENT",
            "problem[0].context.authorPayloadState=MAY_HAVE_BEEN_SENT private-value",
            "context.inputSenderCreated=TRUE",
            "problem[0].context.channelPeerAssigned=1",
            "context.remoteWindowOpenMaxSize=-1",
            "context.remoteWindowOpenSize=+0",
            "context.remoteWindowOpenPacketSize=1.0",
            "problem[0].context.remoteWindowFailureMaxSize=1234567890123",
            "problem[0].context.remoteWindowFailureSize=0 private-value",
            "context.remoteWindowFailurePacketSize=UNKNOWN",
            "context.remoteWindowFailureMessage=private-value",
            "context.remoteWindowOpenPeerAddress=private-host:22",
            "context.remoteWindowOpenPath=/private/source");
        assertTrue(DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(String.join("\n", privateFacts)).isEmpty());
    }
}
