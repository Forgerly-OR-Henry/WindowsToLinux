package gold.debug.windowstolinux.shared.backup.format;

import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Strict codec for immutable configuration and the complete non-secret activation state. / 不可变配置及完整无秘密激活状态的严格编解码器。 */
public final class BackupConfigurationCodec {
    private static final int MAGIC = 0x57544243;
    private static final int INSPECTION_VERSION = 1;
    private static final int ACTIVATION_VERSION = 3;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FILES = 4_096;
    private static final int MAX_DATABASES = 256;

    /** Encodes the historical configuration-only document for compatibility tests and inspection. / 编码历史仅配置文档以供兼容测试及检查。 */
    public byte[] write(ConfigurationSnapshot snapshot) throws IOException {
        return encode(INSPECTION_VERSION, output -> writeSnapshot(output, snapshot));
    }

    /** Encodes every exact non-secret input required by automatic activation and later backup. / 编码自动激活及后续备份所需的全部精确无秘密输入。 */
    public byte[] writeActivation(BackupConfigurationDocument document) throws IOException {
        return encode(ACTIVATION_VERSION, output -> {
            writeSnapshot(output, document.configuration());
            writeResources(output, document.resourceBindings());
            writeRuntime(output, document.runtimeConfiguration());
        });
    }

    /** Decodes the immutable configuration from either supported document version. / 从任一受支持文档版本解码不可变配置。 */
    public ConfigurationSnapshot read(byte[] document) throws IOException {
        return parse(document, false).configuration();
    }

    /** Decodes a complete activation document and rejects historical configuration-only members. / 解码完整激活文档并拒绝历史仅配置成员。 */
    public BackupConfigurationDocument readActivation(byte[] document) throws IOException {
        Parsed parsed = parse(document, true);
        return new BackupConfigurationDocument(parsed.configuration(), parsed.resources().orElseThrow(),
                parsed.runtime().orElseThrow());
    }

    private static Parsed parse(byte[] document, boolean activationRequired) throws IOException {
        if (document == null || document.length < 8 || document.length > MAX_BYTES) {
            throw new IOException("backup configuration document length is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC) throw new IOException("backup configuration document magic is unsupported");
            int version = input.readUnsignedByte();
            if (version < INSPECTION_VERSION || version > ACTIVATION_VERSION) {
                throw new IOException("backup configuration document version is unsupported");
            }
            if (activationRequired && version < 2) {
                throw new IOException("backup configuration lacks exact resource and runtime activation state");
            }
            ConfigurationSnapshot snapshot = readSnapshot(input);
            Optional<ManagedComponentResourceBindings> resources = Optional.empty();
            Optional<ManagedApplicationRuntimeConfiguration> runtime = Optional.empty();
            if (version >= 2) {
                resources = Optional.of(readResources(input)); runtime = Optional.of(readRuntime(input, version));
            }
            if (input.read() >= 0) throw new IOException("backup configuration document has trailing bytes");
            return new Parsed(snapshot, resources, runtime);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("backup configuration document is invalid", exception);
        }
    }

    private static byte[] encode(int version, Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeByte(version); writer.write(output);
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("backup configuration member exceeds policy");
        return bytes.toByteArray();
    }

    private static void writeSnapshot(DataOutputStream output, ConfigurationSnapshot snapshot) throws IOException {
        text(output, snapshot.applicationId(), 128); output.writeLong(snapshot.revision());
        text(output, snapshot.schemaVersion(), 128); text(output, snapshot.createdAt().toString(), 128);
        text(output, snapshot.sha256(), 64);
        List<ConfigurationEntry> entries = snapshot.entries().stream()
                .sorted(Comparator.comparing(ConfigurationEntry::key).thenComparing(value -> value.scope().name())).toList();
        if (entries.size() > 4_096) throw new IOException("configuration entry count exceeds policy");
        output.writeInt(entries.size());
        for (ConfigurationEntry entry : entries) {
            text(output, entry.key(), 64); text(output, entry.scope().name(), 64);
            switch (entry.value()) {
                case ConfigurationValue.Text value -> { output.writeByte(1); text(output, value.value(), 1024); }
                case ConfigurationValue.Number value -> { output.writeByte(2); output.writeLong(value.value()); }
                case ConfigurationValue.Flag value -> { output.writeByte(3); output.writeBoolean(value.value()); }
            }
        }
    }

    private static ConfigurationSnapshot readSnapshot(DataInputStream input) throws IOException {
        String application = text(input, 128); long revision = input.readLong();
        String schema = text(input, 128); Instant created = Instant.parse(text(input, 128));
        String sha256 = text(input, 64); int count = bounded(input.readInt(), 1, 4_096, "configuration entry");
        List<ConfigurationEntry> entries = new ArrayList<>(count); String previous = null;
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
        return new ConfigurationSnapshot(application, revision, schema, created, entries, sha256);
    }

    private static void writeResources(DataOutputStream output, ManagedComponentResourceBindings resources)
            throws IOException {
        if (resources.fileBindings().size() > MAX_FILES
                || resources.databaseBindings().orElseThrow().size() > MAX_DATABASES) {
            throw new IOException("backup resource binding count exceeds policy");
        }
        output.writeInt(resources.fileBindings().size());
        for (ManagedFileBinding file : resources.fileBindings()) {
            text(output, file.bindingId(), 63); text(output, file.dataPath().path(), 255);
            output.writeByte(file.dataPath().access() == ComponentDataPath.AccessMode.READ_ONLY ? 1 : 2);
            text(output, file.dataPath().schemaId(), 128); output.writeBoolean(file.dataPath().reversible());
        }
        output.writeInt(resources.databaseBindings().orElseThrow().size());
        for (ManagedDatabaseBinding database : resources.databaseBindings().orElseThrow()) {
            text(output, database.databaseId(), 63); output.writeByte(switch (database.connection().engine()) {
                case SQLITE -> 1;
                case POSTGRESQL -> 2;
                case MYSQL -> 3;
                case MARIADB -> 4;
                case REDIS -> throw new IOException("complete Redis backup is unsupported");
            });
            if (database.connection() instanceof ManagedDatabaseConnection.Sqlite sqlite) {
                text(output, sqlite.fileName(), 128);
            } else {
                ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) database.connection();
                text(output, server.host(), 253); output.writeInt(server.port()); text(output, server.database(), 128);
                text(output, server.username(), 128); text(output, server.passwordReference().identifier(), 64);
                output.writeLong(server.passwordReference().revision()); output.writeBoolean(server.tlsRequired());
            }
        }
    }

