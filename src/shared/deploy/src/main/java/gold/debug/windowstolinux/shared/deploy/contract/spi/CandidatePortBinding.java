package gold.debug.windowstolinux.shared.deploy.contract.spi;

/**
 * One official port and its optional loopback-only candidate replacement. / 一个正式端口及其可选的仅回环候选替代端口。
 *
 * @param officialPort official port / 正式端口
 * @param candidatePort candidate port / 候选端口
 * @param protocol protocol / 协议
 */
public record CandidatePortBinding(int officialPort, int candidatePort, String protocol) {
    /**
     * Initializes candidate port binding through its shared constructor contract.
     * <p>通过共享构造契约初始化候选端口绑定。
     *
     * @param officialPort official port / 正式端口
     * @param candidatePort candidate port / 候选端口
     */
    public CandidatePortBinding(int officialPort, int candidatePort) { this(officialPort, candidatePort, "tcp"); }
    /**
     * Requires two different valid ports. / 要求两个不同的有效端口。
     *
     * @param officialPort official port / 正式端口
     * @param candidatePort candidate port / 候选端口
     * @param protocol protocol / 协议
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public CandidatePortBinding {
        if (!java.util.Set.of("tcp", "udp").contains(protocol)) throw new IllegalArgumentException("invalid port transport");
        requirePort(officialPort, "officialPort");
        requirePort(candidatePort, "candidatePort");
        if (officialPort == candidatePort) {
            throw new IllegalArgumentException("candidatePort must differ from officialPort");
        }
    }

    /**
     * Requires network port number in the reviewed endpoint and rejects inputs outside the declared constraints.
     * <p>要求已审阅端点中的网络端口号并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void requirePort(int value, String field) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException(field + " is invalid");
    }
}
