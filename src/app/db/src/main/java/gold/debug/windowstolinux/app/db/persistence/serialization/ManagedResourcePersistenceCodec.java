package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseEngineType;
import gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strict versioned storage codec for reviewed non-secret managed resource bindings. / 经审阅无秘密受管资源绑定的严格版本化存储编解码器。
 */
public final class ManagedResourcePersistenceCodec {
    /**
     * Reads initialization.
     * <p>读取初始化。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return initialization / 初始化
     * @throws java.io.IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static java.util.List<String> readInitialization(java.io.DataInputStream input) throws java.io.IOException {
        int size = input.readInt(); if (size < 0 || size > 32) throw new java.io.IOException("invalid initialization file count");
        var result = new java.util.ArrayList<String>(); for (int i=0; i<size; i++) result.add(input.readUTF());
        return java.util.List.copyOf(result);
    }
    /**
     * MAGIC.
     * <p>格式标记。
     */
    private static final int MAGIC = 0x57544c52;
    /**
     * VERSION.
     * <p>版本。
     */
    private static final int VERSION = 2;
    /**
     * MAX DOCUMENT BYTES.
     * <p>最大文档字节。
     */
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    /**
     * MAX FILE BINDINGS.
     * <p>最大文件绑定集合。
     */
    private static final int MAX_FILE_BINDINGS = 4_096;
    /**
     * MAX DATABASE BINDINGS.
     * <p>最大数据库绑定集合。
     */
    private static final int MAX_DATABASE_BINDINGS = 256;

