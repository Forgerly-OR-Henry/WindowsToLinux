package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;

/** Uses native recycle receipts without any permanent-delete fallback. / 使用原生回收回执，绝不回退永久删除。 */
public final class WindowsRecycle {
    /** Prevents construction of the native recycle utility. / 禁止构造原生回收工具。 */
    private WindowsRecycle() {
    }

    /** Accepts fixed local volumes; actual recycle availability is checked by the operation. / 接受本机固定卷，实际回收可用性由操作核验。
     * @param path exact local object / 精确本机对象
     * @return whether this volume admits a native recycle attempt / 卷是否允许原生回收尝试
     */
    public static boolean supports(Path path) {
        Objects.requireNonNull(path, "path");
        Path root = path.toAbsolutePath().getRoot();
        return Platform.isWindows() && root != null
            && Kernel32.INSTANCE.GetDriveType(root.toString()) == WinBase.DRIVE_FIXED;
    }

    /** Recycles one object and proves its actual new identity before returning. / 回收一个对象，返回前证明其实际新身份。
     * @param path exact original object / 精确原对象
     * @return internal native receipt, never an SDK browsing or restoration capability / 内部原生回执，不构成 SDK 浏览或还原能力
     * @throws IOException if admission, native completion or receipt proof fails / 准入、原生完成或回执证明失败时
     */
    public static WindowsRecycleReceipt recycle(Path path) throws IOException {
        if (!supports(path)) throw new IOException("Recoverable system trash is unavailable at this location");
        Path original = path.toAbsolutePath().normalize();
        return apartment(() -> WindowsRecycleNative.recycle(original));
    }

    /** Verifies unchanged native metadata identity and its exact original path. / 核验原生元数据身份未变及其精确原路径。
     * @param information exact paired metadata object / 精确配对元数据对象
     * @param identity captured native metadata identity / 捕获的原生元数据身份
     * @param original exact original object / 精确原对象
     * @return true only after every identity and path check succeeds / 仅在全部身份及路径检查成功后返回真
     * @throws IOException if identity, format or original path cannot be proven / 身份、格式或原路径无法证明时
     */
    public static boolean validateInformation(Path information, String identity, Path original) throws IOException {
        WindowsRecycleInformation.validate(information, identity, original.toAbsolutePath().normalize());
        return true;
    }

    /** Runs COM initialization, work and release on one physical platform thread and joins it. / 在同一实际平台线程初始化、执行及释放 COM，并等待其退出。
     * @param action work owned by the apartment / 单元所持有的操作
     * @param <T> immutable result type / 不可变结果类型
     * @return completed result after thread exit / 线程退出后的完成结果
     * @throws IOException when the native action fails / 原生操作失败时
     */
    static <T> T apartment(ApartmentAction<T> action) throws IOException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().name("windows-recycle-com").unstarted(() -> {
            try {
                result.set(action.run());
            } catch (Throwable nativeFailure) {
                failure.set(nativeFailure);
            }
        });
        worker.start();
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException joinInterrupted) {
                // A requested interruption cannot abandon live COM resources. / 中断请求不能放弃尚存活的 COM 资源。
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable problem = failure.get();
        if (problem instanceof IOException io) throw io;
        if (problem instanceof Error fatal) throw fatal;
        if (problem != null) throw new IOException("Native recycle work failed", problem);
        return Objects.requireNonNull(result.get(), "Completed native result");
    }

    /** Checked work whose lifetime belongs to one COM apartment. / 生命周期归属于一个 COM 单元的可检查操作。
     * @param <T> result type / 结果类型
     */
    @FunctionalInterface
    interface ApartmentAction<T> {
        /** Executes the native work to completion. / 将原生工作执行至完成。
         * @return actual result / 实际结果
         * @throws IOException if native proof fails / 原生证明失败时
         */
        T run() throws IOException;
    }
}
