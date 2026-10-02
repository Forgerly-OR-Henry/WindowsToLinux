package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.io.IOException;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.PropertyResolver;
import org.apache.sshd.common.RuntimeSshException;
import org.apache.sshd.common.io.AbstractIoServiceFactoryFactory;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.io.IoConnector;
import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.IoServiceEventListener;
import org.apache.sshd.common.io.IoServiceFactory;
import org.apache.sshd.common.io.nio2.Nio2Connector;
import org.apache.sshd.common.io.nio2.Nio2Session;
import org.apache.sshd.common.util.closeable.AbstractCloseable;

/** Owns the actual client I/O group independently of SSHD's logical close future. / 独立于 SSHD 逻辑关闭凭据持有真实客户端 I/O 群组。 */
final class ClientIoLifecycle extends AbstractIoServiceFactoryFactory {
    /** Unique names identify every owned completion and resumption thread. / 唯一名称标识每个所属完成及恢复线程。 */
    private final String prefix = "platform-ssh-" + UUID.randomUUID();

    /** Actual threads, retained until they have physically exited. / 实际线程，保留至物理退出。 */
    private final ConcurrentLinkedQueue<Thread> workers = new ConcurrentLinkedQueue<>();

    /** Monotonic thread names within this connection. / 此连接内的单调线程名称序号。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** Fixed group owns its actual executor, including Windows IOCP handler threads. / 固定群组持有真实执行器，包括 Windows IOCP 处理线程。 */
    private AsynchronousChannelGroup group;

    /** Connection waiters and suspended-read resumptions. / 连接等待及暂停读取的恢复工作。 */
    private ExecutorService resume;

    /** Prevents starting resources after physical cleanup has begun. / 物理清理开始后阻止启动资源。 */
    private boolean sealed;

    /** Creates no threads before the SSH client starts. / SSH 客户端启动前不创建线程。 */
    ClientIoLifecycle() {
        super(null);
    }

    /**
     * Creates the sole logical factory backed by this connection's physical resources. / 创建以此连接物理资源为后端的唯一逻辑工厂。
     * @param manager actual SSH client / 实际 SSH 客户端
     * @return logical factory whose closure leaves physical ownership with the connection / 逻辑工厂，关闭后物理资源仍由连接持有
     * @throws RuntimeSshException if the actual group cannot be created / 无法创建真实群组时
     */
    @Override
    public synchronized IoServiceFactory create(FactoryManager manager) {
        if (sealed || group != null)
            throw new IllegalStateException("SSH client I/O owner cannot be restarted");
        try {
            // Fixed groups have no unbound Windows IOCP thread; await observes their real pool.
            // 固定群组不创建未绑定的 Windows IOCP 线程，等待结果反映真实线程池结束。
            group = AsynchronousChannelGroup.withFixedThreadPool(2, runnable -> worker(runnable, "io"));
            resume = Executors.newFixedThreadPool(2, runnable -> worker(runnable, "resume"));
            return new Factory(manager);
        } catch (IOException failure) {
            throw new RuntimeSshException(failure);
        }
    }