    /**
     * Encodes exact file bindings and unknown or explicitly reviewed database state. / 编码精确文件绑定及未知或显式审阅的数据库状态。
     *
     * @param bindings bindings / 绑定集合
     * @return exact file bindings and unknown or explicitly reviewed database state / 精确文件绑定及未知或显式审阅的数据库状态
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] write(ManagedComponentResourceBindings bindings) throws IOException {
        bindings = java.util.Objects.requireNonNull(bindings, "bindings");
        if (bindings.fileBindings().size() > MAX_FILE_BINDINGS
                || bindings.databaseBindings().map(List::size).orElse(0) > MAX_DATABASE_BINDINGS) {
            throw new IOException("managed resource binding collection exceeds the storage limit");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeInt(bindings.fileBindings().size());
            for (ManagedFileBinding binding : bindings.fileBindings()) writeFile(output, binding);
            output.writeByte(bindings.databaseBindings().isPresent() ? 1 : 0);
            if (bindings.databaseBindings().isPresent()) {
                output.writeInt(bindings.databaseBindings().orElseThrow().size());
                for (ManagedDatabaseBinding binding : bindings.databaseBindings().orElseThrow()) {
                    writeDatabase(output, binding);
                }
            }
        }
        byte[] document = bytes.toByteArray();
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("managed resource binding document exceeds the storage limit");
        }
        return document;
    }

    /**
     * Decodes one exact canonical binding document and rejects partial or extended data. / 解码一个精确规范绑定文档并拒绝部分或扩展数据。
     *
     * @param document document / 文档
     * @return one exact canonical binding document and rejects partial or extended data / 一个精确规范绑定文档并拒绝部分或扩展数据
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ManagedComponentResourceBindings read(byte[] document) throws IOException {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("managed resource binding document size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("managed resource binding document header is unsupported");
            }
            int fileCount = boundedCount(input.readInt(), MAX_FILE_BINDINGS, "file binding");
            List<ManagedFileBinding> files = new ArrayList<>(fileCount);
            while (fileCount-- > 0) files.add(readFile(input));
            Optional<List<ManagedDatabaseBinding>> databases = switch (input.readUnsignedByte()) {
                case 0 -> Optional.empty();
                case 1 -> {
                    int databaseCount = boundedCount(input.readInt(), MAX_DATABASE_BINDINGS, "database binding");
                    List<ManagedDatabaseBinding> values = new ArrayList<>(databaseCount);
                    while (databaseCount-- > 0) values.add(readDatabase(input));
                    yield Optional.of(List.copyOf(values));
                }
                default -> throw new IOException("managed database review state is invalid");
            };
            if (input.read() != -1) throw new IOException("managed resource binding document contains trailing data");
            ManagedComponentResourceBindings bindings = new ManagedComponentResourceBindings(files, databases);
            if (!bindings.fileBindings().equals(files)
                    || databases.isPresent() && !bindings.databaseBindings().orElseThrow().equals(databases.orElseThrow())) {
                throw new IOException("managed resource binding document is not in canonical order");
            }
            return bindings;
        } catch (IllegalArgumentException exception) {
            throw new IOException("managed resource binding document violates the typed model", exception);
        }
    }

    /**
     * Writes file.
     * <p>写入文件。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param binding binding / 绑定
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeFile(DataOutputStream output, ManagedFileBinding binding) throws IOException {
        output.writeUTF(binding.bindingId());
        output.writeUTF(binding.dataPath().path());
        output.writeByte(binding.dataPath().access() == ComponentDataPath.AccessMode.READ_ONLY ? 1 : 2);
        output.writeUTF(binding.dataPath().schemaId());
        output.writeByte(binding.dataPath().reversible() ? 1 : 0);
        output.writeUTF(binding.location().type().name()); output.writeUTF(binding.location().path());
        output.writeUTF(binding.resourceType().name()); output.writeUTF(binding.seedFile()); output.writeUTF(binding.contentSha256());
    }

    /**
     * Reads file.
     * <p>读取文件。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return file / 文件
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static ManagedFileBinding readFile(DataInputStream input) throws IOException {
        String bindingId = input.readUTF();
        String path = input.readUTF();
        ComponentDataPath.AccessMode access = switch (input.readUnsignedByte()) {
            case 1 -> ComponentDataPath.AccessMode.READ_ONLY;
            case 2 -> ComponentDataPath.AccessMode.READ_WRITE;
            default -> throw new IOException("managed file binding access mode is unsupported");
        };
        String schemaId = input.readUTF();
        boolean reversible = readBoolean(input, "managed file binding reversible value");
        var location = new gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation(
                gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.valueOf(input.readUTF()), input.readUTF());
        return new ManagedFileBinding(bindingId, new ComponentDataPath(path, access, schemaId, reversible), location,
                gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageResourceType.valueOf(input.readUTF()), input.readUTF(), input.readUTF());
    }

    /**
     * Serializes the database binding variant with its stable discriminator and bounded connection fields.
     * <p>使用稳定判别码及有界连接字段序列化数据库绑定变体。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param binding binding / 绑定
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeDatabase(DataOutputStream output, ManagedDatabaseBinding binding) throws IOException {
        output.writeUTF(binding.databaseId());
        output.writeByte(engineCode(binding.connection().engine()));
        if (binding.connection() instanceof ManagedDatabaseConnection.Sqlite sqlite) {
            output.writeUTF(sqlite.fileName());
            output.writeUTF(sqlite.location().type().name()); output.writeUTF(sqlite.location().path()); output.writeUTF(sqlite.accessPath());
            output.writeUTF(sqlite.seedFile()); output.writeInt(sqlite.initializationFiles().size());
            for (String file : sqlite.initializationFiles()) output.writeUTF(file);
            return;
        }
        ManagedDatabaseConnection.Server server = (ManagedDatabaseConnection.Server) binding.connection();
        output.writeUTF(server.host());
        output.writeInt(server.port());
        output.writeUTF(server.database());
        output.writeUTF(server.username());
        output.writeUTF(server.passwordReference().identifier());
        output.writeLong(server.passwordReference().revision());
        output.writeByte(server.tlsRequired() ? 1 : 0);
    }

    /**
     * Reads reviewed database identity or database operation boundary.
     * <p>读取已审阅数据库身份或数据库操作边界。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static ManagedDatabaseBinding readDatabase(DataInputStream input) throws IOException {
        String databaseId = input.readUTF();
        ManagedDatabaseEngineType engine = engine(input.readUnsignedByte());
        ManagedDatabaseConnection connection;
        if (engine == ManagedDatabaseEngineType.SQLITE) {
            connection = new ManagedDatabaseConnection.Sqlite(input.readUTF(), new gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation(
                    gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.StorageLocationType.valueOf(input.readUTF()), input.readUTF()), input.readUTF(), input.readUTF(), readInitialization(input));
        } else {
            connection = new ManagedDatabaseConnection.Server(engine, input.readUTF(), input.readInt(),
                    input.readUTF(), input.readUTF(), new SecretReference(input.readUTF(), input.readLong()),
                    readBoolean(input, "managed database TLS value"));
        }
        return new ManagedDatabaseBinding(databaseId, connection);
    }

    /**
     * Encodes the database engine using the stable binary persistence codes.
     * <p>使用稳定的二进制持久化编码表示数据库引擎。
     *
     * @param engine engine / 引擎
     * @return the database engine using the stable binary persistence codes / 使用稳定的二进制持久化编码表示数据库引擎
     */
    private static int engineCode(ManagedDatabaseEngineType engine) {
        return switch (engine) {
            case SQLITE -> 1;
            case POSTGRESQL -> 2;
            case MYSQL -> 3;
            case MARIADB -> 4;
            case REDIS -> 5;
        };
    }

    /**
     * Decodes a supported database engine code and rejects unknown binary values.
     * <p>解码受支持的数据库引擎编码，并拒绝未知二进制值。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return a supported database engine code and rejects unknown binary values / 受支持的数据库引擎编码，并拒绝未知二进制值
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static ManagedDatabaseEngineType engine(int value) throws IOException {
        return switch (value) {
            case 1 -> ManagedDatabaseEngineType.SQLITE;
            case 2 -> ManagedDatabaseEngineType.POSTGRESQL;
            case 3 -> ManagedDatabaseEngineType.MYSQL;
            case 4 -> ManagedDatabaseEngineType.MARIADB;
            case 5 -> ManagedDatabaseEngineType.REDIS;
            default -> throw new IOException("managed database engine is unsupported");
        };
    }

    /**
     * Reads boolean.
     * <p>读取布尔。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return true when reads boolean, false otherwise / 读取布尔时为 true，否则为 false
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static boolean readBoolean(DataInputStream input, String name) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IOException(name + " is invalid");
        };
    }

    /**
     * Validates a count before it controls allocation or iteration.
     * <p>在数量控制分配或迭代前完成校验。
     *
     * @param count count / 数量
     * @param maximum maximum / 最大
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return bounded count as a numeric result / 有界数量的数值结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static int boundedCount(int count, int maximum, String name) throws IOException {
        if (count < 0 || count > maximum) throw new IOException(name + " count is invalid");
        return count;
    }
}
