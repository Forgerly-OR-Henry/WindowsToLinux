package gold.debug.windowstolinux.shared.backup.manifest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.Objects;

/** Strict and deterministic JSON codec for {@code manifest.json}. / 用于 {@code manifest.json} 的严格确定性 JSON 编解码器。 */
public final class BackupManifestCodec {
    private static final ObjectMapper MAPPER = createMapper();

    /** Encodes one validated manifest as deterministic UTF-8 JSON. / 将已校验清单编码为确定性 UTF-8 JSON。 */
    public byte[] write(BackupManifest manifest) throws IOException {
        return MAPPER.writeValueAsBytes(Objects.requireNonNull(manifest, "manifest"));
    }

    /** Decodes strict UTF-8 JSON and rejects unknown, duplicate or trailing content. / 解码严格 UTF-8 JSON并拒绝未知、重复或尾随内容。 */
    public BackupManifest read(byte[] document) throws IOException {
        Objects.requireNonNull(document, "document");
        return MAPPER.readValue(document, BackupManifest.class);
    }

    /** Encodes the signed payload with an explicit unsigned marker. / 使用显式未签名标记编码签名载荷。 */
    public byte[] signaturePayload(BackupManifest manifest) throws IOException {
        return write(Objects.requireNonNull(manifest, "manifest").withProvenance(BackupProvenance.unsigned()));
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(24).maxStringLength(1_048_576).maxDocumentLength(2_097_152).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
}
