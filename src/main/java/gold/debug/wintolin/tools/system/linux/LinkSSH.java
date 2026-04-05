package gold.debug.wintolin.tools.system.linux;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.lang.reflect.Method;
import java.util.Properties;

public final class LinkSSH {

    private static final int DEFAULT_PORT = 22;
    private static final int DEFAULT_TIMEOUT_MS = 2 * 60 * 1000;

    private static final String ERROR_NULL_LINUX = "ERROR_NULL_LINUX";
    private static final String ERROR_EMPTY_HOST = "ERROR_EMPTY_HOST";
    private static final String ERROR_EMPTY_USER = "ERROR_EMPTY_USER";
    private static final String ERROR_NULL_PASSWORD = "ERROR_NULL_PASSWORD";
    private static final String ERROR_DISCONNECT_OLD_SESSION = "ERROR_DISCONNECT_OLD_SESSION";
    private static final String ERROR_CREATE_SESSION = "ERROR_CREATE_SESSION";
    private static final String ERROR_SET_PASSWORD = "ERROR_SET_PASSWORD";
    private static final String ERROR_SET_CONFIG = "ERROR_SET_CONFIG";
    private static final String ERROR_CONNECT_TIMEOUT = "ERROR_CONNECT_TIMEOUT";
    private static final String ERROR_AUTH_FAILED = "ERROR_AUTH_FAILED";
    private static final String ERROR_CONNECTION_REFUSED = "ERROR_CONNECTION_REFUSED";
    private static final String ERROR_UNKNOWN_HOST = "ERROR_UNKNOWN_HOST";
    private static final String ERROR_NO_ROUTE_TO_HOST = "ERROR_NO_ROUTE_TO_HOST";
    private static final String ERROR_CONNECT_SSH = "ERROR_CONNECT_SSH";
    private static final String ERROR_UNEXPECTED = "ERROR_UNEXPECTED";
    private static final String ERROR_DISCONNECT_SSH = "ERROR_DISCONNECT_SSH";

    private static final Method METHOD_CONNECT_SSH =
            MethodUtils.getCurrentMethod(LinkSSH.class, "connectSSH", ALinux.class);

    private static final Method METHOD_DISCONNECT_SSH =
            MethodUtils.getCurrentMethod(LinkSSH.class, "disconnectSSH", ALinux.class);

    private LinkSSH() {
    }

