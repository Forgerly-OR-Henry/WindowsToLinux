package gold.debug.windowstolinux.app.windows.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Ole32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsRecycleTest {
    @TempDir
    Path temporary;

    @Test
    void realFileHasReceiptAndCanBeRestoredWithoutChangingItsContent() throws Exception {
        Path file = Files.writeString(temporary.resolve("owned-file.txt"), "native receipt / 原生回执");
        roundTrip(file, () -> assertEquals("native receipt / 原生回执", Files.readString(file)));
    }

    @Test
    void realNonEmptyDirectoryHasReceiptAndRestoresItsWholeTree() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("owned-directory"));
        Files.createDirectory(directory.resolve("child"));
        Files.writeString(directory.resolve("child/value.txt"), "retained content");
        roundTrip(directory, () -> assertEquals("retained content", Files.readString(directory.resolve("child/value.txt"))));
    }

    @Test
    void realDirectoryLinkRecyclesOnlyTheLinkAndRetainsItsTarget() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Files.writeString(target.resolve("retained.txt"), "target must survive");
        Path link = temporary.resolve("owned-link");
        WindowsLinks.create(link, target);
        String targetIdentity = WindowsFileIdentity.read(target);
        roundTrip(link, () -> {
            assertEquals(target.toAbsolutePath().toString(), WindowsLinks.target(link).orElseThrow());
            assertEquals(targetIdentity, WindowsFileIdentity.read(target));
            assertEquals("target must survive", Files.readString(target.resolve("retained.txt")));
        });
        // Removing this task-owned junction must precede temporary-directory recursion. / 临时目录递归清理前先移除本任务所属联接点。
        Files.delete(link);
    }

    @Test
    void successfulDeleteCallbackWithoutRecycleObjectCannotConfirmSuccess() throws Exception {
        Path file = Files.writeString(temporary.resolve("callback-only.txt"), "unchanged");
        WindowsRecycle.apartment(() -> {
            WindowsRecycleNative.check(Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue());
            try (var sink = new WindowsRecycleSink(file, WindowsFileIdentity.read(file));
                 var item = WindowsRecycleNative.item(file)) {
                var callback = Function.getFunction(sink.pointer().getPointer(0).getPointer(12L * Native.POINTER_SIZE), Function.ALT_CONVENTION);
                assertTrue(callback.invokeInt(new Object[]{sink.pointer(), 0, item.getPointer(), 0, null}) < 0);
                assertThrows(IOException.class, sink::receipt);
                assertEquals("unchanged", Files.readString(file));
                return true;
            } finally {
                Ole32.INSTANCE.CoUninitialize();
            }
        });
    }

    @Test
    void nativePreDeleteWithoutRecoverabilityFlagAbortsBeforeAnyDeletion() throws Exception {
        Path file = Files.writeString(temporary.resolve("must-survive.txt"), "original bytes");
        WindowsRecycle.apartment(() -> {
            WindowsRecycleNative.check(Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue());
            try (var sink = new WindowsRecycleSink(file, WindowsFileIdentity.read(file));
                 var item = WindowsRecycleNative.item(file)) {
                var callback = Function.getFunction(sink.pointer().getPointer(0).getPointer(11L * Native.POINTER_SIZE), Function.ALT_CONVENTION);
                assertTrue(callback.invokeInt(new Object[]{sink.pointer(), 0, item.getPointer()}) < 0);
                assertThrows(IOException.class, sink::receipt);
                assertEquals("original bytes", Files.readString(file));
                return true;
            } finally {
                Ole32.INSTANCE.CoUninitialize();
            }
        });
    }

    @Test
    void missingObjectFailsWithoutCreatingAReceiptOrDeletingItsParent() {
        assertThrows(IOException.class, () -> WindowsRecycle.recycle(temporary.resolve("absent")));
        assertTrue(Files.isDirectory(temporary));
    }

    @Test
    void callbackMemoryCannotBeFreedWhileANativeReferenceStillExists() throws Exception {
        WindowsRecycle.apartment(() -> {
            var sink = new WindowsRecycleSink(temporary.resolve("unused"), "unused");
            var memory = (com.sun.jna.Memory) sink.pointer();
            var table = sink.pointer().getPointer(0);
            var add = Function.getFunction(table.getPointer((long) Native.POINTER_SIZE), Function.ALT_CONVENTION);
            var release = Function.getFunction(table.getPointer(2L * Native.POINTER_SIZE), Function.ALT_CONVENTION);
            assertEquals(2, add.invokeInt(new Object[]{sink.pointer()}));
            assertThrows(IOException.class, sink::close);
            assertTrue(memory.valid(), "A live COM reference must retain callback memory");
            assertEquals(1, release.invokeInt(new Object[]{sink.pointer()}));
            sink.close();
            assertFalse(memory.valid());
            return true;
        });
    }

    @Test
    void interruptedVirtualCallerStillWaitsForPhysicalApartmentExit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var complete = new CompletableFuture<Boolean>();
        var caller = Thread.ofVirtual().start(() -> {
            try {
                assertTrue(WindowsRecycle.apartment(() -> {
                    assertFalse(Thread.currentThread().isVirtual());
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException unexpected) {
                        throw new IOException(unexpected);
                    }
                    return true;
                }));
                complete.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                complete.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            assertFalse(complete.isDone());
        } finally {
            release.countDown();
        }
        assertTrue(complete.get(5, TimeUnit.SECONDS));
        caller.join();
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().equals("windows-recycle-com")));
    }

    private void roundTrip(Path original, CheckedAssertion check) throws Exception {
        String identity = WindowsFileIdentity.read(original);
        WindowsRecycleReceipt receipt = WindowsRecycle.recycle(original);
        Path metadata = receipt.information();
        try {
            assertFalse(Files.exists(original, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.exists(receipt.path(), LinkOption.NOFOLLOW_LINKS));
            assertEquals(identity, receipt.identity());
            assertEquals(receipt.identity(), WindowsFileIdentity.read(receipt.path()));
            assertTrue(Files.exists(metadata, LinkOption.NOFOLLOW_LINKS));
            assertEquals(receipt.informationIdentity(), WindowsFileIdentity.read(metadata));
            WindowsRecycle.validateInformation(metadata, receipt.informationIdentity(), original);
            assertThrows(IOException.class, () -> WindowsRecycle.validateInformation(metadata, receipt.informationIdentity(), original.resolveSibling("wrong-original")));
            assertThrows(IOException.class, () -> WindowsRecycle.validateInformation(metadata, "wrong-native-identity", original));
            byte[] valid = Files.readAllBytes(metadata);
            byte[] wrongVersion = valid.clone();
            wrongVersion[0] = 3;
            assertThrows(IOException.class, () -> WindowsRecycleInformation.original(wrongVersion));
            assertThrows(IOException.class, () -> WindowsRecycleInformation.original(java.util.Arrays.copyOf(valid, valid.length - 1)));
            byte[] wrongLength = valid.clone();
            java.nio.ByteBuffer.wrap(wrongLength).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(24, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> WindowsRecycleInformation.original(wrongLength));
        } finally {
            restore(receipt, original);
        }
        assertFalse(Files.exists(receipt.path(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(metadata, LinkOption.NOFOLLOW_LINKS), "Only this owned trash metadata must be cleaned after restoration");
        assertEquals(identity, WindowsFileIdentity.read(original));
        check.run();
    }

    private static void restore(WindowsRecycleReceipt receipt, Path original) throws IOException {
        WindowsRecycle.apartment(() -> {
            WindowsRecycleNative.check(Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue());
            try {
                assertFalse(Files.exists(original, LinkOption.NOFOLLOW_LINKS));
                assertEquals(receipt.identity(), WindowsFileIdentity.read(receipt.path()));
                try (var operation = WindowsRecycleNative.create(); var source = WindowsRecycleNative.item(receipt.path());
                     var parent = WindowsRecycleNative.item(original.getParent())) {
                    operation.move(source, parent, original.getFileName().toString());
                    operation.perform();
                }
                assertTrue(Files.exists(original, LinkOption.NOFOLLOW_LINKS));
                assertEquals(receipt.identity(), WindowsFileIdentity.read(original));
                // Physical-shell MoveItem restores the object but leaves its paired metadata. / 物理 Shell MoveItem 还原对象但保留配对元数据。
                // Delete only this fixture's unchanged metadata after its original identity is restored. / 原身份已还原后，仅删除该夹具身份未变的元数据。
                assertEquals(receipt.informationIdentity(), WindowsFileIdentity.read(receipt.information()));
                WindowsRecycle.validateInformation(receipt.information(), receipt.informationIdentity(), original);
                Files.delete(receipt.information());
                return true;
            } finally {
                Ole32.INSTANCE.CoUninitialize();
            }
        });
    }

    @FunctionalInterface
    private interface CheckedAssertion {
        void run() throws Exception;
    }
}
