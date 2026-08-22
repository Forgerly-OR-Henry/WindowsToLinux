package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/** Strict versioned storage codec for one independently reviewed application health check. / 独立审阅整应用健康检查的严格版本化存储编解码器。 */
public final class HealthCheckPersistenceCodec {
    private static final int MAGIC = 0x57544c48;
    private static final int VERSION = 1;
    private static final int MAX_DOCUMENT_BYTES = 65_536;

    /** Encodes one complete health contract without external defaults. / 编码不依赖外部默认值的完整健康契约。 */
    public byte[] write(HealthCheck healthCheck) throws IOException {
        Objects.requireNonNull(healthCheck, "healthCheck");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            switch (healthCheck) {
                case HealthCheck.Http value -> {
                    output.writeByte(1);
                    output.writeUTF(value.endpoint().toASCIIString());
                    output.writeInt(value.expectedStatus());
                    output.writeInt(value.timeoutSeconds());
                }
                case HealthCheck.Tcp value -> {
                    output.writeByte(2);
                    output.writeInt(value.port());
                    output.writeInt(value.timeoutSeconds());
                    output.writeInt(value.stabilitySeconds());
                }
            }
        }
        byte[] document = bytes.toByteArray();
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("application health document exceeds the storage limit");
        }
        return document;
    }

    /** Decodes one complete document and rejects unknown, truncated, or extended payloads. / 解码完整文档并拒绝未知、截断或尾随载荷。 */
    public HealthCheck read(byte[] document) throws IOException {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("application health document size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("application health document header is unsupported");
            }
            HealthCheck healthCheck = switch (input.readUnsignedByte()) {
                case 1 -> new HealthCheck.Http(URI.create(input.readUTF()), input.readInt(), input.readInt());
                case 2 -> new HealthCheck.Tcp(input.readInt(), input.readInt(), input.readInt());
                default -> throw new IOException("application health type is unsupported");
            };
            if (input.read() != -1) {
                throw new IOException("application health document contains trailing data");
            }
            return healthCheck;
        } catch (IllegalArgumentException exception) {
            throw new IOException("application health document violates the typed model", exception);
        }
    }
}
