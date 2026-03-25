package gold.debug.wintolin.tools.system.linux;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.regex.Pattern;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.exceptionanderror.MyResult;

public final class JudgeIPRight {
    private JudgeIPRight() {}

    // IPv4（严格 0-255）
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
    );

    // 域名（ASCII）：多级 label + TLD（2~63），label 不能以 - 开头/结尾，总长 <=253
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)(?:(?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+(?:[A-Za-z]{2,63})$"
    );

    /**
     * 检查 ALinux.ip 格式是否正确：
     * - 允许：IPv4 / IPv6 / 域名
     * - 不允许：协议头(如 http://)、路径(/xxx)、反斜杠、空格、端口(如 a.com:22 / 1.2.3.4:22)
     */
    public static MyResult isIPRight(ALinux linux) {
        if (linux == null) return MyResult.fail("ALinux is null");

        String raw = linux.getIp();
        if (raw == null) return MyResult.fail("IP/Host is null");

        String host = raw.trim();
        if (host.isEmpty()) return MyResult.fail("IP/Host is empty");

        // 不允许协议头
        if (containsScheme(host)) {
            return MyResult.fail("IP/Host must NOT contain scheme like http:// or https://");
        }

        // 不允许路径、反斜杠、空格
        if (host.contains("/") || host.contains("\\") || host.contains(" ")) {
            return MyResult.fail("IP/Host must NOT contain path, backslash, or spaces");
        }

        // 不允许 []（通常用于 [IPv6]:port 形式）
        if (host.contains("[") || host.contains("]")) {
            return MyResult.fail("IP/Host must NOT contain '[' or ']'");
        }

        // 去掉末尾点（FQDN 写法 example.com.）
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) return MyResult.fail("IP/Host is invalid");
        }

        // 1) IPv4
        if (IPV4_PATTERN.matcher(host).matches()) {
            return MyResult.ok();
        }

        // 2) 含冒号：要么 IPv6，要么 host:port（但端口不允许）
        if (host.contains(":")) {
            // 如果像 “example.com:22” 这种只有一个冒号且右侧全是数字，判定为端口 -> 不允许
            int first = host.indexOf(':');
            int last = host.lastIndexOf(':');
            if (first == last) {
                String right = host.substring(first + 1);
                if (right.matches("\\d+")) {
                    return MyResult.fail("Port is NOT allowed (e.g., host:22)");
                }
            }

            // 进一步判断是否为 IPv6 字面量（不做 DNS）
            if (isValidIPv6Literal(host)) {
                // 也不允许 zone id（如 fe80::1%eth0）
                if (host.contains("%")) {
                    return MyResult.fail("IPv6 zone id is NOT allowed (e.g., %eth0)");
                }
                return MyResult.ok();
            }

            return MyResult.fail("Invalid IPv6 format or contains port");
        }

        // 3) 域名（包含 IDN：如 中文域名会先转 ASCII 再校验）
        if (isDomainString(host)) {
            return MyResult.ok();
        }

        return MyResult.fail("IP/Host format is invalid (not IPv4/IPv6/domain)");
    }

    /**
     * 仅判断 ALinux.ip 是否为域名（不负责“是否带端口/路径/协议”等输入清洗）
     * - 返回 ok() 表示是域名
     * - fail(...) 表示不是域名
     */
    public static MyResult isDomainName(ALinux linux) {
        if (linux == null) return MyResult.fail("ALinux is null");

        String raw = linux.getIp();
        if (raw == null) return MyResult.fail("IP/Host is null");

        String host = raw.trim();
        if (host.isEmpty()) return MyResult.fail("IP/Host is empty");

        // 去掉末尾点（example.com.）
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) return MyResult.fail("Not a domain name");
        }

        if (isDomainString(host)) {
            return MyResult.ok();
        }
        return MyResult.fail("Not a domain name");
    }

    // ---------------- helpers ----------------

    private static boolean containsScheme(String s) {
        // 比如 http://, https://, ssh:// 等
        int idx = s.indexOf("://");
        return idx >= 0;
    }

    private static boolean isDomainString(String host) {
        // 你可以决定是否允许 localhost；这里我保留为“算域名/主机名”
        if ("localhost".equalsIgnoreCase(host)) return true;

        try {
            // 支持 IDN：中文域名等（转成 punycode 再按 ASCII 域名规则校验）
            String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            return DOMAIN_PATTERN.matcher(ascii).matches();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isValidIPv6Literal(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr instanceof Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }
}