    private static ManagedComponentResourceBindings readResources(DataInputStream input) throws IOException {
        int fileCount = bounded(input.readInt(), 0, MAX_FILES, "file binding");
        List<ManagedFileBinding> files = new ArrayList<>(fileCount);
        for (int index = 0; index < fileCount; index++) {
            String id = text(input, 63); String path = text(input, 255);
            ComponentDataPath.AccessMode access = switch (input.readUnsignedByte()) {
                case 1 -> ComponentDataPath.AccessMode.READ_ONLY;
                case 2 -> ComponentDataPath.AccessMode.READ_WRITE;
                default -> throw new IOException("backup file binding access mode is unsupported");
            };
            String schema = text(input, 128); boolean reversible = input.readBoolean();
            files.add(new ManagedFileBinding(id, new ComponentDataPath(path, access, schema, reversible)));
        }
        int databaseCount = bounded(input.readInt(), 0, MAX_DATABASES, "database binding");
        List<ManagedDatabaseBinding> databases = new ArrayList<>(databaseCount);
        for (int index = 0; index < databaseCount; index++) {
            String id = text(input, 63);
            ManagedDatabaseEngineType engine = switch (input.readUnsignedByte()) {
                case 1 -> ManagedDatabaseEngineType.SQLITE;
                case 2 -> ManagedDatabaseEngineType.POSTGRESQL;
                case 3 -> ManagedDatabaseEngineType.MYSQL;
                case 4 -> ManagedDatabaseEngineType.MARIADB;
                default -> throw new IOException("backup database engine is unsupported");
            };
            ManagedDatabaseConnection connection = engine == ManagedDatabaseEngineType.SQLITE
                    ? new ManagedDatabaseConnection.Sqlite(text(input, 128))
                    : new ManagedDatabaseConnection.Server(engine, text(input, 253), input.readInt(),
                    text(input, 128), text(input, 128), new SecretReference(text(input, 64), input.readLong()),
                    input.readBoolean());
            databases.add(new ManagedDatabaseBinding(id, connection));
        }
        ManagedComponentResourceBindings result = new ManagedComponentResourceBindings(files, Optional.of(databases));
        if (!result.fileBindings().equals(files) || !result.databaseBindings().orElseThrow().equals(databases)) {
            throw new IOException("backup resource bindings are not canonical");
        }
        return result;
    }

    private static void writeRuntime(DataOutputStream output, ManagedApplicationRuntimeConfiguration runtime)
            throws IOException {
        text(output, runtime.identityPolicy().name(), 64);
        switch (runtime.healthCheck()) {
            case HealthCheck.Http health -> {
                output.writeByte(1); text(output, health.endpoint().toString(), 2_048);
                output.writeInt(health.expectedStatus()); output.writeInt(health.timeoutSeconds());
            }
            case HealthCheck.Tcp health -> {
                output.writeByte(2); output.writeInt(health.port()); output.writeInt(health.timeoutSeconds());
                output.writeInt(health.stabilitySeconds());
            }
        }
        output.writeBoolean(runtime.userAccessUrl().isPresent());
        if (runtime.userAccessUrl().isPresent()) {
            text(output, runtime.userAccessUrl().orElseThrow().url().toString(), 2_048);
        }
    }

    private static ManagedApplicationRuntimeConfiguration readRuntime(DataInputStream input, int version) throws IOException {
        RuntimeIdentityMode policy = version >= 3 ? RuntimeIdentityMode.valueOf(text(input, 64))
                : RuntimeIdentityMode.LEGACY_UNSPECIFIED;
        HealthCheck health = switch (input.readUnsignedByte()) {
            case 1 -> new HealthCheck.Http(URI.create(text(input, 2_048)), input.readInt(), input.readInt());
            case 2 -> new HealthCheck.Tcp(input.readInt(), input.readInt(), input.readInt());
            default -> throw new IOException("backup runtime health type is unsupported");
        };
        Optional<UserAccessUrl> access = input.readBoolean()
                ? Optional.of(new UserAccessUrl(URI.create(text(input, 2_048)))) : Optional.empty();
        return new ManagedApplicationRuntimeConfiguration(health, access, policy);
    }

    private static int bounded(int value, int minimum, int maximum, String field) throws IOException {
        if (value < minimum || value > maximum) throw new IOException(field + " count is invalid");
        return value;
    }

    private static void text(DataOutputStream output, String value, int maximum) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maximum) {
            throw new IOException("backup configuration text length exceeds policy");
        }
        output.writeInt(bytes.length); output.write(bytes);
    }

    private static String text(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum) throw new IOException("backup configuration text length is invalid");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("backup configuration document is truncated");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("backup configuration text is not valid UTF-8", exception);
        }
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream output) throws IOException; }
    private record Parsed(ConfigurationSnapshot configuration,
                          Optional<ManagedComponentResourceBindings> resources,
                          Optional<ManagedApplicationRuntimeConfiguration> runtime) { }
}
