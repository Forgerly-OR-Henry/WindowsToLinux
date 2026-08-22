package gold.debug.windowstolinux.shared.backup.format;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Strict versioned codec for one complete non-secret configuration member. / 一个完整非秘密配置成员的严格版本化编解码器。 */
public final class BackupConfigurationCodec {
    private static final int MAGIC = 0x57544243;
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /** Encodes all typed fields in canonical entry order. / 以规范条目顺序编码全部类型化字段。 */
    public byte[] write(ConfigurationSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeByte(VERSION);
            text(output, snapshot.applicationId()); output.writeLong(snapshot.revision());
            text(output, snapshot.schemaVersion()); text(output, snapshot.createdAt().toString());
            text(output, snapshot.sha256());
            List<ConfigurationEntry> entries = snapshot.entries().stream()
                    .sorted(Comparator.comparing(ConfigurationEntry::key).thenComparing(value -> value.scope().name()))
                    .toList();
            output.writeInt(entries.size());
            for (ConfigurationEntry entry : entries) {
                text(output, entry.key()); text(output, entry.scope().name());
                switch (entry.value()) {
                    case ConfigurationValue.Text value -> { output.writeByte(1); text(output, value.value()); }
                    case ConfigurationValue.Number value -> { output.writeByte(2); output.writeLong(value.value()); }
                    case ConfigurationValue.Flag value -> { output.writeByte(3); output.writeBoolean(value.value()); }
                }
            }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("backup configuration member exceeds policy");
        return bytes.toByteArray();
    }

    /** Decodes the complete document and revalidates its canonical snapshot digest. / 解码完整文档并重新校验规范快照摘要。 */
    public ConfigurationSnapshot read(byte[] document) throws IOException {
        if (document == null || document.length < 8 || document.length > MAX_BYTES) {
            throw new IOException("backup configuration document length is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("backup configuration document version is unsupported");
            }
            String application = text(input, 128); long revision = input.readLong();
            String schema = text(input, 128); Instant created = Instant.parse(text(input, 128));
            String sha256 = text(input, 64); int count = input.readInt();
            if (count < 1 || count > 4096) throw new IOException("backup configuration entry count is invalid");
            List<ConfigurationEntry> entries = new ArrayList<>(count);
            String previous = null;
            for (int index = 0; index < count; index++) {
                String key = text(input, 64); ConfigurationScope scope = ConfigurationScope.valueOf(text(input, 64));
                String order = key + "\0" + scope.name();
                if (previous != null && previous.compareTo(order) >= 0) {
                    throw new IOException("backup configuration entries are not canonical and unique");
                }
                previous = order;
                ConfigurationValue value = switch (input.readUnsignedByte()) {
                    case 1 -> new ConfigurationValue.Text(text(input, 1024));
                    case 2 -> new ConfigurationValue.Number(input.readLong());
                    case 3 -> new ConfigurationValue.Flag(input.readBoolean());
                    default -> throw new IOException("backup configuration value type is unknown");
                };
                entries.add(new ConfigurationEntry(key, scope, value));
            }
            if (input.read() >= 0) throw new IOException("backup configuration document has trailing bytes");
            return new ConfigurationSnapshot(application, revision, schema, created, entries, sha256);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("backup configuration document is invalid", exception);
        }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }

    private static String text(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum) throw new IOException("backup configuration text length is invalid");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("backup configuration document is truncated");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("backup configuration text is not valid UTF-8", exception);
        }
    }
}
