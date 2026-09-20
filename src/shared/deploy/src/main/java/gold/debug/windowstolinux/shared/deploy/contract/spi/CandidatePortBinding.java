package gold.debug.windowstolinux.shared.deploy.contract.spi;

/** One official port and its optional loopback-only candidate replacement. / 一个正式端口及其可选的仅回环候选替代端口。 */
public record CandidatePortBinding(int officialPort, int candidatePort, String protocol) {
    public CandidatePortBinding(int officialPort, int candidatePort) { this(officialPort, candidatePort, "tcp"); }
    /** Requires two different valid ports. / 要求两个不同的有效端口。 */
    public CandidatePortBinding {
        if (!java.util.Set.of("tcp", "udp").contains(protocol)) throw new IllegalArgumentException("invalid port transport");
        requirePort(officialPort, "officialPort");
        requirePort(candidatePort, "candidatePort");
        if (officialPort == candidatePort) {
            throw new IllegalArgumentException("candidatePort must differ from officialPort");
        }
    }

    private static void requirePort(int value, String field) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException(field + " is invalid");
    }
}
