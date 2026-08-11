package gold.debug.windowstolinux.shared.git.snapshot;

/**
 * A safe Git snapshot failure that intentionally excludes remotes and credential material from its message.
 *
 * <p>安全的 Git 快照失败；其消息刻意不含远端或凭据材料。
 */
public final class GitSnapshotException extends Exception {
    /**
     * Creates a {@code GitSnapshotException} instance.
     *
     * <p>创建 {@code GitSnapshotException} 实例。
     *
     * @param message the generic failure message / 通用失败消息
     * @param cause the underlying cause / 底层原因
     */
    public GitSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
