package gold.debug.wintolin.tools.system.linux;

import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.lang.reflect.Method;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * 用于校验 ALinux 中的 ip 字段是否合法。
 * 支持：
 * 1. IPv4
 * 2. IPv6
 * 3. 域名（含 IDN）
 *
 * 不允许：
 * 1. 协议头（如 http://、https://、ssh://）
 * 2. 路径、反斜杠、空格
 * 3. 中括号形式
 * 4. 端口
 */
public final class JudgeIPRight {

    private JudgeIPRight() {
    }

    /**
     * 默认错误码命名规则：
     * ERROR_ + 问题简称
     */
    private static final String ERROR_NULL_LINUX = "ERROR_NULL_LINUX";
    private static final String ERROR_NULL_HOST = "ERROR_NULL_HOST";
    private static final String ERROR_EMPTY_HOST = "ERROR_EMPTY_HOST";
    private static final String ERROR_SCHEME_NOT_ALLOWED = "ERROR_SCHEME_NOT_ALLOWED";
    private static final String ERROR_INVALID_CHAR = "ERROR_INVALID_CHAR";
    private static final String ERROR_BRACKET_NOT_ALLOWED = "ERROR_BRACKET_NOT_ALLOWED";
    private static final String ERROR_INVALID_HOST = "ERROR_INVALID_HOST";
    private static final String ERROR_PORT_NOT_ALLOWED = "ERROR_PORT_NOT_ALLOWED";
    private static final String ERROR_INVALID_IPV6 = "ERROR_INVALID_IPV6";
    private static final String ERROR_INVALID_IP_OR_HOST = "ERROR_INVALID_IP_OR_HOST";
    private static final String ERROR_NOT_DOMAIN = "ERROR_NOT_DOMAIN";

    /**
     * 当前类中需要记录的方法对象
     */
    private static final Method METHOD_IS_IP_RIGHT =
            MethodUtils.getCurrentMethod(JudgeIPRight.class, "isIPRight", ALinux.class);

    private static final Method METHOD_IS_DOMAIN_NAME =
            MethodUtils.getCurrentMethod(JudgeIPRight.class, "isDomainName", ALinux.class);

