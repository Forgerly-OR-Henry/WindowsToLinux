package gold.debug.windowstolinux.shared.git.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class GitCommandExecutorTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    @Test
    void interruptionTerminatesTheStartedProcessAndPreservesInterruptStatus() throws Exception {
        var pidFile = directory.resolve("pid");
        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                new GitCommandExecutor().run(directory,
                        List.of(javaExecutable, "-cp", System.getProperty("java.class.path"),
                                GitInterruptProcessFixture.class.getName(), pidFile.toString()));
            } catch (Throwable failure) {
                result.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!java.nio.file.Files.exists(pidFile) && System.nanoTime() < deadline)
                Thread.sleep(20);
            org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(pidFile));
            long pid = Long.parseLong(java.nio.file.Files.readString(pidFile));
            worker.interrupt();
            worker.join(15000);
            org.junit.jupiter.api.Assertions.assertFalse(worker.isAlive());
            org.junit.jupiter.api.Assertions.assertInstanceOf(InterruptedException.class, result.get());
            org.junit.jupiter.api.Assertions.assertTrue(interrupted.get());
            org.junit.jupiter.api.Assertions
                    .assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            worker.interrupt();
            worker.join(15000);
            if (java.nio.file.Files.exists(pidFile)) {
                var owned = ProcessHandle.of(Long.parseLong(java.nio.file.Files.readString(pidFile)));
                if (owned.isPresent() && owned.get().isAlive()) {
                    owned.get().destroyForcibly();
                    owned.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void exitedParentCannotLeaveItsOutputHoldingChildBehind() throws Exception {
        var pidFile = directory.resolve("child-pid");
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(25), () -> {
                try {
                    new GitCommandExecutor(java.time.Duration.ofSeconds(5)).run(directory,
                            List.of(javaExecutable, "-cp", System.getProperty("java.class.path"),
                                    GitParentProcessFixture.class.getName(), pidFile.toString()));
                } catch (gold.debug.windowstolinux.shared.git.GitSnapshotException expected) {
                    // A retained output pipe may fail the command; it must still be cleaned. / 输出管道可导致命令失败，但必须完成清理。
                }
            });
            long pid = Long.parseLong(java.nio.file.Files.readString(pidFile));
            org.junit.jupiter.api.Assertions
                    .assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            if (java.nio.file.Files.exists(pidFile)) {
                var child = ProcessHandle.of(Long.parseLong(java.nio.file.Files.readString(pidFile)));
                if (child.isPresent() && child.get().isAlive()) {
                    child.get().destroyForcibly();
                    child.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
        }
    }

    static final class GitParentProcessFixture {
        /** Starts one owned descendant then exits. / 启动本次子进程后退出。 */
        public static void main(String[] args) throws Exception {
            String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                    GitInterruptProcessFixture.class.getName(), args[0]).inheritIO().start();
            Thread.sleep(1000);
        }
    }

    static final class GitInterruptProcessFixture {
        /** Waits until the test cancels this task-owned process. / 等待测试取消本次启动的进程。 */
        public static void main(String[] args) throws Exception {
            var published = java.nio.file.Path.of(args[0]);
            var pending = published.resolveSibling(published.getFileName() + ".pending");
            java.nio.file.Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
            java.nio.file.Files.move(pending, published, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Thread.sleep(60000);
        }
    }

    @Test
    void usesOpenSslForNonInteractiveHttpsOnWindows() {
        assertEquals(List.of("git", "-c", "http.sslBackend=openssl", "fetch", "origin"),
                GitCommandExecutor.commandForPlatform(List.of("git", "fetch", "origin"), "Windows 11"));
    }

    @Test
    void leavesThePlatformGitDefaultUnchangedElsewhere() {
        assertEquals(List.of("git", "fetch", "origin"),
                GitCommandExecutor.commandForPlatform(List.of("git", "fetch", "origin"), "Linux"));
    }
}
