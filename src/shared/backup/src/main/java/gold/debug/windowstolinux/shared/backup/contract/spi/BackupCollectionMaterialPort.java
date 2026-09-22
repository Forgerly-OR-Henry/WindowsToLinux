package gold.debug.windowstolinux.shared.backup.contract.spi;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Platform-owned private storage and quota enforcement for collected files. / 平台持有的采集文件私有存储与配额约束。
 */
public interface BackupCollectionMaterialPort {
    /**
     * Resolves a member below the platform's private backup directory and prepares its parent directories. The caller retains ownership of the returned path.
     * <p>在平台私有备份目录内解析成员并准备父目录。返回路径仍由调用方持有。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return a member below the platform's private backup directory and prepares its parent directories / 在平台私有备份目录内解析成员并准备父目录
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    Path member(String name) throws IOException;

    /**
     * Opens a new material output with the platform's quota and path checks. The collection service closes the returned stream.
     * <p>通过平台配额及路径检查打开新的素材输出。采集服务负责关闭返回流。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return constructed or resolved output stream / 构造或解析得到的输出流
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    OutputStream open(Path path) throws IOException;
}
