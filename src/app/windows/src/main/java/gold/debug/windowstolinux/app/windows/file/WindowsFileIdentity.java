package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.*;

/** Reads volume and native file identity without following the final reparse point. / 不跟随末级重解析点，读取卷及原生文件身份。 */
public final class WindowsFileIdentity {
    /** Prevents construction of the native identity utility. / 禁止构造原生身份工具。 */
    private WindowsFileIdentity() {
    }

    /** Reads FILE_ID_INFO while retaining and closing the actual native handle. / 保留并关闭实际原生句柄期间读取 FILE_ID_INFO。
     * @param path exact local file or directory / 精确本地文件或目录
     * @return volume serial and 128-bit file identity / 卷序列号及 128 位文件身份
     * @throws IOException if the filesystem cannot prove identity or release the handle / 文件系统无法证明身份或释放句柄时 */
    public static String read(Path path) throws IOException {
        var nativeApi = Kernel32.INSTANCE;
        String nativePath = path.toAbsolutePath().normalize().toString();
        if (!nativePath.startsWith("\\\\?\\"))
            nativePath = nativePath.startsWith("\\\\")
                ? "\\\\?\\UNC\\" + nativePath.substring(2)
                : "\\\\?\\" + nativePath;
        var handle = nativeApi.CreateFile(nativePath, 0,
            WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE, null, WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(handle))
            throw new IOException("File identity handle could not be opened: " + path + " (" + nativeApi.GetLastError() + ")");
        try (var information = new Memory(24)) {
            // FILE_ID_INFO contains one 64-bit volume number and one 128-bit identifier. / FILE_ID_INFO 含一个 64 位卷号和一个 128 位标识。
            if (!nativeApi.GetFileInformationByHandleEx(handle, 18, information, new WinDef.DWORD(information.size())))
                throw new IOException("Native file identity is unavailable");
            return HexFormat.of().formatHex(information.getByteArray(0, 24));
        } finally {
            if (!nativeApi.CloseHandle(handle))
                throw new IOException("File identity handle could not be closed");
        }
    }
}
