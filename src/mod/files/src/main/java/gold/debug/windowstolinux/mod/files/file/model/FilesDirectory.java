package gold.debug.windowstolinux.mod.files.file.model;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;

/** Complete bounded observations of one directory, without reusable authority. / 一个目录的完整有界观测，不含可复用授权。
 * @param location original host-qualified browsing position / 原带主机浏览位置
 * @param entries immediate no-follow entries / 不跟随链接的直接子项 */
public record FilesDirectory(FilePath location, List<FileInfo> entries) {
    /** Copies actual observations. / 复制实际观测。
     * @param location original browsing position / 原浏览位置
     * @param entries actual immediate entries / 实际直接子项 */
    public FilesDirectory {
        Objects.requireNonNull(location);
        entries = List.copyOf(entries);
    }
}
