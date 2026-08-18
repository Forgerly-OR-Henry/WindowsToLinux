package gold.debug.windowstolinux.app.secret;

import java.util.Optional;

/**
 * Platform credential boundary. Callers receive copies and must clear them after use.
 *
 * <p>平台凭据边界。调用方收到副本，并且必须在使用后清除。
 */
public interface SecretStore extends AutoCloseable {
    /**
     * Stores data through {@code save}.
     *
     * <p>通过 {@code save} 保存数据。
     *
     * @param key the {@code key} value / {@code key} 值
     * @param value the {@code value} value / {@code value} 值
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    void save(String key, char[] value) throws SecretStoreException;

    /**
     * Returns the value produced by {@code read}.
     *
     * <p>返回 {@code read} 生成的值。
     *
     * @param key the {@code key} value / {@code key} 值
     * @return the optional operation result / 可选操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    Optional<char[]> read(String key) throws SecretStoreException;

    /** Closes this resource. / 关闭此资源。 */
    @Override
    void close();
}
