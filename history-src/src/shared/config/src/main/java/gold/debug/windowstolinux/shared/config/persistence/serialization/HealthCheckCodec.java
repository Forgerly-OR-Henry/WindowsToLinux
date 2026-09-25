package gold.debug.windowstolinux.shared.config.persistence.serialization;

import java.io.*;
import java.net.URI;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

/**
 * Complete strict health payload for both clients and portable backups. / 两端及备份共用的完整严格健康载荷。
 */
public final class HealthCheckCodec {
    /**
     * Serializes the selected HTTP, TCP or application health-check variant into its versioned binary contract.
     * <p>将所选 HTTP、TCP 或应用健康检查变体序列化为带版本二进制契约。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public byte[] write(HealthCheck value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x57544c48);
            out.writeByte(2);
            switch (value) {
                case HealthCheck.Http http -> {
                    out.writeByte(1);
                    out.writeUTF(http.endpoint().toString());
                    out.writeInt(http.expectedStatus());
                    out.writeInt(http.timeoutSeconds());
                }
                case HealthCheck.Tcp tcp -> {
                    out.writeByte(2);
                    out.writeInt(tcp.port());
                    out.writeInt(tcp.timeoutSeconds());
                    out.writeInt(tcp.stabilitySeconds());
                }
                case HealthCheck.Process process -> {
                    out.writeByte(3);
                    out.writeInt(process.timeoutSeconds());
                    out.writeInt(process.stabilitySeconds());
                }
                case HealthCheck.Command command -> {
                    out.writeByte(4);
                    ApplicationWorkloadCodec.writeCommand(out, command.command());
                    out.writeUTF(command.expectedOutput());
                    out.writeInt(command.timeoutSeconds());
                }
                case HealthCheck.Udp udp -> {
                    out.writeByte(5);
                    out.writeInt(udp.port());
                    out.writeUTF(udp.requestHex());
                    out.writeUTF(udp.responseHex());
                    ApplicationWorkloadCodec.writeOptional(out, udp.probe());
                    out.writeInt(udp.timeoutSeconds());
                }
            }
        }
        if (bytes.size() > 65536)
            throw new IOException("health payload exceeds size limit");
        return bytes.toByteArray();
    }

    /**
     * Decodes a bounded versioned health-check payload and rejects unknown variants or trailing bytes.
     * <p>解码有界且带版本的健康检查载荷，并拒绝未知变体或尾随字节。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return a bounded versioned health-check payload and rejects unknown variants or trailing bytes / 有界且带版本的健康检查载荷，并拒绝未知变体或尾随字节
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public HealthCheck read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > 65536)
            throw new IOException("invalid health payload size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x57544c48 || in.readUnsignedByte() != 2)
                throw new IOException("unsupported health format");
            HealthCheck value = switch (in.readUnsignedByte()) {
                case 1 -> new HealthCheck.Http(URI.create(in.readUTF()), in.readInt(), in.readInt());
                case 2 -> new HealthCheck.Tcp(in.readInt(), in.readInt(), in.readInt());
                case 3 -> new HealthCheck.Process(in.readInt(), in.readInt());
                case 4 -> new HealthCheck.Command(ApplicationWorkloadCodec.readCommand(in), in.readUTF(), in.readInt());
                case 5 -> new HealthCheck.Udp(in.readInt(), in.readUTF(), in.readUTF(),
                        ApplicationWorkloadCodec.readOptional(in), in.readInt());
                default -> throw new IOException("unsupported health kind");
            };
            if (in.read() != -1)
                throw new IOException("trailing health payload");
            return value;
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid health contract", failure);
        }
    }
}
