package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.ai.collaboration.AiCollaborationRole;

import java.util.Arrays;
import java.util.Objects;

/**
 * Provides the {@code AiPageState} implementation.
 *
 * <p>提供 {@code AiPageState} 实现。
 */
public final class AiPageState implements AutoCloseable {
    private final String endpoint;
    private final String model;
    private final String providerId;
    private final AiCollaborationRole role;
    private final char[] apiKey;
    private final CredentialStorageMode credentialMode;
    private final char[] masterPassword;
    private final String output;

    /**
     * Creates a {@code AiPageState} instance.
     *
     * <p>创建 {@code AiPageState} 实例。
     *
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param model the {@code model} value / {@code model} 值
     * @param apiKey the {@code apiKey} value / {@code apiKey} 值
     * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param output the {@code output} value / {@code output} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AiPageState(String endpoint, String model, String providerId, AiCollaborationRole role,
                       char[] apiKey, CredentialStorageMode credentialMode,
                       char[] masterPassword, String output) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.role = Objects.requireNonNull(role, "role");
        this.apiKey = apiKey.clone();
        this.credentialMode = Objects.requireNonNull(credentialMode, "credentialMode");
        this.masterPassword = masterPassword.clone();
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Performs the {@code endpoint} operation.
     *
     * <p>执行 {@code endpoint} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String endpoint() { return endpoint; }
    /**
     * Performs the {@code model} operation.
     *
     * <p>执行 {@code model} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String model() { return model; }
    /** Returns the unsaved named-provider identifier. / 返回未保存的命名提供者标识。 */
    public String providerId() { return providerId; }
    /** Returns the selected fixed collaboration role. / 返回所选固定协作角色。 */
    public AiCollaborationRole role() { return role; }
    /**
     * Performs the {@code apiKey} operation.
     *
     * <p>执行 {@code apiKey} 操作。
     *
     * @return the operation result / 操作结果
     */
    public char[] apiKey() { return apiKey.clone(); }
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
        Arrays.fill(apiKey, '\0');
        Arrays.fill(masterPassword, '\0');
    }
}
