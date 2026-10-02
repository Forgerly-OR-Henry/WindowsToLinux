package gold.debug.windowstolinux.app.file;

import java.io.IOException;
import java.util.*;
import gold.debug.windowstolinux.appapi.file.*;

/** Durable pre-effect evidence and a bounded owner-specific link registry. / 持久副作用前依据及有界所有者链接登记。 */
public interface FileJournal {
    /** Commits the complete bounded plan before any effect. / 在任何副作用前提交完整有界计划。 */
    UUID begin(String type, FilePath source, Optional<FilePath> target, String plan) throws IOException;
    /** Commits each phase before dispatch and its observed result afterwards. / 发出前提交阶段，之后提交观测结果。 */
    void record(UUID operation, String phase, FilePath source, Optional<FilePath> target, String detail) throws IOException;
    /** Returns only the caller's registered links on this host. / 只返回调用方在当前主机的登记链接。 */
    List<FileInfo> links(FilePath host) throws IOException;
    /** Verifies a registered target identity before following a moved original. / 跟随移动原件前核对登记目标身份。 */
    boolean targetMatches(FileInfo link, FileInfo target) throws IOException;
    /** Saves an actually observed link and target identity. / 保存实际观测的链接及目标身份。 */
    void saveLink(FileInfo link, Optional<FileInfo> target) throws IOException;
    /** Removes only the caller's existing link registration. / 仅解除调用方既有链接登记。 */
    void removeLink(FilePath link) throws IOException;
}