    /**
     * Closes native channels, then proves completion and resumption workers have returned. / 关闭原生通道，再证明完成及恢复线程均已返回。
     * @param timeout remaining physical cleanup budget / 物理清理剩余预算
     * @return true only after every owned worker has exited; false remains retryable / 仅所有所属线程退出后为 true，false 可重试
     * @throws IOException if native channel closure fails / 原生通道关闭失败时
     * @throws InterruptedException if the cleanup observer is interrupted / 清理观察线程被中断时
     */
    synchronized boolean close(Duration timeout) throws IOException, InterruptedException {
        sealed = true;
        if (workers.contains(Thread.currentThread()))
            return false;
        long deadline = System.nanoTime() + timeout.toNanos();
        if (group != null) {
            group.shutdownNow();
            if (!group.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS))
                return false;
        }
        if (resume != null) {
            resume.shutdown();
            if (!resume.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS))
                return false;
        }
        for (Thread thread : workers) {
            if (thread.isAlive()) {
                long nanos = remaining(deadline);
                if (nanos == 0)
                    return false;
                thread.join(Duration.ofNanos(nanos));
            }
            if (thread.isAlive())
                return false;
        }
        return true;
    }

    /**
     * Creates a daemon worker without hiding its ownership or lifetime. / 创建守护线程，保留其所属关系及生命周期。
     * @param runnable actual executor task / 实际执行器工作
     * @param role completion or resumption role / 完成或恢复职责
     * @return tracked thread before it starts / 启动前已登记的线程
     */
    private Thread worker(Runnable runnable, String role) {
        Thread thread = new Thread(runnable, prefix + "-" + role + "-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        workers.add(thread);
        return thread;
    }

    /**
     * Returns a nonnegative wait budget without extending the original deadline. / 返回非负等待预算，不延长原截止时间。
     * @param deadline original monotonic deadline / 原单调截止时间
     * @return nanoseconds left / 剩余纳秒
     */
    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** Logical SSHD admission facade; physical release belongs to the enclosing connection. / SSHD 逻辑准入门面，物理释放由外层连接负责。 */
    private final class Factory extends AbstractCloseable implements IoServiceFactory {
        /** Actual client properties used by the original NIO2 sessions. / 原 NIO2 会话使用的实际客户端属性。 */
        private final FactoryManager manager;

        /** Client-owned transport event listener. / 客户端所属传输事件监听器。 */
        private IoServiceEventListener listener;

        /** Binds original client properties and events. / 绑定原客户端属性及事件。
         * @param manager actual client / 实际客户端 */
        private Factory(FactoryManager manager) {
            this.manager = manager;
            listener = manager.getIoServiceEventListener();
        }

        /** Creates an original NIO2 connector using the physically owned group. / 使用物理所属群组创建原 NIO2 连接器。
         * @param handler original SSH handler / 原 SSH 处理器
         * @return connector without an unused default factory or protected executor / 不创建未使用默认工厂或执行器包装的连接器 */
        @Override
        public IoConnector createConnector(IoHandler handler) {
            var connector = new Nio2Connector(null, manager, handler, group, resume) {
                /** Uses the verified SSHD 2.14.0 extension point instead of its concrete factory field. / 使用已核实的 SSHD 2.14.0 扩展点，替代其具体工厂字段。
                 * @param properties original properties / 原属性
                 * @param actualHandler original handler / 原处理器
                 * @param socket actual group-owned socket / 真实群组所属 socket
                 * @return original NIO2 session / 原 NIO2 会话
                 * @throws Throwable if session construction fails / 会话构造失败时 */
                @Override
                protected Nio2Session createSession(PropertyResolver properties, IoHandler actualHandler,
                                                    AsynchronousSocketChannel socket) throws Throwable {
                    // SSHD 2.14.0 assigns its concrete factory only in the constructor and reads it only here.
                    // SSHD 2.14.0 仅在构造器保存具体工厂，并仅在此扩展点读取它。
                    // The same session is constructed without an unused factory or NoClose group.
                    // 此处直接创建相同会话，不创建未使用的工厂或 NoClose 群组。
                    return new Nio2Session(this, properties, actualHandler, socket, null);
                }
            };
            connector.setIoServiceEventListener(listener);
            return connector;
        }

        /** Rejects server listeners outside this client's capability. / 拒绝此客户端能力之外的服务端监听器。
         * @param handler requested handler / 请求的处理器
         * @return no server acceptor / 不返回服务端接入器
         * @throws UnsupportedOperationException because this transport owns outbound connections / 此传输仅持有出站连接时 */
        @Override
        public IoAcceptor createAcceptor(IoHandler handler) {
            throw new UnsupportedOperationException("SSH client transport does not accept connections");
        }

        /** Returns the original transport listener. / 返回原传输监听器。
         * @return current listener / 当前监听器 */
        @Override
        public IoServiceEventListener getIoServiceEventListener() {
            return listener;
        }

        /** Replaces the transport listener before connector creation. / 在连接器创建前替换传输监听器。
         * @param listener original SSHD event listener / 原 SSHD 事件监听器 */
        @Override
        public void setIoServiceEventListener(IoServiceEventListener listener) {
            this.listener = listener;
        }
    }
}
