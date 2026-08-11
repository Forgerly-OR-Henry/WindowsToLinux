package gold.debug.windowstolinux.app.service.concurrency;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides the {@code ServerOperationLocks} implementation.
 *
 * <p>提供 {@code ServerOperationLocks} 实现。
 */
public final class ServerOperationLocks {
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Performs the {@code forServer} operation.
     *
     * <p>执行 {@code forServer} 操作。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ReentrantLock forServer(String serverId) {
        return locks.computeIfAbsent(Objects.requireNonNull(serverId, "serverId"), ignored -> new ReentrantLock());
    }
}
