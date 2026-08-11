package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.util.Arrays;
import java.util.Objects;

/**
 * Provides the {@code ServerPageState} implementation.
 *
 * <p>提供 {@code ServerPageState} 实现。
 */
public final class ServerPageState implements AutoCloseable {
    private final String id;
    private final String host;
    private final String port;
    private final String username;
    private final char[] password;
    private final CredentialStorageMode credentialMode;
    private final char[] masterPassword;
    private final String output;

    /**
     * Creates a {@code ServerPageState} instance.
     *
     * <p>创建 {@code ServerPageState} 实例。
     *
     * @param id the {@code id} value / {@code id} 值
     * @param host the {@code host} value / {@code host} 值
     * @param port the {@code port} value / {@code port} 值
     * @param username the {@code username} value / {@code username} 值
     * @param password the {@code password} value / {@code password} 值
     * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param output the {@code output} value / {@code output} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ServerPageState(String id, String host, String port, String username, char[] password,
                           CredentialStorageMode credentialMode, char[] masterPassword, String output) {
        this.id = Objects.requireNonNull(id, "id");
        this.host = Objects.requireNonNull(host, "host");
        this.port = Objects.requireNonNull(port, "port");
        this.username = Objects.requireNonNull(username, "username");
        this.password = password.clone();
        this.credentialMode = Objects.requireNonNull(credentialMode, "credentialMode");
        this.masterPassword = masterPassword.clone();
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Performs the {@code id} operation.
     *
     * <p>执行 {@code id} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String id() { return id; }
    /**
     * Performs the {@code host} operation.
     *
     * <p>执行 {@code host} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String host() { return host; }
    /**
     * Performs the {@code port} operation.
     *
     * <p>执行 {@code port} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String port() { return port; }
    /**
     * Performs the {@code username} operation.
     *
     * <p>执行 {@code username} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String username() { return username; }
    /**
     * Performs the {@code password} operation.
     *
     * <p>执行 {@code password} 操作。
     *
     * @return the operation result / 操作结果
     */
    public char[] password() { return password.clone(); }
    /**
     * Performs the {@code credentialMode} operation.
     *
     * <p>执行 {@code credentialMode} 操作。
     *
     * @return the operation result / 操作结果
     */
    public CredentialStorageMode credentialMode() { return credentialMode; }
    /**
     * Performs the {@code masterPassword} operation.
     *
     * <p>执行 {@code masterPassword} 操作。
     *
     * @return the operation result / 操作结果
     */
    public char[] masterPassword() { return masterPassword.clone(); }
    /**
     * Performs the {@code output} operation.
     *
     * <p>执行 {@code output} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String output() { return output; }

    @Override public void close() {
        Arrays.fill(password, '\0');
        Arrays.fill(masterPassword, '\0');
    }
}
