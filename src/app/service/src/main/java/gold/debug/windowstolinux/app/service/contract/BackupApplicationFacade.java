package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;

import java.io.IOException;
import java.nio.file.Path;

/** UI-facing local backup inspection and candidate preparation contract. / 面向 UI 的本地备份检查与候选准备契约。 */
public interface BackupApplicationFacade {
    /** Validates one archive without extraction or remote access. / 校验一个归档且不提取、不访问远端。 */
    BackupArchiveInspection inspectBackup(Path archive) throws IOException;

    /** Creates a new isolated local candidate without activation. / 创建一个新的隔离本地候选且不激活。 */
    PreparedBackupCandidate prepareBackupCandidate(Path archive) throws IOException;
}