    /**
     * 严格 IPv4 正则：
     * 每段范围 0~255
     */
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
    );

    /**
     * ASCII 域名正则：
     * 1. 总长度 1~253
     * 2. 每个 label 长度 1~63
     * 3. label 不能以 '-' 开头或结尾
     * 4. 末尾顶级域名长度 2~63
     */
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)(?:(?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+(?:[A-Za-z]{2,63})$"
    );

    /**
     * 检查 ALinux.ip 是否为合法的 IPv4 / IPv6 / 域名。
     *
     * 校验通过：正常返回
     * 校验失败：抛出 MyException
     */
    public static void isIPRight(ALinux linux) {
        if (linux == null) {
            MyException.fail(JudgeIPRight.class, METHOD_IS_IP_RIGHT, "ALinux is null", ERROR_NULL_LINUX);
        }

        String raw = linux.getIp();
        if (raw == null) {
            MyException.fail(JudgeIPRight.class, METHOD_IS_IP_RIGHT, "IP/Host is null", ERROR_NULL_HOST);
        }

        String host = raw.trim();
        if (host.isEmpty()) {
            MyException.fail(JudgeIPRight.class, METHOD_IS_IP_RIGHT, "IP/Host is empty", ERROR_EMPTY_HOST);
        }

        // 不允许协议头
        if (containsScheme(host)) {
            MyException.fail(
                    JudgeIPRight.class,
                    METHOD_IS_IP_RIGHT,
                    "IP/Host must NOT contain scheme like http:// or https://",
                    ERROR_SCHEME_NOT_ALLOWED
            );
        }

        // 不允许路径、反斜杠、空格
        if (host.contains("/") || host.contains("\\") || host.contains(" ")) {
            MyException.fail(
                    JudgeIPRight.class,
                    METHOD_IS_IP_RIGHT,
                    "IP/Host must NOT contain path, backslash, or spaces",
                    ERROR_INVALID_CHAR
            );
        }

        // 不允许 []
        if (host.contains("[") || host.contains("]")) {
            MyException.fail(
                    JudgeIPRight.class,
                    METHOD_IS_IP_RIGHT,
                    "IP/Host must NOT contain '[' or ']'",
                    ERROR_BRACKET_NOT_ALLOWED
            );
        }

        // 去掉末尾点（FQDN：example.com.）
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) {
                MyException.fail(
                        JudgeIPRight.class,
                        METHOD_IS_IP_RIGHT,
                        "IP/Host is invalid",
                        ERROR_INVALID_HOST
                );
            }
        }

        // 1. IPv4
        if (IPV4_PATTERN.matcher(host).matches()) {
            return;
        }

        // 2. 含冒号：可能是 IPv6，也可能是 host:port
        if (host.contains(":")) {
            int first = host.indexOf(':');
            int last = host.lastIndexOf(':');

            // 只有一个冒号，且右边全是数字，很可能是 host:22
            if (first == last) {
                String right = host.substring(first + 1);
                if (right.matches("\\d+")) {
                    MyException.fail(
                            JudgeIPRight.class,
                            METHOD_IS_IP_RIGHT,
                            "Port is NOT allowed (e.g., host:22)",
                            ERROR_PORT_NOT_ALLOWED
                    );
                }
            }

            // zone id 不允许，例如 fe80::1%eth0
            if (host.contains("%")) {
                MyException.fail(
                        JudgeIPRight.class,
                        METHOD_IS_IP_RIGHT,
                        "IPv6 zone id is NOT allowed (e.g., %eth0)",
                        ERROR_INVALID_IPV6
                );
            }

            if (isValidIPv6Literal(host)) {
                return;
            }

            MyException.fail(
                    JudgeIPRight.class,
                    METHOD_IS_IP_RIGHT,
                    "Invalid IPv6 format or contains port",
                    ERROR_INVALID_IPV6
            );
        }

        // 3. 域名
        if (isDomainString(host)) {
            return;
        }

        MyException.fail(
                JudgeIPRight.class,
                METHOD_IS_IP_RIGHT,
                "IP/Host format is invalid (not IPv4/IPv6/domain)",
                ERROR_INVALID_IP_OR_HOST
        );
    }

    /**
     * 检查 ALinux.ip 是否为域名。
     *
     * 校验通过：正常返回
     * 校验失败：抛出 MyException
     */
    public static void isDomainName(ALinux linux) {
        if (linux == null) {
            MyException.fail(JudgeIPRight.class, METHOD_IS_DOMAIN_NAME, "ALinux is null", ERROR_NULL_LINUX);
        }

        String raw = linux.getIp();
        if (raw == null) {
            MyException.fail(JudgeIPRight.class, METHOD_IS_DOMAIN_NAME, "IP/Host is null", ERROR_NULL_HOST);
        }

        String host = raw.trim();
        if (host.isEmpty()) {
            MyException.fail(JudgeIPRight.class, METHOD_IS_DOMAIN_NAME, "IP/Host is empty", ERROR_EMPTY_HOST);
        }

        // 去掉末尾点（example.com.）
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) {
                MyException.fail(JudgeIPRight.class, METHOD_IS_DOMAIN_NAME, "Not a domain name", ERROR_NOT_DOMAIN);
            }
        }

        if (isDomainString(host)) {
            return;
        }

        MyException.fail(JudgeIPRight.class, METHOD_IS_DOMAIN_NAME, "Not a domain name", ERROR_NOT_DOMAIN);
    }

    /**
     * 判断是否包含协议头
     */
    private static boolean containsScheme(String value) {
        return value.contains("://");
    }

    /**
     * 判断是否为合法域名字符串。
     * 支持 IDN（国际化域名），会先转换为 ASCII 再校验。
     */
    private static boolean isDomainString(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }

        try {
            String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            return DOMAIN_PATTERN.matcher(ascii).matches();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 判断是否为合法 IPv6 字面量。
     */
    private static boolean isValidIPv6Literal(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address instanceof Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }
}