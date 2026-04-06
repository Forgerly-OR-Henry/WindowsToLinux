package gold.debug.wintolin.attribute.system;

import com.jcraft.jsch.Session;

public class ALinux {

    // 常见 Linux 发行版
    public enum LinuxDistribution {
        UBUNTU,
        DEBIAN,
        FEDORA,
        CENTOS,
        RHEL,
        ROCKY,
        ALMALINUX,
        ORACLE,
        ARCH,
        MANJARO,
        MINT,
        OPENSUSE,
        ALPINE,
        KALI,
        AMAZON_LINUX,
        UNKNOWN
    }

    private String ip; // IP 地址或域名
    private String user; // SSH 用户名
    private String password; // 用户名对应密码
    private Session session; // SSH 连接通道
    private LinuxDistribution linuxDistribution; // Linux 发行版
    private String linuxVersion; // Linux 发行版版本号

    // GET AND SET 方法
    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public LinuxDistribution getLinuxDistribution() {
        return linuxDistribution;
    }

    public void setLinuxDistribution(LinuxDistribution linuxDistribution) {
        this.linuxDistribution = linuxDistribution;
    }

    public String getLinuxVersion() {
        return linuxVersion;
    }

    public void setLinuxVersion(String linuxVersion) {
        this.linuxVersion = linuxVersion;
    }
}
