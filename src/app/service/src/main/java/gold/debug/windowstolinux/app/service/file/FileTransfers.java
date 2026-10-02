package gold.debug.windowstolinux.app.service.file;

import java.util.Optional;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Adapts one preplanned ordinary file to the existing journalled transfer state machine. / 将单个预先计划的普通文件接入既有持久传输状态机。 */
@FunctionalInterface
public interface FileTransfers {
    /** Transfers only to this actual selected destination; the old target must still match. / 仅传输到实际选定目标，旧目标仍须匹配。 */
    FileReport.Item transfer(FileInfo source, FilePath target, Optional<FileInfo> expectedTarget, TaskContext task) throws ApiException;
}
