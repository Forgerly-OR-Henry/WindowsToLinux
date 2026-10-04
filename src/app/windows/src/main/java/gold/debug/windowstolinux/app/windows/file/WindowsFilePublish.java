package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import com.sun.jna.platform.win32.*;

/** Publishes verified files or whole directories with explicit native rename rules. / 按明确原生重命名规则发布已验证文件或整个目录。 */
public final class WindowsFilePublish {
    /** Prevents utility construction. / 禁止构造工具实例。 */
    private WindowsFilePublish() {
    }

    /** Publishes a complete directory with one same-volume native rename, never replacing a target. / 以一次同卷原生重命名发布完整目录，绝不替换目标。
     * @param staged verified ordinary staging directory / 已验证普通暂存目录
     * @param target absent final directory under an existing parent / 现有父目录下不存在的最终目录
     * @throws IOException if boundaries or the no-copy rename fail / 边界或不复制重命名失败时 */
    public static void publishDirectory(Path staged, Path target) throws IOException {
        if (!staged.isAbsolute() || !target.isAbsolute() || !staged.equals(staged.normalize())
            || !target.equals(target.normalize()) || staged.equals(target) || target.startsWith(staged))
            throw new IOException("Directory publication requires explicit distinct canonical paths");
        for (Path path : java.util.List.of(staged, target.getParent())) {
            for (Path parent = path; parent != null; parent = parent.getParent()) {
                var attrs = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isDirectory() || attrs.isOther() || attrs.isSymbolicLink()
                    || !parent.toRealPath().equals(parent))
                    throw new IOException("Directory publication path is redirected");
            }
        }
        if (!Kernel32.INSTANCE.MoveFileEx(staged.toString(), target.toString(),
            new WinDef.DWORD(WinBase.MOVEFILE_WRITE_THROUGH)))
            throw new IOException("Native directory publication failed");
    }

    /** Moves within one directory using the native no-copy rename operation. / 使用原生不复制的重命名操作在同一目录内移动。
     * @param staged existing verified regular file / 已有已验证普通文件
     * @param target selected final sibling path / 选定最终同目录路径
     * @param overwrite whether the user approved replacement / 用户是否已批准替换
     * @throws IOException if boundaries or native publication fail / 边界或原生发布失败时 */
    public static void publish(Path staged, Path target, boolean overwrite) throws IOException {
        Path source = staged.toAbsolutePath().normalize();
        var attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isOther() || attrs.isSymbolicLink())
            throw new IOException("Publish source is not a regular file");
        rename(source, target, overwrite);
    }

    /** Publishes a supported link itself without following its target. / 不跟随目标地发布受支持链接本身。
     * @param staged exclusively owned symbolic link or junction / 排他所属的符号链接或联接点
     * @param target selected final sibling path / 选定最终同目录路径
     * @param overwrite whether replacement was approved / 是否批准替换
     * @throws IOException if link validation or native publication fails / 链接校验或原生发布失败时 */
    public static void publishLink(Path staged, Path target, boolean overwrite) throws IOException {
        Path source = staged.toAbsolutePath().normalize();
        if (WindowsLinks.target(source).isEmpty())
            throw new IOException("Publish source is not a supported link");
        rename(source, target, overwrite);
    }

    /** Performs one native rename with replacement controlled by the operation itself. / 通过操作自身控制替换，执行一次原生重命名。
     * @param source verified staged object / 已验证暂存对象
     * @param target selected sibling destination / 选定同目录目标
     * @param overwrite whether replacement was approved / 是否批准替换
     * @throws IOException if boundaries or the native rename fail / 边界或原生重命名失败时 */
    private static void rename(Path source, Path target, boolean overwrite) throws IOException {
        Path destination = target.toAbsolutePath().normalize();
        if (!source.getParent().equals(destination.getParent()) || source.equals(destination))
            throw new IOException("File publication requires distinct sibling paths");
        for (Path parent = source.getParent(); parent != null; parent = parent.getParent()) {
            var attrs = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isOther() || attrs.isSymbolicLink())
                throw new IOException("Publish parent is not an ordinary directory");
        }
        int flags = WinBase.MOVEFILE_WRITE_THROUGH | (overwrite ? WinBase.MOVEFILE_REPLACE_EXISTING : 0);
        if (!Kernel32.INSTANCE.MoveFileEx(source.toString(), destination.toString(), new WinDef.DWORD(flags))) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (!overwrite && (error == WinError.ERROR_ALREADY_EXISTS || error == WinError.ERROR_FILE_EXISTS))
                throw new FileAlreadyExistsException(destination.toString());
            throw new IOException("Native file publication failed");
        }
    }
}
