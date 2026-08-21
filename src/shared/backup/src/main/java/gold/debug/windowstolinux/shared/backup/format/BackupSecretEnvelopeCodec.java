package gold.debug.windowstolinux.shared.backup.format;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/** Strict deterministic codec for encrypted-secret public parameters and ciphertext. / 加密秘密公开参数与密文的严格确定性编解码器。 */
public final class BackupSecretEnvelopeCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(64 * 1024 * 1024).maxDocumentLength(64 * 1024 * 1024).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    /** Encodes one validated envelope. / 编码一个已校验信封。 */
    public byte[] write(BackupSecretEnvelope envelope) throws IOException {
        return MAPPER.writeValueAsBytes(Objects.requireNonNull(envelope, "envelope"));
    }

    /** Decodes one strict envelope without decrypting it. / 在不解密的情况下解码一个严格信封。 */
    public BackupSecretEnvelope read(byte[] document) throws IOException {
        return MAPPER.readValue(Objects.requireNonNull(document, "document"), BackupSecretEnvelope.class);
    }
}
