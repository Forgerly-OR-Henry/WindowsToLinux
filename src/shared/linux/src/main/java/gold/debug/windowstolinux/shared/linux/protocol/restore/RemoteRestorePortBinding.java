package gold.debug.windowstolinux.shared.linux.protocol.restore;

/**
 * One exact official-to-candidate port mapping. / 一个精确的正式到候选端口映射。
 *
 * @param officialPort official port / 正式端口
 * @param candidatePort candidate port / 候选端口
 * @param protocol protocol / 协议
 */
public record RemoteRestorePortBinding(int officialPort, int candidatePort, String protocol) {
    /**
     * Initializes remote restore port binding through its shared constructor contract.
     * <p>通过共享构造契约初始化远端恢复端口绑定。
     *
     * @param officialPort official port / 正式端口
     * @param candidatePort candidate port / 候选端口
     */
    public RemoteRestorePortBinding(int officialPort, int candidatePort) {
        this(officialPort, candidatePort, "tcp");
    }

    /**
     * Requires valid different ports. / 要求有效且不同的端口。
     *
     * @param officialPort official port / 正式端口
     * @param candidatePort candidate port / 候选端口
     * @param protocol protocol / 协议
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public RemoteRestorePortBinding {
        if (!java.util.Set.of("tcp", "udp").contains(protocol))
            throw new IllegalArgumentException("invalid port transport");
        if (officialPort < 1 || officialPort > 65535 || candidatePort < 49152 || candidatePort > 65535
                || officialPort == candidatePort) {
            throw new IllegalArgumentException("remote restore port binding is invalid");
        }
    }
}
