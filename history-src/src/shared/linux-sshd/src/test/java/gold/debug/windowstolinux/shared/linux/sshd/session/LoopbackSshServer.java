package gold.debug.windowstolinux.shared.linux.sshd.session;

import java.io.IOException;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.io.*;
import org.apache.sshd.common.io.nio2.*;
import org.apache.sshd.common.util.threads.CloseableExecutorService;
import org.apache.sshd.common.util.threads.ThreadUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/** Test-owned listeners and NIO resources; all accept callbacks drain before executor shutdown. */
public final class LoopbackSshServer implements AutoCloseable {
    private final BackgroundExceptionScope exceptions;

    private final String threadPrefix = "loopback-" + UUID.randomUUID();

    private final CloseableExecutorService io = ThreadUtils.newFixedThreadPool(threadPrefix + "-io", 4);

    private final CloseableExecutorService resume = ThreadUtils.newFixedThreadPool(threadPrefix + "-resume", 2);

    private final ScheduledExecutorService timer = ThreadUtils
            .newSingleThreadScheduledExecutor(threadPrefix + "-timer");

    private AsynchronousChannelGroup group;

    private final SshServer server = SshServer.setUpDefaultServer();

    private final Object accepts = new Object();

    private int pending;

    private Nio2Acceptor listener;

    private boolean closed;

    private volatile Runnable beforeCompletion = () -> {
    };

    final CountDownLatch listenerStopped = new CountDownLatch(1);