    /**
     * 连接 SSH
     * - 端口默认 22
     * - 超时默认 2 分钟
     * - 成功后写回 linux.setSession(session)
     *
     * 成功：正常返回
     * 失败：抛出 MyException
     */
    public static void connectSSH(ALinux linux) {
        if (linux == null) {
            MyException.fail(
                    LinkSSH.class,
                    METHOD_CONNECT_SSH,
                    "ALinux is null",
                    ERROR_NULL_LINUX
            );
        }

        String host = safeTrim(linux.getIp());
        String user = safeTrim(linux.getUser());
        String pass = linux.getPassword();

        if (isBlank(host)) {
            MyException.fail(
                    LinkSSH.class,
                    METHOD_CONNECT_SSH,
                    "IP/Host is empty",
                    ERROR_EMPTY_HOST
            );
        }

        if (isBlank(user)) {
            MyException.fail(
                    LinkSSH.class,
                    METHOD_CONNECT_SSH,
                    "User is empty",
                    ERROR_EMPTY_USER
            );
        }

        if (pass == null) {
            MyException.fail(
                    LinkSSH.class,
                    METHOD_CONNECT_SSH,
                    "Password is null",
                    ERROR_NULL_PASSWORD
            );
        }

        Session old = linux.getSession();
        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception e) {
                MyException.fail(
                        LinkSSH.class,
                        METHOD_CONNECT_SSH,
                        "Failed to disconnect old session",
                        ERROR_DISCONNECT_OLD_SESSION,
                        e
                );
            }
        }
        linux.setSession(null);

        Session session = null;
        try {
            JSch jsch = new JSch();

            try {
                session = jsch.getSession(user, host, DEFAULT_PORT);
            } catch (JSchException e) {
                MyException.fail(
                        LinkSSH.class,
                        METHOD_CONNECT_SSH,
                        "Failed to create SSH session",
                        ERROR_CREATE_SESSION,
                        e
                );
            }

            try {
                session.setPassword(pass);
            } catch (Exception e) {
                safeDisconnect(session);
                linux.setSession(null);
                MyException.fail(
                        LinkSSH.class,
                        METHOD_CONNECT_SSH,
                        "Failed to set SSH password",
                        ERROR_SET_PASSWORD,
                        e
                );
            }

            try {
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no");
                session.setConfig(config);
            } catch (Exception e) {
                safeDisconnect(session);
                linux.setSession(null);
                MyException.fail(
                        LinkSSH.class,
                        METHOD_CONNECT_SSH,
                        "Failed to set SSH config",
                        ERROR_SET_CONFIG,
                        e
                );
            }

            try {
                session.connect(DEFAULT_TIMEOUT_MS);
            } catch (JSchException e) {
                safeDisconnect(session);
                linux.setSession(null);
                throw convertJSchException(METHOD_CONNECT_SSH, e);
            }

            linux.setSession(session);

        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            safeDisconnect(session);
            linux.setSession(null);
            MyException.fail(
                    LinkSSH.class,
                    METHOD_CONNECT_SSH,
                    "Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    ERROR_UNEXPECTED,
                    e
            );
        }
    }

    /**
     * 断开 SSH
     *
     * 成功：正常返回
     * 失败：抛出 MyException
     */
    public static void disconnectSSH(ALinux linux) {
        if (linux == null) {
            MyException.fail(
                    LinkSSH.class,
                    METHOD_DISCONNECT_SSH,
                    "ALinux is null",
                    ERROR_NULL_LINUX
            );
        }

        Session session = linux.getSession();
        if (session != null) {
            try {
                session.disconnect();
            } catch (Exception e) {
                MyException.fail(
                        LinkSSH.class,
                        METHOD_DISCONNECT_SSH,
                        "Failed to disconnect SSH session",
                        ERROR_DISCONNECT_SSH,
                        e
                );
            }
        }

        linux.setSession(null);
    }

    private static MyException convertJSchException(Method sourceMethod, JSchException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();

        if (msg.contains("auth fail")) {
            return new MyException(
                    LinkSSH.class,
                    sourceMethod,
                    "Authentication failed: wrong username or password",
                    ERROR_AUTH_FAILED,
                    e
            );
        }

        if (msg.contains("timeout") || msg.contains("socket is not established")) {
            return new MyException(
                    LinkSSH.class,
                    sourceMethod,
                    "Connection timeout or network unreachable",
                    ERROR_CONNECT_TIMEOUT,
                    e
            );
        }

        if (msg.contains("connection refused")) {
            return new MyException(
                    LinkSSH.class,
                    sourceMethod,
                    "Connection refused: SSH service may be down or port 22 blocked",
                    ERROR_CONNECTION_REFUSED,
                    e
            );
        }

        if (msg.contains("unknownhostexception") || msg.contains("unknown host")) {
            return new MyException(
                    LinkSSH.class,
                    sourceMethod,
                    "Unknown host: cannot resolve IP/host",
                    ERROR_UNKNOWN_HOST,
                    e
            );
        }

        if (msg.contains("no route to host")) {
            return new MyException(
                    LinkSSH.class,
                    sourceMethod,
                    "No route to host: network routing issue or firewall",
                    ERROR_NO_ROUTE_TO_HOST,
                    e
            );
        }

        return new MyException(
                LinkSSH.class,
                sourceMethod,
                "SSH connect failed: " + e.getMessage(),
                ERROR_CONNECT_SSH,
                e
        );
    }

    private static void safeDisconnect(Session session) {
        if (session != null) {
            try {
                session.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeTrim(String value) {
        return value == null ? null : value.trim();
    }
}