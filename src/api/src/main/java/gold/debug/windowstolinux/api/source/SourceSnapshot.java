package gold.debug.windowstolinux.api.source;

import java.nio.file.Path;
import java.util.UUID;

import gold.debug.windowstolinux.api.task.ApiException;

/**
 * Owned immutable source directory released after actual close. / 实际关闭后释放的受管不可变来源目录。
 */
public interface SourceSnapshot extends AutoCloseable {

    /**
     * Returns the stable identity of this registered resource. / 返回此已登记资源的稳定身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UUID id();

    /**
     * Returns the immutable snapshot content digest. / 返回不可变快照的内容摘要。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    String sha256();

    /**
     * Returns the owned immutable snapshot directory. / 返回所属不可变快照目录。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    Path directory();

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
