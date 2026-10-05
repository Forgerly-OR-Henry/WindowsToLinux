package gold.debug.windowstolinux.appapi.file.material;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.file.source.FileSelection;
import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Consumer-local persisted read occupancy, never a transferable directory handle. / 消费方本地持久读占用，不是可转交的目录句柄。 */
public interface FileMaterialHold extends AutoCloseable {
    /** Returns this original hold identity. / 返回此原持有身份。
     * @return exact hold identity / 精确持有身份 */
    UUID id();

    /** Returns the immutable original material value. / 返回不可变原素材值。
     * @return exact published reference / 精确发布引用 */
    MaterialRef reference();

    /** Returns this consumer's original whitelisted selection, without location access. / 返回此消费方的原白名单选择，不开放位置访问。
     * @return original authorized selection / 原授权选择
     * @throws ApiException if the hold or original content is unavailable / 持有或原内容不可用时 */
    FileSelection selection() throws ApiException;

    /** Revokes this selection and releases only this exact read hold. / 撤销此选择并仅释放此精确读持有。
     * @throws ApiException if durable release is uncertain / 持久释放不确定时 */
    @Override
    void close() throws ApiException;
}
