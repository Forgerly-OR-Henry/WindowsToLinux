package gold.debug.windowstolinux.shared.config.persistence.serialization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode;

/**
 * Strict versioned storage codec for one reviewed non-secret runtime definition. / 单个已审阅非秘密运行时定义的严格版本化存储编解码器。
 */
public final class DeploymentRuntimePersistenceCodec {
    /**
     * MAGIC.
     * <p>格式标记。
     */
    private static final int MAGIC = 0x57544c52;

    /**
     * VERSION.
     * <p>版本。
     */
    private static final int VERSION = 5;

    /**
     * MAX DOCUMENT BYTES.
     * <p>最大文档字节。
     */
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;

    /**
     * MAX COLLECTION SIZE.
     * <p>最大采集大小。
     */
    private static final int MAX_COLLECTION_SIZE = 4_096;

    /**
     * Encodes one runtime without duplicating its separately persisted health contract. / 编码运行时且不重复其单独持久化的健康契约。
     *
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return one runtime without duplicating its separately persisted health contract / 运行时且不重复其单独持久化的健康契约
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public byte[] write(DeploymentRuntimeSpecification runtime) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(runtime instanceof DeploymentRuntimeSpecification.ManagedProcess
                    ? 7
                    : runtime.workload().workers().isEmpty() ? VERSION : 6);
            output.writeByte(type(runtime));
            output.writeUTF(runtime.identityPolicy().name());
            writePayload(output, runtime);
            if (runtime instanceof DeploymentRuntimeSpecification.ManagedProcess)
                output.writeBoolean(!runtime.workload().workers().isEmpty());
            ApplicationWorkloadCodec.write(output, runtime.workload());
        }
        byte[] document = bytes.toByteArray();
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("reviewed runtime document exceeds the storage limit");
        }
        return document;
    }

    /**
     * Decodes one exact runtime using the independently persisted health contract. / 使用独立持久化的健康契约解码一个精确运行时。
     *
     * @param document document / 文档
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return one exact runtime using the independently persisted health contract / 使用独立持久化的健康契约解码一个精确运行时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public DeploymentRuntimeSpecification read(byte[] document, HealthCheck healthCheck) throws IOException {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("reviewed runtime document size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("reviewed runtime document header is unsupported");
            }
            int version = input.readUnsignedByte();
            if (version != VERSION && version != 6 && version != 7)
                throw new IOException("unsupported runtime format");
            int type = input.readUnsignedByte();
            if ((type == 15) != (version == 7))
                throw new IOException("runtime format and discriminator mismatch");
            RuntimeIdentityMode policy = RuntimeIdentityMode.valueOf(input.readUTF());
            DeploymentRuntimeSpecification runtime = readPayload(input, type, healthCheck).withIdentityPolicy(policy);
            runtime = runtime.withWorkload(
                    ApplicationWorkloadCodec.read(input, version == 7 ? input.readBoolean() : version == 6));
            if (input.read() != -1) {
                throw new IOException("reviewed runtime document contains trailing data");
            }
            return runtime;
        } catch (IllegalArgumentException exception) {
            throw new IOException("reviewed runtime document violates the typed model", exception);
        }
    }

    /**
     * Encodes the runtime variant as its stable persistence discriminator.
     * <p>将运行规格变体编码为稳定持久化判别码。
     *
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @return the runtime variant as its stable persistence discriminator / 将运行规格变体编码为稳定持久化判别码
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static int type(DeploymentRuntimeSpecification runtime) throws IOException {
        if (runtime == null)
            throw new IOException("reviewed runtime is required");
        return switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot ignored -> 1;
            case DeploymentRuntimeSpecification.JavaJar ignored -> 2;
            case DeploymentRuntimeSpecification.JavaSource ignored -> 3;
            case DeploymentRuntimeSpecification.NodeService ignored -> 4;
            case DeploymentRuntimeSpecification.PythonService ignored -> 5;
            case DeploymentRuntimeSpecification.StaticSite ignored -> 6;
            case DeploymentRuntimeSpecification.Container ignored -> 7;
            case DeploymentRuntimeSpecification.GoService ignored -> 8;
            case DeploymentRuntimeSpecification.RustService ignored -> 9;
            case DeploymentRuntimeSpecification.DotNetService ignored -> 10;
            case DeploymentRuntimeSpecification.KotlinService ignored -> 11;
            case DeploymentRuntimeSpecification.PhpService ignored -> 12;
            case DeploymentRuntimeSpecification.RubyService ignored -> 13;
            case DeploymentRuntimeSpecification.CmakeService ignored -> 14;
            case DeploymentRuntimeSpecification.ManagedProcess ignored -> 15;
        };
    }

    /**
     * Writes the selected runtime variant's fields using its established binary persistence layout.
     * <p>使用既定二进制持久化布局写入所选运行变体字段。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writePayload(DataOutputStream output, DeploymentRuntimeSpecification runtime)
            throws IOException {
        switch (runtime) {
            case DeploymentRuntimeSpecification.ManagedProcess ignored -> {
            }
            case DeploymentRuntimeSpecification.SpringBoot value -> output.writeUTF(value.javaVersion());
            case DeploymentRuntimeSpecification.JavaJar value -> {
                output.writeUTF(value.jarRelativePath());
                output.writeUTF(value.mainClass());
                output.writeUTF(value.javaVersion());
                writeStrings(output, value.jvmArguments());
                writeStrings(output, value.applicationArguments());
            }
            case DeploymentRuntimeSpecification.JavaSource value -> {
                output.writeUTF(value.sourceRoot());
                output.writeUTF(value.mainClass());
                output.writeUTF(value.javaVersion());
                writeStrings(output, value.jvmArguments());
                writeStrings(output, value.applicationArguments());
            }
            case DeploymentRuntimeSpecification.NodeService value -> output.writeInt(value.nodeMajorVersion());
            case DeploymentRuntimeSpecification.PythonService value -> {
                output.writeUTF(value.pythonVersion());
                output.writeUTF(value.entrypoint());
            }
            case DeploymentRuntimeSpecification.StaticSite value -> {
                output.writeUTF(value.outputDirectory());
                output.writeInt(value.nodeMajorVersion().orElse(0));
            }
            case DeploymentRuntimeSpecification.Container value -> writeContainer(output, value);
            case DeploymentRuntimeSpecification.GoService value ->
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
            case DeploymentRuntimeSpecification.RustService value ->
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
            case DeploymentRuntimeSpecification.DotNetService value ->
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
            case DeploymentRuntimeSpecification.KotlinService value -> {
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
                output.writeUTF(value.jvmTarget());
            }
            case DeploymentRuntimeSpecification.PhpService value -> {
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
                output.writeInt(value.servicePort());
            }
            case DeploymentRuntimeSpecification.RubyService value -> {
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
                output.writeInt(value.servicePort());
            }
            case DeploymentRuntimeSpecification.CmakeService value -> {
                output.writeUTF(value.preset());
                output.writeUTF(value.target());
                output.writeUTF(value.artifactName());
            }
        }
    }

    /**
     * Reads the selected runtime discriminator into its exact typed deployment specification.
     * <p>根据所选运行判别码读取精确类型化部署规格。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param health health / 健康
     * @return the selected runtime discriminator into its exact typed deployment specification / 根据所选运行判别码读取精确类型化部署规格
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static DeploymentRuntimeSpecification readPayload(DataInputStream input, int type, HealthCheck health)
            throws IOException {
        return switch (type) {
            case 15 -> new DeploymentRuntimeSpecification.ManagedProcess(health, RuntimeIdentityMode.SYSTEMD_STATIC,
                    gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.unspecified());
            case 1 -> new DeploymentRuntimeSpecification.SpringBoot(input.readUTF(), health);
            case 2 -> new DeploymentRuntimeSpecification.JavaJar(input.readUTF(), input.readUTF(), input.readUTF(),
                    readStrings(input), readStrings(input), health);
            case 3 -> new DeploymentRuntimeSpecification.JavaSource(input.readUTF(), input.readUTF(), input.readUTF(),
                    readStrings(input), readStrings(input), health);
            case 4 -> new DeploymentRuntimeSpecification.NodeService(input.readInt(), health);
            case 5 -> new DeploymentRuntimeSpecification.PythonService(input.readUTF(), input.readUTF(), health);
            case 6 -> new DeploymentRuntimeSpecification.StaticSite(input.readUTF(), optionalVersion(input.readInt()),
                    http(health));
            case 7 -> readContainer(input, health);
            case 8 ->
                new DeploymentRuntimeSpecification.GoService(input.readUTF(), input.readUTF(), input.readUTF(), health);
            case 9 -> new DeploymentRuntimeSpecification.RustService(input.readUTF(), input.readUTF(), input.readUTF(),
                    health);
            case 10 -> new DeploymentRuntimeSpecification.DotNetService(input.readUTF(), input.readUTF(),
                    input.readUTF(), health);
            case 11 -> new DeploymentRuntimeSpecification.KotlinService(input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), health);
            case 12 -> new DeploymentRuntimeSpecification.PhpService(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readInt(), health);
            case 13 -> new DeploymentRuntimeSpecification.RubyService(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readInt(), health);
            case 14 -> new DeploymentRuntimeSpecification.CmakeService(input.readUTF(), input.readUTF(),
                    input.readUTF(), health);
            default -> throw new IOException("reviewed runtime document type is unsupported");
        };
    }

    /**
     * Writes container.
     * <p>写入容器。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeContainer(DataOutputStream output, DeploymentRuntimeSpecification.Container value)
            throws IOException {
        output.writeByte(value.engine() == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER ? 1 : 2);
        writeCount(output, value.publishedPorts().size());
        for (Map.Entry<Integer, Integer> port : value.publishedPorts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            output.writeInt(port.getKey());
            output.writeInt(port.getValue());
        }
        writeCount(output, value.volumes().size());
        for (DeploymentRuntimeSpecification.ManagedVolume volume : value.volumes()) {
            output.writeUTF(volume.name());
            output.writeUTF(volume.containerPath());
            output.writeByte(volume.readOnly() ? 1 : 0);
        }
    }

    /**
     * Reconstructs the container engine, image, ports and volumes from bounded binary runtime fields.
     * <p>根据有界二进制运行字段重建容器引擎、镜像、端口及卷。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param health health / 健康
     * @return constructed or resolved container / 构造或解析得到的容器
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static DeploymentRuntimeSpecification.Container readContainer(DataInputStream input, HealthCheck health)
            throws IOException {
        DeploymentRuntimeSpecification.ContainerEngineType engine = switch (input.readUnsignedByte()) {
            case 1 -> DeploymentRuntimeSpecification.ContainerEngineType.DOCKER;
            case 2 -> DeploymentRuntimeSpecification.ContainerEngineType.PODMAN;
            default -> throw new IOException("reviewed container engine is unsupported");
        };
        Map<Integer, Integer> ports = new LinkedHashMap<>();
        for (int count = readCount(input); count > 0; count--) {
            if (ports.put(input.readInt(), input.readInt()) != null) {
                throw new IOException("reviewed container ports contain a duplicate host port");
            }
        }
        List<DeploymentRuntimeSpecification.ManagedVolume> volumes = new ArrayList<>();
        for (int count = readCount(input); count > 0; count--) {
            volumes.add(new DeploymentRuntimeSpecification.ManagedVolume(input.readUTF(), input.readUTF(),
                    readBoolean(input)));
        }
        return new DeploymentRuntimeSpecification.Container(engine, ports, volumes, health);
    }

    /**
     * Writes application service used by the caller.
     * <p>写入调用方使用的应用服务。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param entrypoint reviewed executable, module or main entry used to start the workload / 启动工作负载所用的已审阅可执行文件、模块或主入口
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeService(DataOutputStream output, String version, String artifact, String entrypoint)
            throws IOException {
        output.writeUTF(version);
        output.writeUTF(artifact);
        output.writeUTF(entrypoint);
    }

    /**
     * Writes strings.
     * <p>写入字符串集合。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeStrings(DataOutputStream output, List<String> values) throws IOException {
        if (values.size() > 32)
            throw new IOException("reviewed runtime argument list exceeds its bound");
        output.writeInt(values.size());
        for (String value : values)
            output.writeUTF(value);
    }

    /**
     * Reads strings.
     * <p>读取字符串集合。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return strings / 字符串集合
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static List<String> readStrings(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 32)
            throw new IOException("reviewed runtime argument count is invalid");
        List<String> values = new ArrayList<>(count);
        while (count-- > 0)
            values.add(input.readUTF());
        return List.copyOf(values);
    }

    /**
     * Writes count.
     * <p>写入数量。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param count count / 数量
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeCount(DataOutputStream output, int count) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("reviewed runtime collection exceeds its storage bound");
        }
        output.writeInt(count);
    }

    /**
     * Reads count.
     * <p>读取数量。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return count / 数量
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("reviewed runtime collection count is invalid");
        }
        return count;
    }

    /**
     * Reads boolean.
     * <p>读取布尔。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return true when reads boolean, false otherwise / 读取布尔时为 true，否则为 false
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static boolean readBoolean(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IOException("reviewed runtime boolean value is invalid");
        };
    }

    /**
     * Decodes zero as an absent Node version and rejects negative persisted versions.
     * <p>将零解码为缺失的 Node 版本，并拒绝持久化负版本值。
     *
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static OptionalInt optionalVersion(int version) throws IOException {
        if (version < 0)
            throw new IOException("reviewed static-site Node version is invalid");
        return version == 0 ? OptionalInt.empty() : OptionalInt.of(version);
    }

    /**
     * Requires a stored static-site health contract to be an HTTP health check.
     * <p>要求持久化静态站点健康契约为 HTTP 健康检查。
     *
     * @param health health / 健康
     * @return constructed or resolved http / 构造或解析得到的HTTP
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static HealthCheck.Http http(HealthCheck health) throws IOException {
        if (health instanceof HealthCheck.Http value)
            return value;
        throw new IOException("reviewed static-site health contract must be HTTP");
    }
}
