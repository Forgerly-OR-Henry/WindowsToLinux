package gold.debug.windowstolinux.app.db.persistence.serialization;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

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

/** Strict versioned storage codec for one reviewed non-secret runtime definition. / 单个已审阅非秘密运行时定义的严格版本化存储编解码器。 */
public final class DeploymentRuntimePersistenceCodec {
    private static final int MAGIC = 0x57544c52;
    private static final int VERSION = 1;
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final int MAX_COLLECTION_SIZE = 4_096;

    /** Encodes one runtime without duplicating its separately persisted health contract. / 编码运行时且不重复其单独持久化的健康契约。 */
    public byte[] write(DeploymentRuntimeSpecification runtime) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(type(runtime));
            writePayload(output, runtime);
        }
        byte[] document = bytes.toByteArray();
        if (document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("reviewed runtime document exceeds the storage limit");
        }
        return document;
    }

    /** Decodes one exact runtime using the independently persisted health contract. / 使用独立持久化的健康契约解码一个精确运行时。 */
    public DeploymentRuntimeSpecification read(byte[] document, HealthCheck healthCheck) throws IOException {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_BYTES) {
            throw new IOException("reviewed runtime document size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(document))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("reviewed runtime document header is unsupported");
            }
            DeploymentRuntimeSpecification runtime = readPayload(input, input.readUnsignedByte(), healthCheck);
            if (input.read() != -1) {
                throw new IOException("reviewed runtime document contains trailing data");
            }
            return runtime;
        } catch (IllegalArgumentException exception) {
            throw new IOException("reviewed runtime document violates the typed model", exception);
        }
    }

    private static int type(DeploymentRuntimeSpecification runtime) throws IOException {
        if (runtime == null) throw new IOException("reviewed runtime is required");
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
        };
    }

    private static void writePayload(DataOutputStream output, DeploymentRuntimeSpecification runtime) throws IOException {
        switch (runtime) {
            case DeploymentRuntimeSpecification.SpringBoot ignored -> { }
            case DeploymentRuntimeSpecification.JavaJar value -> {
                output.writeUTF(value.jarRelativePath()); output.writeUTF(value.mainClass());
                output.writeUTF(value.javaVersion()); writeStrings(output, value.jvmArguments());
                writeStrings(output, value.applicationArguments());
            }
            case DeploymentRuntimeSpecification.JavaSource value -> {
                output.writeUTF(value.sourceRoot()); output.writeUTF(value.mainClass());
                output.writeUTF(value.javaVersion()); writeStrings(output, value.jvmArguments());
                writeStrings(output, value.applicationArguments());
            }
            case DeploymentRuntimeSpecification.NodeService value -> output.writeInt(value.nodeMajorVersion());
            case DeploymentRuntimeSpecification.PythonService value -> {
                output.writeUTF(value.pythonVersion()); output.writeUTF(value.entrypoint());
            }
            case DeploymentRuntimeSpecification.StaticSite value -> {
                output.writeUTF(value.outputDirectory()); output.writeInt(value.nodeMajorVersion().orElse(0));
            }
            case DeploymentRuntimeSpecification.Container value -> writeContainer(output, value);
            case DeploymentRuntimeSpecification.GoService value -> writeService(output, value.version(),
                    value.artifactName(), value.entrypoint());
            case DeploymentRuntimeSpecification.RustService value -> writeService(output, value.version(),
                    value.artifactName(), value.entrypoint());
            case DeploymentRuntimeSpecification.DotNetService value -> writeService(output, value.version(),
                    value.artifactName(), value.entrypoint());
            case DeploymentRuntimeSpecification.KotlinService value -> writeService(output, value.version(),
                    value.artifactName(), value.entrypoint());
            case DeploymentRuntimeSpecification.PhpService value -> {
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
                output.writeInt(value.servicePort());
            }
            case DeploymentRuntimeSpecification.RubyService value -> {
                writeService(output, value.version(), value.artifactName(), value.entrypoint());
                output.writeInt(value.servicePort());
            }
            case DeploymentRuntimeSpecification.CmakeService value -> {
                output.writeUTF(value.preset()); output.writeUTF(value.target()); output.writeUTF(value.artifactName());
            }
        }
    }

    private static DeploymentRuntimeSpecification readPayload(DataInputStream input, int type, HealthCheck health)
            throws IOException {
        return switch (type) {
            case 1 -> new DeploymentRuntimeSpecification.SpringBoot(health);
            case 2 -> new DeploymentRuntimeSpecification.JavaJar(input.readUTF(), input.readUTF(), input.readUTF(),
                    readStrings(input), readStrings(input), health);
            case 3 -> new DeploymentRuntimeSpecification.JavaSource(input.readUTF(), input.readUTF(), input.readUTF(),
                    readStrings(input), readStrings(input), health);
            case 4 -> new DeploymentRuntimeSpecification.NodeService(input.readInt(), health);
            case 5 -> new DeploymentRuntimeSpecification.PythonService(input.readUTF(), input.readUTF(), health);
            case 6 -> new DeploymentRuntimeSpecification.StaticSite(input.readUTF(), optionalVersion(input.readInt()),
                    http(health));
            case 7 -> readContainer(input, health);
            case 8 -> new DeploymentRuntimeSpecification.GoService(input.readUTF(), input.readUTF(), input.readUTF(), health);
            case 9 -> new DeploymentRuntimeSpecification.RustService(input.readUTF(), input.readUTF(), input.readUTF(), health);
            case 10 -> new DeploymentRuntimeSpecification.DotNetService(input.readUTF(), input.readUTF(), input.readUTF(), health);
            case 11 -> new DeploymentRuntimeSpecification.KotlinService(input.readUTF(), input.readUTF(), input.readUTF(), health);
            case 12 -> new DeploymentRuntimeSpecification.PhpService(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readInt(), health);
            case 13 -> new DeploymentRuntimeSpecification.RubyService(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readInt(), health);
            case 14 -> new DeploymentRuntimeSpecification.CmakeService(input.readUTF(), input.readUTF(), input.readUTF(), health);
            default -> throw new IOException("reviewed runtime document type is unsupported");
        };
    }

    private static void writeContainer(DataOutputStream output, DeploymentRuntimeSpecification.Container value)
            throws IOException {
        output.writeByte(value.engine() == DeploymentRuntimeSpecification.ContainerEngineType.DOCKER ? 1 : 2);
        writeCount(output, value.publishedPorts().size());
        for (Map.Entry<Integer, Integer> port : value.publishedPorts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            output.writeInt(port.getKey()); output.writeInt(port.getValue());
        }
        writeCount(output, value.volumes().size());
        for (DeploymentRuntimeSpecification.ManagedVolume volume : value.volumes()) {
            output.writeUTF(volume.name()); output.writeUTF(volume.containerPath());
            output.writeByte(volume.readOnly() ? 1 : 0);
        }
    }

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
            volumes.add(new DeploymentRuntimeSpecification.ManagedVolume(
                    input.readUTF(), input.readUTF(), readBoolean(input)));
        }
        return new DeploymentRuntimeSpecification.Container(engine, ports, volumes, health);
    }

    private static void writeService(DataOutputStream output, String version, String artifact, String entrypoint)
            throws IOException {
        output.writeUTF(version); output.writeUTF(artifact); output.writeUTF(entrypoint);
    }

    private static void writeStrings(DataOutputStream output, List<String> values) throws IOException {
        if (values.size() > 32) throw new IOException("reviewed runtime argument list exceeds its bound");
        output.writeInt(values.size());
        for (String value : values) output.writeUTF(value);
    }

    private static List<String> readStrings(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 32) throw new IOException("reviewed runtime argument count is invalid");
        List<String> values = new ArrayList<>(count);
        while (count-- > 0) values.add(input.readUTF());
        return List.copyOf(values);
    }

    private static void writeCount(DataOutputStream output, int count) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("reviewed runtime collection exceeds its storage bound");
        }
        output.writeInt(count);
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("reviewed runtime collection count is invalid");
        }
        return count;
    }

    private static boolean readBoolean(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IOException("reviewed runtime boolean value is invalid");
        };
    }

    private static OptionalInt optionalVersion(int version) throws IOException {
        if (version < 0) throw new IOException("reviewed static-site Node version is invalid");
        return version == 0 ? OptionalInt.empty() : OptionalInt.of(version);
    }

    private static HealthCheck.Http http(HealthCheck health) throws IOException {
        if (health instanceof HealthCheck.Http value) return value;
        throw new IOException("reviewed static-site health contract must be HTTP");
    }
}
