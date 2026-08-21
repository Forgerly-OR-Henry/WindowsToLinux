package gold.debug.windowstolinux.shared.backup.format;

import java.io.IOException;
import java.io.InputStream;

/** Opens one fresh member stream for deterministic archive construction. / 为确定性归档构建打开一个全新的成员流。 */
@FunctionalInterface
public interface BackupArchiveStream {
    /** Opens content that the caller closes. / 打开由调用方关闭的内容。 */
    InputStream open() throws IOException;
}
