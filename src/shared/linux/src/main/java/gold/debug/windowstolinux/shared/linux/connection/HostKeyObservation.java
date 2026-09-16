package gold.debug.windowstolinux.shared.linux.connection;

import java.util.Objects;

/** Two fingerprints computed from the same handshake public key. / 从同一个握手公钥计算的两种指纹。 */
public record HostKeyObservation(String sshSha256, String legacyEncodedSha256) {
    /** Requires both representations of the observed key. / 要求提供观测公钥的两种表示。 */
    public HostKeyObservation {
        Objects.requireNonNull(sshSha256, "sshSha256");
        Objects.requireNonNull(legacyEncodedSha256, "legacyEncodedSha256");
    }
}
