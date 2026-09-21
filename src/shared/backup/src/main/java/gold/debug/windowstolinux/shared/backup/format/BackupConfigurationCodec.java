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

/**
 * Strict codec for immutable configuration and the complete non-secret activation state. / 不可变配置及完整无秘密激活状态的严格编解码器。
 */
public final class BackupConfigurationCodec {
    /**
     * MAGIC.
     * <p>格式标记。
     */
    private static final int MAGIC = 0x57544243;
    /**
     * INSPECTION VERSION.
     * <p>检查版本。
     */
    private static final int INSPECTION_VERSION = 1;
    /**
     * ACTIVATION VERSION.
     * <p>激活版本。
     */
    private static final int ACTIVATION_VERSION = 5;
    /**
     * MAX BYTES.
     * <p>最大字节。
     */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /**
     * MAX FILES.
     * <p>最大文件集合。
     */
    private static final int MAX_FILES = 4_096;
    /**
     * MAX DATABASES.
     * <p>最大数据库集合。
     */
    private static final int MAX_DATABASES = 256;

    /**
     * Encodes the historical configuration-only document for compatibility tests and inspection. / 编码历史仅配置文档以供兼容测试及检查。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @return the historical configuration-only document for compatibility tests and inspection / 历史仅配置文档以供兼容测试及检查
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public byte[] write(ConfigurationSnapshot snapshot) throws IOException {
        return encode(INSPECTION_VERSION, output -> writeSnapshot(output, snapshot));
    }

    /**
     * Encodes every exact non-secret input required by automatic activation and later backup. / 编码自动激活及后续备份所需的全部精确无秘密输入。
     *
     * @param document document / 文档
     * @return every exact non-secret input required by automatic activation and later backup / 自动激活及后续备份所需的全部精确无秘密输入
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public byte[] writeActivation(BackupConfigurationDocument document) throws IOException {
        return encode(ACTIVATION_VERSION, output -> {
            writeSnapshot(output, document.configuration());
            writeResources(output, document.resourceBindings());
            writeRuntime(output, document.runtimeConfiguration());
        });
    }

    /**
     * Decodes the immutable configuration from either supported document version. / 从任一受支持文档版本解码不可变配置。
     *
     * @param document document / 文档
     * @return the immutable configuration from either supported document version / 从任一受支持文档版本解码不可变配置
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ConfigurationSnapshot read(byte[] document) throws IOException {
        return parse(document, false).configuration();
    }

    /**
     * Decodes a complete activation document and rejects historical configuration-only members. / 解码完整激活文档并拒绝历史仅配置成员。
     *
     * @param document document / 文档
     * @return a complete activation document and rejects historical configuration-only members / 完整激活文档并拒绝历史仅配置成员
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public BackupConfigurationDocument readActivation(byte[] document) throws IOException {
        Parsed parsed = parse(document, true);
        return new BackupConfigurationDocument(parsed.configuration(), parsed.resources().orElseThrow(),
                parsed.runtime().orElseThrow());
    }

    /**
     * Decodes a bounded versioned configuration document and rejects invalid fields or trailing content.
     * <p>解码有界且带版本的配置文档，并拒绝无效字段或尾随内容。
     *
     * @param document document / 文档
     * @param activationRequired activation required / 激活必需
     * @return decoded versioned application configuration and resource records / 解码后的带版本应用配置及资源记录
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Parsed parse(byte[] document, boolean activationRequired) throws IOException {
        if (document == null || document.length < 8 || document.length > MAX_BYTES) {
            throw new IOException("backup configuration document length is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC) throw new IOException("backup configuration document magic is unsupported");
            int version = input.readUnsignedByte();
            if (version != INSPECTION_VERSION && version != ACTIVATION_VERSION) {
                throw new IOException("backup configuration document version is unsupported");
            }
            if (activationRequired && version != ACTIVATION_VERSION) {
                throw new IOException("backup configuration lacks exact resource and runtime activation state");
            }
            ConfigurationSnapshot snapshot = readSnapshot(input);
            Optional<ManagedComponentResourceBindings> resources = Optional.empty();
            Optional<ManagedApplicationRuntimeConfiguration> runtime = Optional.empty();
            if (version == ACTIVATION_VERSION) {
                resources = Optional.of(readResources(input)); runtime = Optional.of(readRuntime(input));
            }
            if (input.read() >= 0) throw new IOException("backup configuration document has trailing bytes");
            return new Parsed(snapshot, resources, runtime);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("backup configuration document is invalid", exception);
        }
    }

    /**
     * Encodes backup configuration.
     * <p>编码备份配置。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param writer writer / 写入器
     * @return backup configuration / 备份配置
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static byte[] encode(int version, Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeByte(version); writer.write(output);
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("backup configuration member exceeds policy");
        return bytes.toByteArray();
    }

    /**
     * Writes the configuration revision and canonical typed entries into the bounded binary backup document.
     * <p>将配置修订及规范类型化条目写入有界二进制备份文档。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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

    /**
     * Reconstructs the immutable configuration revision from bounded typed binary entries.
     * <p>根据有界类型化二进制条目重建不可变配置修订。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved configuration snapshot / 构造或解析得到的配置快照
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static ConfigurationSnapshot readSnapshot(DataInputStream input) throws IOException {
        String application = text(input, 128); long revision = input.readLong();
        String schema = text(input, 128); Instant created = Instant.parse(text(input, 128));
        String sha256 = text(input, 64); int count = bounded(input.readInt(), 0, 4_096, "configuration entry");
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

    /**
     * Serializes bounded file, configuration and database bindings while preserving the distinction between unreviewed and reviewed-empty database scope.
     * <p>序列化有界文件、配置及数据库绑定，并保留数据库范围未审阅与已审阅为空的区别。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeResources(DataOutputStream output, ManagedComponentResourceBindings resources)
            throws IOException {
        if (resources.fileBindings().size() > MAX_FILES
                || resources.databaseBindings().orElseThrow().size() > MAX_DATABASES) {
            throw new IOException("backup resource binding count exceeds policy");
        }
        output.writeInt(resources.fileBindings().size());
        for (ManagedFileBinding file : resources.fileBindings()) {
            text(output, file.bindingId(), 63); text(output, file.dataPath().path(), 512);
            output.writeByte(file.dataPath().access() == ComponentDataPath.AccessMode.READ_ONLY ? 1 : 2);
            text(output, file.dataPath().schemaId(), 128); output.writeBoolean(file.dataPath().reversible());
            text(output, file.location().type().name(), 32); text(output, file.location().path().isEmpty() ? "-" : file.location().path(), 512);
            text(output, file.resourceType().name(), 32);
            text(output,file.seedFile().isEmpty() ? "-" : file.seedFile(),512); text(output,file.contentSha256().isEmpty() ? "-" : file.contentSha256(),64);
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
                text(output, sqlite.location().type().name(), 32); text(output, sqlite.location().path().isEmpty() ? "-" : sqlite.location().path(), 512);
                text(output, sqlite.accessPath().isEmpty() ? "-" : sqlite.accessPath(), 512);
                text(output, sqlite.seedFile().isEmpty() ? "-" : sqlite.seedFile(), 512);
                output.writeInt(sqlite.initializationFiles().size());
                for (String file : sqlite.initializationFiles()) text(output,file,512);
            } else {
                ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) database.connection();
                text(output, server.host(), 253); output.writeInt(server.port()); text(output, server.database(), 128);
                text(output, server.username(), 128); text(output, server.passwordReference().identifier(), 64);
                output.writeLong(server.passwordReference().revision()); output.writeBoolean(server.tlsRequired());
            }
        }
    }

    /**
     * Decodes bounded resource bindings and preserves explicit review state without inventing missing bindings.
     * <p>解码有界资源绑定并保留显式审阅状态，不推断缺失绑定。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return bounded resource bindings and preserves explicit review state without inventing missing bindings / 有界资源绑定并保留显式审阅状态，不推断缺失绑定
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static ManagedComponentResourceBindings readResources(DataInputStream input) throws IOException {
        int fileCount = bounded(input.readInt(), 0, MAX_FILES, "file binding");
        List<ManagedFileBinding> files = new ArrayList<>(fileCount);
        for (int index = 0; index < fileCount; index++) {
            String id = text(input, 63); String path = text(input, 512);
            ComponentDataPath.AccessMode access = switch (input.readUnsignedByte()) {
                case 1 -> ComponentDataPath.AccessMode.READ_ONLY;
                case 2 -> ComponentDataPath.AccessMode.READ_WRITE;
                default -> throw new IOException("backup file binding access mode is unsupported");
            };
            String schema = text(input, 128); boolean reversible = input.readBoolean();
            var locationType = gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.valueOf(text(input, 32));
            String locationPath = text(input, 512);
            var location = new gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation(locationType, locationPath.equals("-") ? "" : locationPath);
            var resourceType = gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.valueOf(text(input,32));
            String seed = text(input,512), digest = text(input,64);
            files.add(new ManagedFileBinding(id, new ComponentDataPath(path, access, schema, reversible), location,
                    resourceType,seed.equals("-") ? "" : seed, digest.equals("-") ? "" : digest));
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
                    ? readSqlite(input)
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

    /**
     * Reads sqlite.
     * <p>读取SQLite 数据库。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return sqlite / SQLite 数据库
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static ManagedDatabaseConnection.Sqlite readSqlite(DataInputStream input) throws IOException {
        String file = text(input,128), type = text(input,32), path = text(input,512), access = text(input,512), seed = text(input,512);
        int size = input.readInt(); if (size < 0 || size > 32) throw new IOException("invalid initialization file count");
        var files = new java.util.ArrayList<String>(); for (int i=0; i<size; i++) files.add(text(input,512));
        return new ManagedDatabaseConnection.Sqlite(file, new gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation(
                gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.valueOf(type), path.equals("-") ? "" : path),
                access.equals("-") ? "" : access, seed.equals("-") ? "" : seed, files);
    }

    /**
     * Writes reviewed language, process and health specification.
     * <p>写入已审阅的语言、进程及健康规格。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeRuntime(DataOutputStream output, ManagedApplicationRuntimeConfiguration runtime)
            throws IOException {
        byte[] payload = new gold.debug.windowstolinux.shared.config.persistence.serialization.ApplicationRuntimeConfigurationCodec().write(runtime);
        output.writeInt(payload.length); output.write(payload);
    }

    /**
     * Reads reviewed language, process and health specification.
     * <p>读取已审阅的语言、进程及健康规格。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static ManagedApplicationRuntimeConfiguration readRuntime(DataInputStream input) throws IOException {
        int length = bounded(input.readInt(), 1, 1_048_576, "runtime payload");
        return new gold.debug.windowstolinux.shared.config.persistence.serialization.ApplicationRuntimeConfigurationCodec().read(input.readNBytes(length));
    }

    /**
     * Rejects content exceeding the explicit size or count bound.
     * <p>拒绝超出显式大小或数量限制的内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param minimum minimum / 最小
     * @param maximum maximum / 最大
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return bounded as a numeric result / 有界的数值结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static int bounded(int value, int minimum, int maximum, String field) throws IOException {
        if (value < minimum || value > maximum) throw new IOException(field + " count is invalid");
        return value;
    }

    /**
     * Writes length-prefixed UTF-8 text within the binary format bound.
     * <p>在二进制格式边界内写入带长度前缀的 UTF-8 文本。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param maximum maximum / 最大
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void text(DataOutputStream output, String value, int maximum) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maximum) {
            throw new IOException("backup configuration text length exceeds policy");
        }
        output.writeInt(bytes.length); output.write(bytes);
    }

    /**
     * Reads bounded length-prefixed UTF-8 text from the binary document.
     * <p>从二进制文档读取有界且带长度前缀的 UTF-8 文本。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param maximum maximum / 最大
     * @return bounded length-prefixed UTF-8 text from the binary document / 从二进制文档读取有界且带长度前缀的 UTF-8 文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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

    /**
     * Writes one bounded portion of the versioned backup configuration stream.
     * <p>写入版本化备份配置流中的一个有界部分。
     */
    @FunctionalInterface
    private interface Writer {
    /**
     * Writes writer.
     * <p>写入写入器。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
     void write(DataOutputStream output) throws IOException; }
    /**
     * Holds decoded backup configuration fields before constructing the validated document.
     * <p>在构造已验证文档前保存解码后的备份配置字段。
     *
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     */
    private record Parsed(ConfigurationSnapshot configuration,
                          Optional<ManagedComponentResourceBindings> resources,
                          Optional<ManagedApplicationRuntimeConfiguration> runtime) { }
}
