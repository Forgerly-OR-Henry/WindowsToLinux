package gold.debug.windowstolinux.shared.config.persistence.serialization;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import java.io.*;
import java.net.URI;
import java.util.Optional;

/**
 * Exact activation and inventory state, without inferred service exposure. / 不推测服务对外范围的精确激活与清单状态。
 */
public final class ApplicationRuntimeConfigurationCodec {
    /**
     * Writes application runtime configuration.
     * <p>写入应用运行时配置。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public byte[] write(ManagedApplicationRuntimeConfiguration value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x57544c41); out.writeByte(value.workload().workers().isEmpty() ? 1 : 2);
            byte[] health = new HealthCheckCodec().write(value.healthCheck()); out.writeInt(health.length); out.write(health);
            out.writeUTF(value.identityPolicy().name()); out.writeUTF(value.userAccessUrl().map(url -> url.url().toString()).orElse(""));
            ApplicationWorkloadCodec.write(out, value.workload());
        }
        if (bytes.size() > 1_048_576) throw new IOException("application runtime exceeds size limit");
        return bytes.toByteArray();
    }
    /**
     * Decodes the bounded versioned application runtime document and rejects unsupported variants or trailing bytes.
     * <p>解码有界且带版本的应用运行文档，并拒绝不支持的变体或尾随字节。
     *
     * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
     * @return the bounded versioned application runtime document and rejects unsupported variants or trailing bytes / 有界且带版本的应用运行文档，并拒绝不支持的变体或尾随字节
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public ManagedApplicationRuntimeConfiguration read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > 1_048_576) throw new IOException("invalid application runtime size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x57544c41) throw new IOException("unsupported application runtime format");
            int version = in.readUnsignedByte();
            if (version != 1 && version != 2) throw new IOException("unsupported application runtime format");
            int count = in.readInt(); if (count < 1 || count > 65536) throw new IOException("invalid health size");
            var health = new HealthCheckCodec().read(in.readNBytes(count));
            var identity = RuntimeIdentityMode.valueOf(in.readUTF()); String url = in.readUTF();
            var result = new ManagedApplicationRuntimeConfiguration(health,
                    url.isEmpty() ? Optional.empty() : Optional.of(new UserAccessUrl(URI.create(url))), identity, ApplicationWorkloadCodec.read(in, version == 2));
            if (in.read() != -1) throw new IOException("trailing application runtime data");
            return result;
        } catch (IllegalArgumentException failure) { throw new IOException("invalid application runtime", failure); }
    }
}