    public LoopbackSshServer(Path directory, Consumer<SshServer> configure) throws Exception {
        exceptions = new BackgroundExceptionScope();
        try {
            group = AsynchronousChannelGroup.withThreadPool(ThreadUtils.noClose(io));
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setScheduledExecutorService(timer, false);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(directory.resolve("host-key")));
            server.setPasswordAuthenticator((user, password, session) -> true);
            server.setIoServiceFactoryFactory(new AbstractIoServiceFactoryFactory(null) {
                @Override
                public IoServiceFactory create(FactoryManager manager) {
                    // The base factory's private group remains unused; its public close owns that group.
                    // Protected executor wrappers keep both groups alive until callbacks have drained.
                    return new Nio2ServiceFactory(manager, ThreadUtils.noClose(io), ThreadUtils.noClose(resume)) {
                        @Override
                        public IoAcceptor createAcceptor(IoHandler handler) {
                            listener = new Nio2Acceptor(this, manager, handler, group, resume) {
                                @Override
                                protected AsynchronousServerSocketChannel openAsynchronousServerSocketChannel(
                                        SocketAddress address, AsynchronousChannelGroup channels) throws IOException {
                                    return new TrackedListener(AsynchronousServerSocketChannel.open(channels));
                                }
                            };
                            return autowireCreatedService(listener);
                        }
                    };
                }
            });
            configure.accept(server);
            server.start();
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public SshServer server() {
        return server;
    }

    void beforeCompletion(Runnable hook) {
        beforeCompletion = hook;
    }

    boolean executorsShutdown() {
        return io.isShutdown() || resume.isShutdown();
    }

    int pendingAccepts() {
        synchronized (accepts) {
            return pending;
        }
    }

    private void beginAccept() {
        synchronized (accepts) {
            pending++;
        }
    }

    private void endAccept() {
        synchronized (accepts) {
            pending--;
            accepts.notifyAll();
        }
    }

    private void drain(long deadline) throws InterruptedException {
        synchronized (accepts) {
            while (pending != 0)
                TimeUnit.NANOSECONDS.timedWait(accepts, remaining(deadline));
        }
    }

    private static long remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0)
            throw new AssertionError("Loopback SSH shutdown deadline exceeded");
        return nanos;
    }

    @Override
    public void close() throws Exception {
        if (closed)
            return;
        closed = true;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Throwable failure = null;
        int port = server.getPort();
        try {
            if (listener != null)
                listener.unbind();
            listenerStopped.countDown();
            drain(deadline);
        } catch (Throwable problem) {
            failure = problem;
        }
        try {
            if (!server.close(true).await(Duration.ofNanos(remaining(deadline))))
                throw new AssertionError("SSH server did not close");
        } catch (Throwable problem) {
            failure = combine(failure, problem);
        }
        try {
            if (group != null) {
                group.shutdownNow();
                if (!group.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS))
                    throw new AssertionError("NIO group did not close");
            }
        } catch (Throwable problem) {
            failure = combine(failure, problem);
        }
        io.shutdown();
        resume.shutdown();
        timer.shutdown();
        try {
            if (!io.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)
                    || !resume.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)
                    || !timer.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS))
                throw new AssertionError("SSH executor did not close");
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().contains(threadPrefix)) {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining(deadline));
                    if (thread.isAlive())
                        throw new AssertionError("Leaked loopback thread: " + thread.getName());
                }
            }
            if (port > 0)
                try (Socket socket = new Socket()) {
                    try {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    } catch (ConnectException expected) {
                        /* A refused connection proves the listener is gone. */ }
                    if (socket.isConnected())
                        throw new AssertionError("Loopback listener is still reachable");
                }
        } catch (Throwable problem) {
            failure = combine(failure, problem);
        } finally {
            if (!io.isTerminated())
                io.shutdownNow();
            if (!resume.isTerminated())
                resume.shutdownNow();
            if (!timer.isTerminated())
                timer.shutdownNow();
        }
        returnAfterCleanup(failure);
    }

    private void returnAfterCleanup(Throwable failure) throws Exception {
        try {
            exceptions.close();
        } catch (Throwable problem) {
            failure = combine(failure, problem);
        }
        if (failure instanceof Error error)
            throw error;
        if (failure instanceof Exception exception)
            throw exception;
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null)
            return next;
        if (first != next)
            first.addSuppressed(next);
        return first;
    }

    private final class TrackedListener extends AsynchronousServerSocketChannel {
        private final AsynchronousServerSocketChannel delegate;
        TrackedListener(AsynchronousServerSocketChannel delegate) {
            super(delegate.provider());
            this.delegate = delegate;
        }

        @Override
        public <A> void accept(A attachment, CompletionHandler<AsynchronousSocketChannel, ? super A> handler) {
            beginAccept();
            var completed = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable finish = () -> {
                if (completed.compareAndSet(false, true))
                    endAccept();
            };
            try {
                delegate.accept(attachment, new CompletionHandler<AsynchronousSocketChannel, A>() {
                    @Override
                    public void completed(AsynchronousSocketChannel result, A value) {
                        try {
                            beforeCompletion.run();
                            handler.completed(result, value);
                        } finally {
                            finish.run();
                        }
                    }

                    @Override
                    public void failed(Throwable failure, A value) {
                        try {
                            beforeCompletion.run();
                            handler.failed(failure, value);
                        } finally {
                            finish.run();
                        }
                    }
                });
            } catch (RuntimeException | Error failure) {
                finish.run();
                throw failure;
            }
        }

        @Override
        public Future<AsynchronousSocketChannel> accept() {
            CompletableFuture<AsynchronousSocketChannel> future = new CompletableFuture<>();
            accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
                @Override
                public void completed(AsynchronousSocketChannel channel, Object value) {
                    if (!future.complete(channel))
                        try {
                            channel.close();
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                }

                @Override
                public void failed(Throwable failure, Object value) {
                    future.completeExceptionally(failure);
                }
            });
            return future;
        }

        @Override
        public AsynchronousServerSocketChannel bind(SocketAddress address, int backlog) throws IOException {
            delegate.bind(address, backlog);
            return this;
        }

        @Override
        public <T> AsynchronousServerSocketChannel setOption(SocketOption<T> option, T value) throws IOException {
            delegate.setOption(option, value);
            return this;
        }

        @Override
        public <T> T getOption(SocketOption<T> option) throws IOException {
            return delegate.getOption(option);
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return delegate.supportedOptions();
        }

        @Override
        public SocketAddress getLocalAddress() throws IOException {
            return delegate.getLocalAddress();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
