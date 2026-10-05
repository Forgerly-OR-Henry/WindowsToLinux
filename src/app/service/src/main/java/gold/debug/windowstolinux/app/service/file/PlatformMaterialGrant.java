package gold.debug.windowstolinux.app.service.file;

import java.io.IOException;
import java.util.*;

import gold.debug.windowstolinux.app.file.source.SelectedDirectory;
import gold.debug.windowstolinux.app.file.source.SourceMaterialManifest;
import gold.debug.windowstolinux.appapi.file.source.*;
import gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Host-only whitelist attached to the consumer's own selection object. / 附着于消费方自己的选择对象的仅宿主白名单。 */
interface PlatformMaterialGrant {
    /** Verifies the original persisted hold and immutable physical facts. / 核验原持久持有及不可变物理事实。
     * @throws ApiException if the original grant is unavailable / 原授权不可用时 */
    void require() throws ApiException;

    /** Returns the original exact immutable file set. / 返回原精确不可变文件集。
     * @return original manifest / 原清单 */
    SortedMap<String, SelectedDirectory.Content> entries();

    /** Authorizes only an exact original regular filename. / 仅授权精确原普通文件名称。
     * @param path exact requested file / 精确请求文件
     * @return original content facts / 原内容事实 */
    default SelectedDirectory.Content file(String path) {
        var value = entries().get(SelectedDirectory.relative(path));
        if (value == null)
            throw new IllegalArgumentException("Source file is outside the material whitelist");
        return value;
    }

    /** Enumerates only whitelisted files and their necessary parent directories. / 仅列举白名单文件及其必要父目录。
     * @param directory exact directory or empty root / 精确目录或空根
     * @param maximum original bounded listing capacity / 原有界列举容量
     * @return complete sorted authorized children / 完整有序授权子项 */
    default List<FileEntry> list(String directory, int maximum) {
        if (directory == null || maximum < 1 || maximum > 100000)
            throw new IllegalArgumentException("Invalid material listing request");
        String prefix = directory.isEmpty() ? "" : SelectedDirectory.relative(directory) + "/";
        var found = new TreeMap<String, FileEntry>();
        boolean parent = directory.isEmpty();
        for (var entry : entries().entrySet()) {
            if (!entry.getKey().startsWith(prefix))
                continue;
            parent = true;
            String remainder = entry.getKey().substring(prefix.length());
            int slash = remainder.indexOf('/');
            String child = slash < 0 ? entry.getKey() : prefix + remainder.substring(0, slash);
            found.put(child, new FileEntry(child, slash < 0 ? FileEntry.EntryType.FILE : FileEntry.EntryType.DIRECTORY,
                slash < 0 ? entry.getValue().size() : 0));
            if (found.size() > maximum)
                throw new IllegalArgumentException("Material listing exceeds its entry budget");
        }
        if (!parent)
            throw new IllegalArgumentException("Directory is outside the material whitelist");
        return List.copyOf(found.values());
    }

    /** Binds a subsequent snapshot to the original subset manifest. / 将后续快照绑定到原子集清单。
     * @param request exact original subset request / 精确原子集请求
     * @return expected canonical subset digest / 预期规范子集摘要
     * @throws IOException if framing fails / 帧编码失败时 */
    default String snapshotDigest(SnapshotRequest request) throws IOException {
        var subset = new TreeMap<String, SelectedDirectory.Content>();
        for (String path : request.relativePaths())
            if (subset.put(path, file(path)) != null)
                throw new IllegalArgumentException("Duplicate material snapshot file");
        return SourceMaterialManifest.digest(subset);
    }
}
