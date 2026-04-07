package gold.debug.wintolin.tools.system.linux;

import gold.debug.wintolin.attribute.system.ALinux;

public final class TLinux {

    private TLinux() {
        throw new UnsupportedOperationException("TLinux is a utility class.");
    }

    public static void isIPRight(ALinux linux) {
        TLJudgeIPRight.isIPRight(linux);
    }

    public static void isDomainName(ALinux linux) {
        TLJudgeIPRight.isDomainName(linux);
    }

    public static void connectSSH(ALinux linux) {
        TLLinkSSH.connectSSH(linux);
    }

    public static void disconnectSSH(ALinux linux) {
        TLLinkSSH.disconnectSSH(linux);
    }

    public static void getDistribution(ALinux linux) {
        TLGetDistribution.getDistribution(linux);
    }
}