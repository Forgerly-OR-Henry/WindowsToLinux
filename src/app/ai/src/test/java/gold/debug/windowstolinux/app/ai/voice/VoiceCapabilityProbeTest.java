package gold.debug.windowstolinux.app.ai.voice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VoiceCapabilityProbeTest {
    @Test
    void fixedHumanSampleIsValidPcmAndComparisonRejectsMissingWords() throws Exception {
        var sample = VoiceCapabilityProbe.create();
        assertEquals(403082, sample.audio().length);
        assertEquals(24000, java.nio.ByteBuffer.wrap(sample.audio()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24));
        assertEquals(VoiceCapabilityProbe.normalize(VoiceCapabilityProbe.EXPECTED), VoiceCapabilityProbe.normalize(
            "ＭＡＮＹ animals, of even complex structure which live parasitically within others are wholly devoid of an alimentary cavity!"));
        assertNotEquals(VoiceCapabilityProbe.normalize(VoiceCapabilityProbe.EXPECTED), VoiceCapabilityProbe.normalize(
            VoiceCapabilityProbe.EXPECTED.replace("wholly ", "")));
        var url = java.net.URI.create("wss://example.test/exact");
        assertEquals(url, VoiceProtocol.transcriptionEndpoint(url));
    }
}
