package gold.debug.windowstolinux.app.ai.voice;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

import gold.debug.windowstolinux.appapi.ai.voice.VoiceRequest;

/** Versioned built-in speech challenge shared by both audio checks. / 两种音频核验共用的版本化内置语音题。 */
public final class VoiceCapabilityProbe {
    public static final String EXPECTED = "Many animals of even complex structure which live parasitically within others are wholly devoid of an alimentary cavity.";

    private static final String SHA256 = "d611aad71d60bd50c67ee75923c512db89ea41112f8aff87def6b8fd651a8947";

    private VoiceCapabilityProbe() {
    }

    /** Loads and authenticates bounded JAR resources before any request. / 请求前加载并校验有界JAR资源。 */
    public static VoiceRequest create() throws IOException {
        try (var input = VoiceCapabilityProbe.class.getResourceAsStream("capability-probe-en-v1.wav")) {
            if (input == null) throw new IOException("Built-in voice sample is absent");
            byte[] bytes = input.readNBytes(524289);
            try {
                if (bytes.length > 524288 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(SHA256))
                    throw new IOException("Built-in voice sample integrity differs");
                return new VoiceRequest(bytes, "capability-probe-en-v1.wav", Duration.ofSeconds(30));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    /** Ignores presentation punctuation without accepting missing words. / 忽略排版标点，不容忍缺词。 */
    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("\\p{P}+", " ").replaceAll("[\\p{Z}\\s]+", " ").strip();
    }
}
