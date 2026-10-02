package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/** Owns native shell objects entirely within one platform-thread COM apartment. / 在一个平台线程 COM 单元内完整持有原生 Shell 对象。 */
final class WindowsRecycleNative extends Unknown implements AutoCloseable {
    /** Recycle, fail on first error, suppress UI and connected-file side effects without elevation. / 回收、首次错误即停、禁用界面及关联文件副作用，不提权。 */
    private static final int FLAGS = 0x00080000 | 0x00100000 | 0x00000400 | 0x00000004 | 0x00000010 | 0x00002000;

    /** Native IFileOperation class identifier. / 原生 IFileOperation 类标识。 */
    private static final Guid.GUID CLASS_ID = new Guid.GUID("{3AD05575-8857-4850-9277-11B85BDB8E09}");

    /** Native IFileOperation interface identifier. / 原生 IFileOperation 接口标识。 */
    private static final Guid.GUID INTERFACE_ID = new Guid.GUID("{947AAB5F-0A5C-4C13-B4D6-4BF7836FC9F8}");

    /** Native filesystem shell-item interface identifier. / 原生文件系统 Shell 对象接口标识。 */
    private static final Guid.IID ITEM_ID = new Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");

    /** Shell parsing entry point with the native Windows calling convention. / 使用 Windows 原生调用约定的 Shell 解析入口。 */
    private static final Function CREATE_ITEM = NativeLibrary.getInstance("shell32")
        .getFunction("SHCreateItemFromParsingName", Function.ALT_CONVENTION);

    /** Wraps an acquired operation reference. / 包装已取得的操作引用。
     * @param pointer acquired native interface / 已取得的原生接口
     */
    private WindowsRecycleNative(Pointer pointer) {
        super(pointer);
    }

