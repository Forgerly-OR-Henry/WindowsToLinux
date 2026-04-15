package gold.debug.wintolin.tools.system.linux;

import gold.debug.wintolin.attribute.system.ALinux;

public final class TLinux {

    private TLinux() {
        throw new UnsupportedOperationException("TLinux is a utility class.");
    }

    /**
     * 校验 ALinux 对象中的 ip 字段是否合法。
     * 支持 IPv4、IPv6 与域名格式校验；
     * 若校验失败，将抛出 MyException。
     *
     * @param linux Linux 属性对象
     */
    public static void isIPRight(ALinux linux) {
        TLJudgeIPRight.isIPRight(linux);
    }

    /**
     * 校验 ALinux 对象中的 ip 字段是否为合法域名。
     * 若不是合法域名，将抛出 MyException。
     *
     * @param linux Linux 属性对象
     */
    public static void isDomainName(ALinux linux) {
        TLJudgeIPRight.isDomainName(linux);
    }

    /**
     * 根据 ALinux 对象中的连接信息建立 SSH 连接。
     * 连接成功后，会将创建好的 Session 写回 ALinux 对象；
     * 若连接失败，将抛出 MyException。
     *
     * @param linux Linux 属性对象
     */
    public static void connectSSH(ALinux linux) {
        TLLinkSSH.connectSSH(linux);
    }

    /**
     * 断开 ALinux 对象中的 SSH 连接。
     * 若当前存在已保存的 Session，则执行断开操作，
     * 并将 ALinux 中的 Session 置空。
     *
     * @param linux Linux 属性对象
     */
    public static void disconnectSSH(ALinux linux) {
        TLLinkSSH.disconnectSSH(linux);
    }

    /**
     * 获取远程 Linux 发行版及版本信息。
     * 要求 ALinux 对象中的 Session 已成功连接；
     * 方法执行成功后，会将识别到的发行版与版本号写回 ALinux 对象。
     * 若 Session 未连接或获取失败，将抛出 MyException。
     *
     * @param linux Linux 属性对象
     */
    public static void getDistribution(ALinux linux) {
        TLGetDistribution.getDistribution(linux);
    }
}