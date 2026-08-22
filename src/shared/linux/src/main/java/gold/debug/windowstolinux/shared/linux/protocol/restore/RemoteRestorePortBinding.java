package gold.debug.windowstolinux.shared.linux.protocol.restore;

/** One exact official-to-candidate port mapping. / 一个精确的正式到候选端口映射。 */
public record RemoteRestorePortBinding(int officialPort, int candidatePort) {
    /** Requires valid different ports. / 要求有效且不同的端口。 */
    public RemoteRestorePortBinding {
        if (officialPort < 1 || officialPort > 65535 || candidatePort < 49152 || candidatePort > 65535
                || officialPort == candidatePort) {
            throw new IllegalArgumentException("remote restore port binding is invalid");
        }
    }
}
