package gold.debug.windowstolinux.shared.config.persistence.serialization;

import java.io.*;
import java.util.*;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.application.*;

/**
 * Canonical bounded workload payload shared by persistence and release identity. / 持久化与发布身份共用的有界执行载荷。
 */
public final class ApplicationWorkloadCodec {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ApplicationWorkloadCodec() {
    }

    /**
     * Writes the workload's execution mode, literal commands, endpoints, companions and worker definitions in the stable binary order.
     * <p>按稳定二进制顺序写入工作负载执行模式、字面命令、端点、配套单元及工作进程定义。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static void write(DataOutput output, ApplicationWorkload value) throws IOException {
        output.writeUTF(value.mode().name());
        output.writeBoolean(value.reviewed());
        writeCommand(output, value.command());
        output.writeUTF(value.workingDirectory());
        output.writeUTF(value.buildDirectory());
        output.writeInt(value.endpoints().size());
        for (var endpoint : value.endpoints()) {
            output.writeUTF(endpoint.id());
            output.writeUTF(endpoint.protocol().name());
            output.writeUTF(endpoint.bindAddress());
            output.writeInt(endpoint.hostPort());
            output.writeInt(endpoint.targetPort());
            output.writeUTF(endpoint.exposure().name());
            output.writeUTF(endpoint.accessUrl());
        }
        writeOptional(output, value.verification());
        output.writeUTF(value.expectedOutput());
        writeOptional(output, value.client());
        output.writeInt(value.inputs().size());
        for (var input : value.inputs()) {
            output.writeUTF(input.id());
            output.writeUTF(input.hostPath());
            output.writeUTF(input.accessPath());
        }
        output.writeInt(value.companions().size());
        for (var item : value.companions()) {
            output.writeUTF(item.id());
            output.writeUTF(item.sourcePath());
            output.writeUTF(item.projectType().name());
            output.writeUTF(item.artifactPath());
            output.writeUTF(item.environment());
        }
        if (!value.workers().isEmpty()) {
            output.writeInt(value.workers().size());
            for (var worker : value.workers()) {
                output.writeUTF(worker.id());
                writeCommand(output, worker.command());
            }
        }
    }

    /**
     * Reads the versioned workload contract with bounded collections and typed execution and health variants.
     * <p>使用有界集合及类型化执行和健康变体读取带版本工作负载契约。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param hasWorkers has workers / 具有工作线程集合
     * @return the versioned workload contract with bounded collections and typed execution and health variants / 使用有界集合及类型化执行和健康变体读取带版本工作负载契约
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static ApplicationWorkload read(DataInput input, boolean hasWorkers) throws IOException {
        var mode = ApplicationWorkload.ExecutionMode.valueOf(input.readUTF());
        boolean reviewed = bool(input);
        var command = readCommand(input);
        String directory = input.readUTF(), buildDirectory = input.readUTF();
        var endpoints = new ArrayList<ApplicationEndpoint>();
        for (int n = count(input, 32); n > 0; n--)
            endpoints.add(new ApplicationEndpoint(input.readUTF(),
                    ApplicationEndpoint.ProtocolType.valueOf(input.readUTF()), input.readUTF(), input.readInt(),
                    input.readInt(), ApplicationEndpoint.ExposureType.valueOf(input.readUTF()), input.readUTF()));
        var verification = readOptional(input);
        String expected = input.readUTF();
        var client = readOptional(input);
        var inputs = new ArrayList<ApplicationInput>();
        for (int n = count(input, 32); n > 0; n--)
            inputs.add(new ApplicationInput(input.readUTF(), input.readUTF(), input.readUTF()));
        var companions = new ArrayList<ApplicationCompanion>();
        for (int n = count(input, 32); n > 0; n--)
            companions.add(new ApplicationCompanion(input.readUTF(), input.readUTF(),
                    ApplicationCompanion.BuildType.valueOf(input.readUTF()), input.readUTF(), input.readUTF()));
        var workers = new ArrayList<ApplicationWorker>();
        if (hasWorkers) {
            int size = count(input, 8);
            if (size == 0)
                throw new IOException("worker runtime must declare workers");
            for (int n = size; n > 0; n--)
                workers.add(new ApplicationWorker(input.readUTF(), readCommand(input)));
        }
        return new ApplicationWorkload(mode, reviewed, command, directory, endpoints, verification, expected, client,
                inputs, companions, buildDirectory, workers);
    }

    /**
     * Writes fixed or explicitly reviewed command text.
     * <p>写入固定或显式审阅的命令文本。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static void writeCommand(DataOutput output, ApplicationCommand value) throws IOException {
        output.writeUTF(value.entrypoint());
        output.writeInt(value.arguments().size());
        for (String arg : value.arguments())
            output.writeUTF(arg);
    }

    /**
     * Reads fixed or explicitly reviewed command text.
     * <p>读取固定或显式审阅的命令文本。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static ApplicationCommand readCommand(DataInput input) throws IOException {
        String entrypoint = input.readUTF();
        var arguments = new ArrayList<String>();
        for (int n = count(input, 64); n > 0; n--)
            arguments.add(input.readUTF());
        return new ApplicationCommand(entrypoint, arguments);
    }

    /**
     * Writes optional.
     * <p>写入可选。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static void writeOptional(DataOutput output, Optional<ApplicationCommand> command) throws IOException {
        output.writeBoolean(command.isPresent());
        if (command.isPresent())
            writeCommand(output, command.orElseThrow());
    }

    /**
     * Reads optional.
     * <p>读取可选。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static Optional<ApplicationCommand> readOptional(DataInput input) throws IOException {
        return bool(input) ? Optional.of(readCommand(input)) : Optional.empty();
    }

    /**
     * Checks the item or byte count against the explicit bound before accepting more content.
     * <p>在接受更多内容前按显式边界检查条目数或字节数。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param max max / 最大
     * @return count as a numeric result / 数量的数值结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static int count(DataInput input, int max) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > max)
            throw new IOException("invalid workload collection size");
        return count;
    }

    /**
     * Parses the supported boolean representation of a contract field.
     * <p>解析契约字段支持的布尔表示。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return true when parses the supported boolean representation of a contract field, false otherwise / 解析契约字段支持的布尔表示时为 true，否则为 false
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static boolean bool(DataInput input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new IOException("invalid workload boolean");
        };
    }
}
