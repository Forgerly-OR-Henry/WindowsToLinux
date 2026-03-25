package gold.debug.wintolin.tools.system.linux;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.exceptionanderror.MyResult;

import java.util.Properties;

public class LinkSSH {
    // 默认配置：端口 22，超时 2 分钟
    private static final int DEFAULT_PORT = 22;
    private static final int DEFAULT_TIMEOUT_MS = 2 * 60 * 1000; // 120000

    /**
     * 连接 SSH：只传入 ALinux
     * - 端口默认 22
     * - 超时默认 2 分钟
     * 成功后写回 linux.setSession(session)
     *
     * @return 成功 true，失败 false
     */
    public static MyResult connectSSH(ALinux linux) {
        if (linux == null) return MyResult.fail("ALinux is null");

        String host = safeTrim(linux.getIp());
        String user = safeTrim(linux.getUser());
        String pass = linux.getPassword();

        if (isBlank(host)) return MyResult.fail("IP/Host is empty");
        if (isBlank(user)) return MyResult.fail("User is empty");
        if (pass == null) return MyResult.fail("Password is null");

        // 断开旧连接
        Session old = linux.getSession();
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
        }
        linux.setSession(null);

        Session session = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(user, host, DEFAULT_PORT);
            session.setPassword(pass);

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no"); // 生产环境建议开启 host key 校验
            session.setConfig(config);

            session.connect(DEFAULT_TIMEOUT_MS);

            linux.setSession(session);
            return MyResult.ok();

        } catch (JSchException e) {
            // 失败时确保清理
            try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
            linux.setSession(null);

            // 尽量把常见问题翻译成“可给用户看的文本”
            return MyResult.fail(prettyProblem(e));

        } catch (Exception e) {
            try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
            linux.setSession(null);
            return MyResult.fail("Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static void disconnectSSH(ALinux linux) {
        if (linux == null) return;
        Session s = linux.getSession();
        if (s != null) {
            try { s.disconnect(); } catch (Exception ignored) {}
        }
        linux.setSession(null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeTrim(String s) {
        return s == null ? null : s.trim();
    }

    /**
     * 把 JSchException 常见报错转换成更直观的说明
     */
    private static String prettyProblem(JSchException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();

        // 认证失败
        if (msg.contains("auth fail")) {
            return "Authentication failed: wrong username or password";
        }

        // 超时
        if (msg.contains("timeout") || msg.contains("socket is not established")) {
            return "Connection timeout or network unreachable";
        }

        // 连接被拒绝（端口不通/SSH服务没开）
        if (msg.contains("connection refused")) {
            return "Connection refused: SSH service may be down or port 22 blocked";
        }

        // unknown host / no route
        if (msg.contains("unknownhostexception") || msg.contains("unknown host")) {
            return "Unknown host: cannot resolve IP/host";
        }
        if (msg.contains("no route to host")) {
            return "No route to host: network routing issue or firewall";
        }

        // 默认兜底
        return "SSH connect failed: " + e.getMessage();
    }
}
