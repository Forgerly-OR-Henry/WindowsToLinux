package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.jna.CallbackProxy;
import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.win32.StdCallLibrary;

/** Receives the actual PostDeleteItem result while retaining the complete COM callback table. / 接收实际 PostDeleteItem 结果，并保留完整 COM 回调表。 */
final class WindowsRecycleSink implements AutoCloseable {
    /** Retains callbacks until native reference release is actually proved, including failed cleanup. / 保留回调直到实际证明原生引用释放，包括清理失败时。 */
    private static final Set<WindowsRecycleSink> LIVE = ConcurrentHashMap.newKeySet();

    /** Standard IUnknown interface identifier. / 标准 IUnknown 接口标识。 */
    private static final Guid.GUID UNKNOWN_ID = new Guid.GUID("{00000000-0000-0000-C000-000000000046}");

    /** IFileOperationProgressSink interface identifier. / IFileOperationProgressSink 接口标识。 */
    private static final Guid.GUID SINK_ID = new Guid.GUID("{04B0F1A7-9490-44BC-96E1-4296A31252E2}");

    /** Native no-interface result. / 原生无接口结果。 */
    private static final int NO_INTERFACE = 0x80004002;

    /** Native callback failure result. / 原生回调失败结果。 */
    private static final int FAILED = 0x80004005;

    /** Exact original filesystem object. / 精确原文件系统对象。 */
    private final Path original;

    /** Original native volume and object identity. / 原始原生卷及对象身份。 */
    private final String identity;

    /** Physical platform thread owning all borrowed COM calls. / 持有所有借用 COM 调用的实际平台线程。 */
    private final Thread owner = Thread.currentThread();

    /** Owner reference plus shell references. / 所有者引用及 Shell 引用。 */
    private final AtomicInteger references = new AtomicInteger(1);

    /** Strong references preventing callback reclamation during native work. / 阻止原生工作期间回调被回收的强引用。 */
    private final List<NativeCallback> callbacks = new ArrayList<>();

    /** Nineteen native interface slots including IUnknown. / 包含 IUnknown 的十九个原生接口槽。 */
    private final Memory table = new Memory(19L * Native.POINTER_SIZE);

    /** Native object containing its vtable pointer. / 包含虚表指针的原生对象。 */
    private final Memory object = new Memory(Native.POINTER_SIZE);

    /** Actual path copied from the non-null recycled shell item. / 从非空回收 Shell 对象复制的实际路径。 */
    private Path recycled;

    /** Callback failure retained instead of throwing across the native boundary. / 保留回调失败，避免跨原生边界抛出异常。 */
    private volatile IOException failure;

    /** Builds the complete native interface on its owning thread. / 在持有线程建立完整原生接口。
     * @param original exact original object / 精确原对象
     * @param identity original native identity / 原始原生身份
     */
    WindowsRecycleSink(Path original, String identity) {
        this.original = original;
        this.identity = identity;
        String[] signatures = {"ppp", "p", "p", "p", "pi", "pipp", "pippip", "pippp",
            "pipppip", "pippp", "pipppip", "pip", "pipip", "pipp", "pipppiip", "pii", "p", "p", "p"};
        for (int slot = 0; slot < signatures.length; slot++) {
            var callback = new NativeCallback(slot, signatures[slot]);
            callbacks.add(callback);
            table.setPointer((long) slot * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(callback));
        }
        object.setPointer(0, table);
        LIVE.add(this);
    }

    /** Returns the native object while retaining every callback. / 保留所有回调期间返回原生对象。
     * @return owned native progress sink / 所持有的原生进度接收器
     */
    Pointer pointer() {
        return object;
    }

    /** Requires a successful root-item callback with an actual recycled object. / 要求根对象回调成功且具有实际回收对象。
     * @return actual recycled filesystem position / 实际回收文件系统位置
     * @throws IOException if completion cannot be proven / 无法证明完成时
     */
    Path receipt() throws IOException {
        if (failure != null) throw failure;
        if (recycled == null) throw new IOException("Native recycle has no confirmed recycled object");
        return recycled;
    }

