package gold.debug.windowstolinux.shared.model.message;

/**
 * A user-facing summary plus a secret-free technical diagnostic.
 *
 * <p>用户可见摘要以及无秘密技术诊断。
 */
public interface LocalizedFailure {
    /**
     * Performs the {@code userMessage} operation.
     *
     * <p>执行 {@code userMessage} 操作。
     *
     * @return the operation result / 操作结果
     */
    LocalizedMessage userMessage();

    /**
     * Performs the {@code diagnostic} operation.
     *
     * <p>执行 {@code diagnostic} 操作。
     *
     * @return the operation result / 操作结果
     */
    String diagnostic();
}