    /** Executes native recycle and validates its actual receipt. / 执行原生回收并校验实际回执。
     * @param original exact original object / 精确原对象
     * @return observed recycle object / 已观测回收对象
     * @throws IOException if shell work or identity proof fails / Shell 工作或身份证明失败时
     */
    static WindowsRecycleReceipt recycle(Path original) throws IOException {
        check(Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue());
        try {
            String identity = WindowsFileIdentity.read(original);
            try (var sink = new WindowsRecycleSink(original, identity);
                 var operation = create(); var item = item(original)) {
                check(operation._invokeNativeInt(18, new Object[]{operation.getPointer(), item.getPointer(), sink.pointer()}));
                operation.perform();
                Path recycled = sink.receipt();
                if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(recycled, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("System recycle receipt could not be confirmed");
                String recycledIdentity = WindowsFileIdentity.read(recycled);
                if (!identity.equals(recycledIdentity)) throw new IOException("System recycle object identity changed");
                String name = recycled.getFileName().toString();
                if (!name.startsWith("$R") || name.length() < 3)
                    throw new IOException("Native recycle receipt is not a paired trash object");
                Path information = recycled.resolveSibling("$I" + name.substring(2));
                if (!Files.isRegularFile(information, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Native recycle metadata is unavailable");
                String informationIdentity = WindowsFileIdentity.read(information);
                WindowsRecycleInformation.validate(information, informationIdentity, original);
                return new WindowsRecycleReceipt(recycled, recycledIdentity, information, informationIdentity);
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    /** Creates a non-elevated in-process operation with explicit safety flags. / 使用明确安全标志创建不提权的进程内操作。
     * @return acquired operation / 已取得的操作
     * @throws IOException if creation or flags fail / 创建或标志设置失败时
     */
    static WindowsRecycleNative create() throws IOException {
        var output = new PointerByReference();
        check(Ole32.INSTANCE.CoCreateInstance(CLASS_ID, null, 1, INTERFACE_ID, output).intValue());
        var operation = new WindowsRecycleNative(output.getValue());
        try {
            check(operation._invokeNativeInt(5, new Object[]{operation.getPointer(), FLAGS}));
            return operation;
        } catch (IOException failure) {
            operation.close();
            throw failure;
        }
    }

    /** Parses an exact filesystem object without retaining unmanaged references. / 解析精确文件系统对象，不遗留非托管引用。
     * @param path exact filesystem position / 精确文件系统位置
     * @return acquired shell item / 已取得的 Shell 对象
     * @throws IOException if parsing fails / 解析失败时
     */
    static ShellItem item(Path path) throws IOException {
        return item(path.toString());
    }

    /** Parses an actual shell namespace identity. / 解析实际 Shell 命名空间身份。
     * @param parsingName exact shell parsing name / 精确 Shell 解析名
     * @return acquired shell item / 已取得的 Shell 对象
     * @throws IOException if parsing fails / 解析失败时
     */
    static ShellItem item(String parsingName) throws IOException {
        var output = new PointerByReference();
        check(CREATE_ITEM.invokeInt(new Object[]{new WString(parsingName), null, new Guid.REFIID(ITEM_ID), output}));
        return new ShellItem(output.getValue());
    }

    /** Performs queued work and rejects an aborted operation. / 执行排队工作并拒绝中止的操作。
     * @throws IOException if execution fails or any operation is aborted / 执行失败或任一操作中止时
     */
    void perform() throws IOException {
        check(_invokeNativeInt(21, new Object[]{getPointer()}));
        var aborted = new IntByReference();
        check(_invokeNativeInt(22, new Object[]{getPointer(), aborted}));
        if (aborted.getValue() != 0) throw new IOException("Native recycle operation was aborted");
    }

    /** Queues a move used only by task-owned native restoration fixtures. / 排队移动，仅供任务自有原生还原夹具使用。
     * @param source actual receipt object / 实际回执对象
     * @param parent original parent / 原父目录
     * @param name original filename / 原文件名
     * @throws IOException if queuing fails / 排队失败时
     */
    void move(ShellItem source, ShellItem parent, String name) throws IOException {
        check(_invokeNativeInt(14, new Object[]{getPointer(), source.getPointer(), parent.getPointer(), new WString(name), null}));
    }

    /** Rejects a native failure without exposing provider or filesystem messages. / 拒绝原生失败，不暴露提供方或文件系统消息。
     * @param result native HRESULT / 原生 HRESULT
     * @throws IOException if the HRESULT denotes failure / HRESULT 表示失败时
     */
    static void check(int result) throws IOException {
        if (result < 0)
            throw new IOException("Native recycle operation failed (HRESULT " + Integer.toHexString(result) + ")");
    }

    /** Releases the operation on its owning apartment. / 在持有单元释放操作。 */
    @Override
    public void close() {
        Release();
    }

    /** Acquired shell item whose native reference is explicitly released. / 显式释放原生引用的已取得 Shell 对象。 */
    static final class ShellItem extends Unknown implements AutoCloseable {
        /** Wraps an acquired or callback-borrowed shell object. / 包装已取得或回调借用的 Shell 对象。
         * @param pointer native item / 原生对象
         */
        ShellItem(Pointer pointer) {
            super(pointer);
        }

        /** Copies the actual filesystem path and releases its COM-allocated string. / 复制实际文件系统路径并释放 COM 分配的字符串。
         * @return absolute filesystem path / 绝对文件系统路径
         * @throws IOException if the shell object has no filesystem path / Shell 对象没有文件系统路径时
         */
        Path path() throws IOException {
            return Path.of(name(0x80058000)).toAbsolutePath().normalize();
        }

        /** Copies a shell display identity and releases its native allocation. / 复制 Shell 显示身份并释放原生分配。
         * @param format exact SIGDN format / 精确 SIGDN 格式
         * @return copied identity / 已复制身份
         * @throws IOException if the requested identity is unavailable / 所需身份不可用时
         */
        String name(int format) throws IOException {
            var output = new PointerByReference();
            check(_invokeNativeInt(5, new Object[]{getPointer(), format, output}));
            Pointer text = output.getValue();
            if (text == null) throw new IOException("Native recycle object has no filesystem path");
            try {
                return text.getWideString(0);
            } finally {
                Ole32.INSTANCE.CoTaskMemFree(text);
            }
        }

        /** Releases an acquired item on its owning apartment. / 在持有单元释放已取得的对象。 */
        @Override
        public void close() {
            Release();
        }
    }
}