    /** Handles interface identity and root-object callbacks without escaping native boundaries. / 处理接口身份及根对象回调，不越过原生边界。
     * @param slot invoked interface slot / 被调用的接口槽
     * @param arguments native arguments / 原生参数
     * @return native HRESULT or reference count / 原生 HRESULT 或引用数
     */
    private int invoke(int slot, Object[] arguments) {
        if (slot == 0) {
            var requested = new Guid.GUID((Pointer) arguments[1]);
            Pointer output = (Pointer) arguments[2];
            boolean supported = UNKNOWN_ID.equals(requested) || SINK_ID.equals(requested);
            output.setPointer(0, supported ? object : null);
            if (supported) references.incrementAndGet();
            return supported ? 0 : NO_INTERFACE;
        }
        if (slot == 1) return references.incrementAndGet();
        if (slot == 2) return references.decrementAndGet();
        if (owner != Thread.currentThread() || owner.isVirtual()) {
            failure = new IOException("Recycle callback left its owning platform apartment");
            return FAILED;
        }
        try {
            if (slot == 4) WindowsRecycleNative.check((Integer) arguments[1]);
            if (slot == 11 && ((Integer) arguments[1] & 0x80) == 0)
                throw new IOException("Native item cannot be recycled; deletion was refused before effect");
            if (slot == 11 || slot == 12) {
                Path source = new WindowsRecycleNative.ShellItem((Pointer) arguments[2]).path();
                if (source.equals(original)) {
                    if (slot == 11 && !identity.equals(WindowsFileIdentity.read(original)))
                        throw new IOException("Original recycle object changed before native deletion");
                    if (slot == 12) {
                        WindowsRecycleNative.check((Integer) arguments[3]);
                        Pointer created = (Pointer) arguments[4];
                        if (created == null) throw new IOException("Native deletion did not return a recycle object");
                        var returned = new WindowsRecycleNative.ShellItem(created);
                        recycled = returned.path();
                    }
                }
            }
            return 0;
        } catch (IOException | RuntimeException callbackFailure) {
            failure = callbackFailure instanceof IOException io ? io : new IOException("Native recycle callback failed", callbackFailure);
            return FAILED;
        }
    }

    /** Releases callback memory only after all shell references have been released. / 仅在全部 Shell 引用已释放后释放回调内存。
     * @throws IOException if native references remain / 仍存在原生引用时
     */
    @Override
    public void close() throws IOException {
        if (references.get() != 1) throw new IOException("Native recycle callback references remain live");
        references.decrementAndGet();
        object.close();
        table.close();
        callbacks.clear();
        LIVE.remove(this);
    }

    /** Converts exact SDK callback signatures using the Windows stdcall convention. / 使用 Windows stdcall 约定转换精确 SDK 回调签名。 */
    private final class NativeCallback implements CallbackProxy, StdCallLibrary.StdCallCallback {
        /** SDK interface slot. / SDK 接口槽。 */
        private final int slot;

        /** Native argument types including the this pointer. / 包含 this 指针的原生参数类型。 */
        private final Class<?>[] types;

        /** Retains the slot and its native signature. / 保留接口槽及原生签名。
         * @param slot SDK interface slot / SDK 接口槽
         * @param signature pointer and integer ABI signature / 指针及整数 ABI 签名
         */
        NativeCallback(int slot, String signature) {
            this.slot = slot;
            types = signature.chars().mapToObj(value -> value == 'p' ? Pointer.class : int.class).toArray(Class<?>[]::new);
        }

        /** Dispatches the actual native call. / 分派实际原生调用。
         * @param arguments native arguments / 原生参数
         * @return native result / 原生结果
         */
        @Override
        public Object callback(Object[] arguments) {
            try {
                return invoke(slot, arguments);
            } catch (Throwable boundaryFailure) {
                // Exceptions cannot escape a COM callback; retain uncertainty for the owning operation. / 异常不能越过 COM 回调边界，保留不确定状态给持有操作。
                failure = new IOException("Native recycle callback boundary failed", boundaryFailure);
                return FAILED;
            }
        }

        /** Supplies the exact native argument types. / 提供精确原生参数类型。
         * @return native signature / 原生签名
         */
        @Override
        public Class<?>[] getParameterTypes() {
            return types.clone();
        }

        /** Supplies the HRESULT or ULONG native return type. / 提供 HRESULT 或 ULONG 原生返回类型。
         * @return 32-bit native integer type / 三十二位原生整数类型
         */
        @Override
        public Class<?> getReturnType() {
            return int.class;
        }
    }
}
