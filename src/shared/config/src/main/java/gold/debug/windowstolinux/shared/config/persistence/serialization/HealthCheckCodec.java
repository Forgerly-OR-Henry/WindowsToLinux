package gold.debug.windowstolinux.shared.config.persistence.serialization;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import java.io.*;
import java.net.URI;

/** Complete strict health payload for both clients and portable backups. / 两端及备份共用的完整严格健康载荷。 */
public final class HealthCheckCodec {
    public byte[] write(HealthCheck value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x57544c48); out.writeByte(2);
            switch (value) {
                case HealthCheck.Http http -> {
                    out.writeByte(1); out.writeUTF(http.endpoint().toString()); out.writeInt(http.expectedStatus()); out.writeInt(http.timeoutSeconds());
                }
                case HealthCheck.Tcp tcp -> {
                    out.writeByte(2); out.writeInt(tcp.port()); out.writeInt(tcp.timeoutSeconds()); out.writeInt(tcp.stabilitySeconds());
                }
                case HealthCheck.Process process -> {
                    out.writeByte(3); out.writeInt(process.timeoutSeconds()); out.writeInt(process.stabilitySeconds());
                }
                case HealthCheck.Command command -> {
                    out.writeByte(4); ApplicationWorkloadCodec.writeCommand(out, command.command());
                    out.writeUTF(command.expectedOutput()); out.writeInt(command.timeoutSeconds());
                }
                case HealthCheck.Udp udp -> {
                    out.writeByte(5); out.writeInt(udp.port()); out.writeUTF(udp.requestHex()); out.writeUTF(udp.responseHex());
                    ApplicationWorkloadCodec.writeOptional(out, udp.probe()); out.writeInt(udp.timeoutSeconds());
                }
            }
        }
        if (bytes.size() > 65536) throw new IOException("health payload exceeds size limit");
        return bytes.toByteArray();
    }
    public HealthCheck read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > 65536) throw new IOException("invalid health payload size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x57544c48 || in.readUnsignedByte() != 2) throw new IOException("unsupported health format");
            HealthCheck value = switch (in.readUnsignedByte()) {
                case 1 -> new HealthCheck.Http(URI.create(in.readUTF()), in.readInt(), in.readInt());
                case 2 -> new HealthCheck.Tcp(in.readInt(), in.readInt(), in.readInt());
                case 3 -> new HealthCheck.Process(in.readInt(), in.readInt());
                case 4 -> new HealthCheck.Command(ApplicationWorkloadCodec.readCommand(in), in.readUTF(), in.readInt());
                case 5 -> new HealthCheck.Udp(in.readInt(), in.readUTF(), in.readUTF(), ApplicationWorkloadCodec.readOptional(in), in.readInt());
                default -> throw new IOException("unsupported health kind");
            };
            if (in.read() != -1) throw new IOException("trailing health payload");
            return value;
        } catch (IllegalArgumentException failure) { throw new IOException("invalid health contract", failure); }
    }
}
