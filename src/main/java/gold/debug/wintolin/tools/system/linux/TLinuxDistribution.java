package gold.debug.wintolin.tool.system;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class TLinux {

    private static final String ERROR_LINUX_NULL = "ERROR_LINUX_NULL";
    private static final String ERROR_SESSION_NOT_CONNECTED = "ERROR_SESSION_NOT_CONNECTED";
    private static final String ERROR_LINUX_DISTRIBUTION_GET_FAILED = "ERROR_LINUX_DISTRIBUTION_GET_FAILED";

    private static final Method METHOD_LINUX_DISTRIBUTION_GET =
            MethodUtils.getCurrentMethod(
                    TLinux.class,
                    "LinuxDistributionGet",
                    ALinux.class
            );

    private TLinux() {
    }

    /**
     * 获取 Linux 发行版及版本号，并写回 ALinux 对象。
     *
     * @param aLinux Linux 属性对象
     */
    public static void LinuxDistributionGet(ALinux aLinux) {
        if (aLinux == null) {
            MyException.fail(
                    TLinux.class,
                    METHOD_LINUX_DISTRIBUTION_GET,
                    "ALinux对象不能为空。",
                    ERROR_LINUX_NULL
            );
        }

        Session session = aLinux.getSession();
        if (session == null || !session.isConnected()) {
            MyException.fail(
                    TLinux.class,
                    METHOD_LINUX_DISTRIBUTION_GET,
                    "Session未连接，无法获取Linux发行版信息。",
                    ERROR_SESSION_NOT_CONNECTED
            );
        }

        String osReleaseContent = readOsReleaseContent(session);
        String distributionId = readOsReleaseValue(osReleaseContent, "ID");
        String versionId = readOsReleaseValue(osReleaseContent, "VERSION_ID");

        aLinux.setLinuxDistribution(parseLinuxDistribution(distributionId));
        aLinux.setLinuxVersion(versionId);
    }

    /**
     * 读取远程系统 /etc/os-release 内容。
     */
    private static String readOsReleaseContent(Session session) {
        ChannelExec channel = null;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("cat /etc/os-release");
            channel.setInputStream(null);
            channel.setOutputStream(outputStream);
            channel.setErrStream(errorStream);
            channel.connect();

            while (!channel.isClosed()) {
                Thread.sleep(50);
            }

            String stdOut = outputStream.toString(StandardCharsets.UTF_8);
            String stdErr = errorStream.toString(StandardCharsets.UTF_8);

            if (stdOut == null || stdOut.isBlank()) {
                MyException.fail(
                        TLinux.class,
                        METHOD_LINUX_DISTRIBUTION_GET,
                        "远程系统未返回有效的 /etc/os-release 内容。错误输出：" + stdErr,
                        ERROR_LINUX_DISTRIBUTION_GET_FAILED
                );
            }

            return stdOut;
        } catch (JSchException e) {
            MyException.fail(
                    TLinux.class,
                    METHOD_LINUX_DISTRIBUTION_GET,
                    "获取Linux发行版信息失败。",
                    ERROR_LINUX_DISTRIBUTION_GET_FAILED,
                    e
            );
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MyException.fail(
                    TLinux.class,
                    METHOD_LINUX_DISTRIBUTION_GET,
                    "线程在获取Linux发行版信息时被中断。",
                    ERROR_LINUX_DISTRIBUTION_GET_FAILED,
                    e
            );
            return null;
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
            try {
                errorStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 从 /etc/os-release 文本中读取指定键的值。
     */
    private static String readOsReleaseValue(String content, String key) {
        if (content == null || content.isBlank() || key == null || key.isBlank()) {
            return null;
        }

        String[] lines = content.split("\\R");
        for (String line : lines) {
            if (line == null) {
                continue;
            }

            String trimmedLine = line.trim();
            if (trimmedLine.startsWith(key + "=")) {
                String value = trimmedLine.substring((key + "=").length()).trim();

                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }

                return value;
            }
        }

        return null;
    }

    /**
     * 将 /etc/os-release 中的 ID 映射为项目内部发行版枚举。
     */
    private static ALinux.LinuxDistribution parseLinuxDistribution(String distributionId) {
        if (distributionId == null || distributionId.isBlank()) {
            return ALinux.LinuxDistribution.UNKNOWN;
        }

        String normalizedId = distributionId.trim().toLowerCase(Locale.ROOT);

        return switch (normalizedId) {
            case "ubuntu" -> ALinux.LinuxDistribution.UBUNTU;
            case "debian" -> ALinux.LinuxDistribution.DEBIAN;
            case "fedora" -> ALinux.LinuxDistribution.FEDORA;
            case "centos" -> ALinux.LinuxDistribution.CENTOS;
            case "rhel", "redhat" -> ALinux.LinuxDistribution.RHEL;
            case "rocky" -> ALinux.LinuxDistribution.ROCKY;
            case "almalinux" -> ALinux.LinuxDistribution.ALMALINUX;
            case "ol", "oracle", "olinux", "oraclelinux" -> ALinux.LinuxDistribution.ORACLE;
            case "arch", "archlinux" -> ALinux.LinuxDistribution.ARCH;
            case "manjaro" -> ALinux.LinuxDistribution.MANJARO;
            case "linuxmint", "mint" -> ALinux.LinuxDistribution.MINT;
            case "opensuse", "opensuse-leap", "opensuse-tumbleweed", "sles", "sled" ->
                    ALinux.LinuxDistribution.OPENSUSE;
            case "alpine" -> ALinux.LinuxDistribution.ALPINE;
            case "kali" -> ALinux.LinuxDistribution.KALI;
            case "amzn", "amazon" -> ALinux.LinuxDistribution.AMAZON_LINUX;
            default -> ALinux.LinuxDistribution.UNKNOWN;
        };
    }
}