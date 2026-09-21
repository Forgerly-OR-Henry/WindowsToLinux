package gold.debug.windowstolinux.shared.backup.format;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens one fresh member stream for deterministic archive construction. / 为确定性归档构建打开一个全新的成员流。
 */
@FunctionalInterface
public interface BackupArchiveStream {
    /**
     * Opens content that the caller closes. / 打开由调用方关闭的内容。
     *
     * @return constructed or resolved input stream / 构造或解析得到的输入流
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    InputStream open() throws IOException;
}
