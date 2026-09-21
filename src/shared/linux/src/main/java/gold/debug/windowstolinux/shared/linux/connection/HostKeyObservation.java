package gold.debug.windowstolinux.shared.linux.connection;

import java.util.Objects;

/**
 * Two fingerprints computed from the same handshake public key. / 从同一个握手公钥计算的两种指纹。
 *
 * @param sshSha256 SSH wire-format SHA-256 public-key fingerprint / SSH 线格式 SHA-256 公钥指纹
 * @param legacyEncodedSha256 legacy encoded sha 256 / 历史已编码SHA256
 */
public record HostKeyObservation(String sshSha256, String legacyEncodedSha256) {
    /**
     * Requires both representations of the observed key. / 要求提供观测公钥的两种表示。
     *
     * @param sshSha256 SSH wire-format SHA-256 public-key fingerprint / SSH 线格式 SHA-256 公钥指纹
     * @param legacyEncodedSha256 legacy encoded sha 256 / 历史已编码SHA256
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public HostKeyObservation {
        Objects.requireNonNull(sshSha256, "sshSha256");
        Objects.requireNonNull(legacyEncodedSha256, "legacyEncodedSha256");
    }
}
