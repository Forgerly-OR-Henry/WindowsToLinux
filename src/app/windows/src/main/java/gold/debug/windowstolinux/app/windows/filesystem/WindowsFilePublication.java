package gold.debug.windowstolinux.app.windows.filesystem;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import com.sun.jna.platform.win32.*;

/** Publishes a verified sibling file without deleting the previous target first. / 发布已验证的同目录文件，不先删除原目标。 */
public final class WindowsFilePublication {
    /** Prevents utility construction. / 禁止构造工具实例。 */
    private WindowsFilePublication() {
    }

    /** Moves within one directory using the native no-copy rename operation. / 使用原生不复制的重命名操作在同一目录内移动。
     * @param staged existing verified regular file / 已有已验证普通文件
     * @param target selected final sibling path / 选定最终同目录路径
     * @param overwrite whether the user approved replacement / 用户是否已批准替换
     * @throws IOException if boundaries or native publication fail / 边界或原生发布失败时 */
    public static void publish(Path staged, Path target, boolean overwrite) throws IOException {
        Path source = staged.toAbsolutePath().normalize(), destination = target.toAbsolutePath().normalize();
        if (!source.getParent().equals(destination.getParent()) || source.equals(destination))
            throw new IOException("File publication requires distinct sibling paths");
        for (Path parent = source.getParent(); parent != null; parent = parent.getParent()) {
            var attrs = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isOther() || attrs.isSymbolicLink())
                throw new IOException("Publication parent is not an ordinary directory");
        }
        var attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isOther() || attrs.isSymbolicLink())
            throw new IOException("Publication source is not a regular file");
        int flags = WinBase.MOVEFILE_WRITE_THROUGH | (overwrite ? WinBase.MOVEFILE_REPLACE_EXISTING : 0);
        if (!Kernel32.INSTANCE.MoveFileEx(source.toString(), destination.toString(), new WinDef.DWORD(flags)))
            throw new IOException("Native file publication failed");
    }
}
