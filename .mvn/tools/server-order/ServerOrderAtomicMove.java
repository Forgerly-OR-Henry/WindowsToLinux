package gold.debug.windowstolinux.app.db.init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Windows rename-by-handle keeps both file objects guarded throughout the atomic name replacement. */
final class ServerOrderAtomicMove {
    private static final int FILE_RENAME_INFO_EX = 22;

    private static final int REPLACE_IF_EXISTS_AND_POSIX = 1 | 2;

    private static final int SUPPORTS_POSIX_UNLINK_RENAME = 0x400;

    private ServerOrderAtomicMove() {
    }

    static void replace(Path staged, Path original) throws IOException {
        var kernel = Kernel.INSTANCE;
        var capabilities = new IntByReference();
        if (!kernel.GetVolumeInformationW(new WString(staged.getRoot().toString()), null, 0,
            null, null, capabilities, null, 0)
            || (capabilities.getValue() & SUPPORTS_POSIX_UNLINK_RENAME) == 0)
            throw new IOException("The original volume does not advertise POSIX atomic rename support");
        // DELETE and READ_ATTRIBUTES do not request data access denied by the retained staged-file guard.
        var source = kernel.CreateFileW(new WString(staged.toString()), 0x00010080,
            1 | 2 | 4, null, 3, 0x80, null);
        if (source == null || Pointer.nativeValue(source.getPointer()) == -1)
            throw new IOException("Could not acquire the staged rename handle; Win32 " + kernel.GetLastError());
        try {
            var identity = identity(source);
            var name = original.toString().getBytes(StandardCharsets.UTF_16LE);
            int rootOffset = Native.POINTER_SIZE == 8 ? 8 : 4;
            int lengthOffset = rootOffset + Native.POINTER_SIZE;
            int nameOffset = lengthOffset + Integer.BYTES;
            try (var information = new Memory(nameOffset + name.length + 2L)) {
                information.clear();
                information.setInt(0, REPLACE_IF_EXISTS_AND_POSIX);
                information.setPointer(rootOffset, null);
                information.setInt(lengthOffset, name.length);
                information.write(nameOffset, name, 0, name.length);
                if (!kernel.SetFileInformationByHandle(source, FILE_RENAME_INFO_EX, information,
                    Math.toIntExact(information.size())))
                    throw new IOException("The guarded atomic rename failed; Win32 " + kernel.GetLastError());
            }
            var published = kernel.CreateFileW(new WString(original.toString()), 0x80, 1 | 2 | 4, null, 3, 0x80, null);
            if (published == null || Pointer.nativeValue(published.getPointer()) == -1)
                throw new IOException("Could not verify the published file identity; Win32 " + kernel.GetLastError());
            try {
                if (!Arrays.equals(identity, identity(published)))
                    throw new IOException("The published name does not reference the original staged file object");
            } finally {
                if (!kernel.CloseHandle(published))
                    throw new IOException("The identity handle could not close; Win32 " + kernel.GetLastError());
            }
        } finally {
            if (!kernel.CloseHandle(source))
                throw new IOException("The rename handle could not close; Win32 " + kernel.GetLastError());
        }
    }

    private static byte[] identity(HANDLE handle) throws IOException {
        // FILE_ID_INFO: 64-bit volume serial followed by a 128-bit file identifier.
        try (var information = new Memory(24)) {
            if (!Kernel.INSTANCE.GetFileInformationByHandleEx(handle, 18, information, 24))
                throw new IOException("The file identity could not be read; Win32 " + Kernel.INSTANCE.GetLastError());
            return information.getByteArray(0, 24);
        }
    }

    interface Kernel extends StdCallLibrary {
        Kernel INSTANCE = Native.load("kernel32", Kernel.class);

        boolean GetVolumeInformationW(WString root, Pointer volumeName, int volumeNameLength,
                                      IntByReference serial, IntByReference componentLength, IntByReference capabilities,
                                      Pointer filesystemName, int filesystemNameLength);

        HANDLE CreateFileW(WString path, int access, int sharing, Pointer security, int creation,
                           int attributes, HANDLE template);

        boolean SetFileInformationByHandle(HANDLE file, int informationClass, Pointer information, int length);

        boolean GetFileInformationByHandleEx(HANDLE file, int informationClass, Pointer information, int length);

        boolean CloseHandle(HANDLE handle);

        int GetLastError();
    }
}
