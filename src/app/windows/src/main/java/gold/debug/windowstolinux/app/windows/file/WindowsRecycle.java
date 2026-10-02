package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.file.*;
import com.sun.jna.Platform;
import com.sun.jna.platform.FileUtils;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;

/** Windows system recycle with a fail-closed location preflight. / Windows 系统回收及拒绝不确定位置的前置检查。 */
public final class WindowsRecycle {
    private WindowsRecycle() { }
    /** Accepts fixed local volumes only; unsupported locations require explicit permanent-delete approval. / 只接受本机固定卷，其他位置须明确永久删除批准。 */
    public static boolean supports(Path path) {
        if (!Platform.isWindows() || path.toAbsolutePath().getRoot() == null) return false;
        return Kernel32.INSTANCE.GetDriveType(path.toAbsolutePath().getRoot().toString()) == WinBase.DRIVE_FIXED && FileUtils.getInstance().hasTrash();
    }
    /** Moves one user object into the Windows recycle bin; failures never fall back to deletion. / 将用户对象移入回收站，失败绝不回退直接删除。 */
    public static void recycle(Path path) throws IOException {
        if (!supports(path)) throw new IOException("Recoverable system trash is unavailable at this location");
        FileUtils.getInstance().moveToTrash(path.toFile());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("System recycle was not confirmed");
    }
}
