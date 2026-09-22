package gold.debug.windowstolinux.shared.backup.format;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Strict deterministic codec for encrypted-secret public parameters and ciphertext. / 加密秘密公开参数与密文的严格确定性编解码器。
 */
public final class BackupSecretEnvelopeCodec {
    /**
     * JSON mapper for backup secret envelope codec.
     * <p>备份秘密信封编解码器使用的 JSON 映射器。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(64 * 1024 * 1024)
                    .maxDocumentLength(64 * 1024 * 1024).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    /**
     * Encodes one validated envelope. / 编码一个已校验信封。
     *
     * @param envelope envelope / 信封
     * @return one validated envelope / 一个已校验信封
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] write(BackupSecretEnvelope envelope) throws IOException {
        return MAPPER.writeValueAsBytes(Objects.requireNonNull(envelope, "envelope"));
    }

    /**
     * Decodes one strict envelope without decrypting it. / 在不解密的情况下解码一个严格信封。
     *
     * @param document document / 文档
     * @return one strict envelope without decrypting it / 在不解密的情况下解码一个严格信封
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupSecretEnvelope read(byte[] document) throws IOException {
        return MAPPER.readValue(Objects.requireNonNull(document, "document"), BackupSecretEnvelope.class);
    }
}
