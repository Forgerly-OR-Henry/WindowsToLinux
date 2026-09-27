package gold.debug.windowstolinux.app.service.mod;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import gold.debug.windowstolinux.app.db.log.LogRepository;
import gold.debug.windowstolinux.app.mod.installation.ModPackageReader;
import gold.debug.windowstolinux.app.service.mod.ModInstallationCleanup.Entry;

/** Reviews whole-owner installed programs and explicitly attributed auxiliary files without following links. / 不跟随链接地审阅整所有者安装程序及明确归属辅助文件。 */
final class ModOwnedCleanup {
    /** Prevents construction of this host-only file helper. / 禁止构造此仅宿主文件助手。 */
    private ModOwnedCleanup() {
    }

    /** Captured private data and exact registered emergency files. / 捕获的私有数据及精确登记应急文件。
     * @param dataRoot original trusted data root / 原可信数据根
     * @param parents shared ancestor witnesses, never deletion entries / 共用祖先依据，绝非删除条目
     * @param auxiliary owner-private data entries / 所有者私有数据条目
     * @param emergency files attributed by original category UUIDs / 按原类别 UUID 归属的文件 */
    record Scope(String dataRoot, List<Entry> parents, List<Entry> auxiliary, List<Entry> emergency) {
    }

    /** Captures only registered version directories plus their common owner container. / 仅捕获登记版本目录及其共用所有者容器。
     * @param root trusted owner installation root / 可信所有者安装根
     * @param versions complete original registered versions / 完整原登记版本
     * @return exact ordered program observations / 精确有序程序观察
     * @throws IOException if an unregistered entry, link or excess scope is found / 发现未登记条目、链接或超限范围时 */
    static List<Entry> programs(Path root, List<String> versions) throws IOException {
        if (!PlatformModInstallation.exists(root))
            return List.of();
        var container = ModInstallationCleanup.observe(root, -1);
        var entries = new ArrayList<Entry>();
        entries.add(container);
        try (var children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (!versions.contains(child.getFileName().toString()))
                    throw new IOException("Unregistered installed content remains");
                entries.addAll(ModInstallationCleanup.installed(child));
                if (entries.size() > ModPackageReader.MAX_ENTRIES + 1)
                    throw new IOException("Whole-owner program scope exceeds bounds");
            }
        }
        PlatformModInstallation.identity(root, container.identity());
        return ModInstallationCleanup.ordered(entries);
    }

    /** Validates every program entry against the original full version catalog. / 按原完整版本目录校验每个程序条目。
     * @param entries original accepted entries / 原接纳条目
     * @param root trusted owner installation root / 可信所有者安装根
     * @param versions original registered releases / 原登记发布
     * @throws IOException if evidence is malformed or outside registered programs / 证据畸形或超出登记程序时 */
    static void validatePrograms(List<Entry> entries, Path root, List<String> versions) throws IOException {
        if (entries == null || entries.size() > ModPackageReader.MAX_ENTRIES + 1)
            throw new IOException("Invalid whole program evidence");
        var groups = new HashMap<String, List<Entry>>();
        Entry container = null;
        for (var entry : entries) {
            ModInstallationCleanup.validateEntry(entry);
            Path path = Path.of(entry.path());
            if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(root))
                throw new IOException("Unowned program path");
            if (path.equals(root)) {
                if (container != null || !entry.directory())
                    throw new IOException("Invalid owner container");
                container = entry;
                continue;
            }
            String version = root.relativize(path).getName(0).toString();
            if (!versions.contains(version))
                throw new IOException("Unregistered version path");
            groups.computeIfAbsent(version, ignored -> new ArrayList<>()).add(entry);
        }
        if (!entries.isEmpty() && container == null)
            throw new IOException("Missing owner container");
        for (var group : groups.entrySet())
            ModInstallationCleanup.validateInstalled(group.getValue(), root.resolve(group.getKey()));
    }

    /** Captures private owner data and only emergency paths proven by original log registrations. / 捕获所有者私有数据及仅由原日志登记证明的应急路径。
     * @param dataRoot trusted database-owned root / 可信数据库所属根
     * @param owner exact registered Mod ID / 精确登记 Mod ID
     * @param targets original owned diagnostic categories / 原自有诊断类别
     * @return immutable physical cleanup scope / 不可变物理清理范围
     * @throws IOException if scope or native ownership cannot be observed / 无法观察范围或原生归属时 */
    static Scope inspect(Path dataRoot, String owner, List<LogRepository.Target> targets) throws IOException {
        var auxiliary = privateTree(dataRoot.resolve("mod").resolve(owner));
        var emergency = emergency(dataRoot, targets);
        var parents = new ArrayList<Entry>();
        parents.add(ModInstallationCleanup.observe(dataRoot, -1));
        if (!auxiliary.isEmpty())
            parents.add(ModInstallationCleanup.observe(dataRoot.resolve("mod"), -1));
        if (!emergency.isEmpty()) {
            parents.add(ModInstallationCleanup.observe(dataRoot.resolve("log"), -1));
            parents.add(ModInstallationCleanup.observe(dataRoot.resolve("log/emergency"), -1));
        }
        var scope = new Scope(dataRoot.toString(), List.copyOf(parents), auxiliary, emergency);
        validate(scope, dataRoot, owner, targets);
        return scope;
    }

    /** Reads a bounded private tree without treating unreadable paths as absent. / 读取有界私有目录树，不将不可读取路径当作不存在。
     * @param root exact owner-private directory / 精确所有者私有目录
     * @return ordered actual files and directories / 有序实际文件和目录
     * @throws IOException if redirected, unreadable or over bounds / 重定向、不可读取或超限时 */
    private static List<Entry> privateTree(Path root) throws IOException {
        if (!PlatformModInstallation.exists(root))
            return List.of();
        var entries = new ArrayList<Entry>();
        long[] bytes = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /** Captures an ordinary private directory. / 捕获普通私有目录。
             * @param path current path / 当前路径
             * @param attrs initial attributes / 初始属性
             * @return continued traversal / 继续遍历
             * @throws IOException if unsafe / 不安全时 */
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                capture(path, true);
                return FileVisitResult.CONTINUE;
            }

            /** Captures an ordinary private file. / 捕获普通私有文件。
             * @param path current path / 当前路径
             * @param attrs initial attributes / 初始属性
             * @return continued traversal / 继续遍历
             * @throws IOException if unsafe / 不安全时 */
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                capture(path, false);
                return FileVisitResult.CONTINUE;
            }

            /** Checks cardinality before reading bounded bytes. / 读取有界字节前检查数量。
             * @param path current entry / 当前条目
             * @param directory expected kind / 预期类型
             * @throws IOException if unsafe or oversized / 不安全或超限时 */
            private void capture(Path path, boolean directory) throws IOException {
                if (entries.size() >= ModPackageReader.MAX_ENTRIES)
                    throw new IOException("Private cleanup scope exceeds bounds");
                var entry = ModInstallationCleanup.observe(path,
                        directory ? -1 : ModPackageReader.MAX_BYTES - bytes[0]);
                bytes[0] += entry.size();
                entries.add(entry);
            }
        });
        PlatformModInstallation.identity(root, entries.getFirst().identity());
        return ModInstallationCleanup.ordered(entries);
    }

    /** Reads only exact registered filenames, including damaged pending files, without decoding or importing them. / 仅读取精确登记文件名，包含损坏待定文件，不解码或导入它们。
     * @param dataRoot trusted data root / 可信数据根
     * @param targets original owned targets / 原自有目标
     * @return exact present files / 精确存在文件
     * @throws IOException if bytes or ownership cannot be captured / 无法捕获字节或归属时 */
    private static List<Entry> emergency(Path dataRoot, List<LogRepository.Target> targets) throws IOException {
        var entries = new ArrayList<Entry>();
        long bytes = 0;
        for (var target : targets)
            for (String suffix : List.of(".log", ".pending")) {
                Path path = dataRoot.resolve("log/emergency").resolve(target.key() + suffix);
                if (!PlatformModInstallation.exists(path))
                    continue;
                var entry = ModInstallationCleanup.observe(path, ModPackageReader.MAX_BYTES - bytes);
                bytes += entry.size();
                entries.add(entry);
            }
        return ModInstallationCleanup.ordered(entries);
    }

    /** Validates bounded owner-only physical evidence against trusted roots and original categories. / 按可信根及原类别校验有界仅所有者物理证据。
     * @param scope persisted original scope / 持久原范围
     * @param dataRoot trusted current data root / 可信当前数据根
     * @param owner exact original owner / 精确原所有者
     * @param targets original registered categories / 原登记类别
     * @throws IOException if evidence expands beyond original ownership / 证据扩展超出原归属时 */
    static void validate(Scope scope, Path dataRoot, String owner, List<LogRepository.Target> targets)
            throws IOException {
        if (scope == null || !dataRoot.toString().equals(scope.dataRoot()) || scope.parents() == null
                || scope.auxiliary() == null || scope.auxiliary().size() > ModPackageReader.MAX_ENTRIES
                || scope.emergency() == null || scope.emergency().size() > 8192)
            throw new IOException("Invalid private deletion evidence");
        Path root = dataRoot.resolve("mod").resolve(owner);
        var indexed = new HashMap<String, Entry>();
        long bytes = 0;
        for (var entry : scope.auxiliary()) {
            ModInstallationCleanup.validateEntry(entry);
            Path path = Path.of(entry.path());
            if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(root)
                    || indexed.putIfAbsent(entry.path(), entry) != null || path.equals(root) && !entry.directory())
                throw new IOException("Invalid private path");
            bytes += entry.size();
        }
        if (bytes > ModPackageReader.MAX_BYTES)
            throw new IOException("Private deletion bytes exceed bounds");
        for (var entry : scope.auxiliary())
            if (!entry.path().equals(root.toString())) {
                var parent = indexed.get(Path.of(entry.path()).getParent().toString());
                if (parent == null || !parent.directory())
                    throw new IOException("Missing private ancestor");
            }
        var expectedFiles = new HashSet<Path>();
        for (var target : targets) {
            if (!target.owner().equals(owner))
                throw new IOException("Foreign diagnostic category");
            for (String suffix : List.of(".log", ".pending"))
                expectedFiles.add(dataRoot.resolve("log/emergency").resolve(target.key() + suffix));
        }
        var actualFiles = new HashSet<String>();
        bytes = 0;
        for (var entry : scope.emergency()) {
            ModInstallationCleanup.validateEntry(entry);
            if (entry.directory() || !expectedFiles.contains(Path.of(entry.path())) || !actualFiles.add(entry.path()))
                throw new IOException("Unregistered emergency path");
            bytes += entry.size();
        }
        if (bytes > ModPackageReader.MAX_BYTES)
            throw new IOException("Emergency deletion bytes exceed bounds");
        var expectedParents = new HashSet<>(Set.of(dataRoot));
        if (!scope.auxiliary().isEmpty())
            expectedParents.add(dataRoot.resolve("mod"));
        if (!scope.emergency().isEmpty()) {
            expectedParents.add(dataRoot.resolve("log"));
            expectedParents.add(dataRoot.resolve("log/emergency"));
        }
        var actualParents = new HashSet<Path>();
        for (var parent : scope.parents()) {
            ModInstallationCleanup.validateEntry(parent);
            if (!parent.directory() || !actualParents.add(Path.of(parent.path())))
                throw new IOException("Invalid shared ancestor");
        }
        if (!expectedParents.equals(actualParents))
            throw new IOException("Shared ancestor scope differs");
    }

    /** Rechecks original shared directories while allowing completed deletion to leave them absent. / 复核原共用目录，同时允许完成清理后其已不存在。
     * @param scope original scope / 原范围
     * @throws IOException if an original root is replaced or inaccessible / 原根被替代或不可访问时 */
    static void parents(Scope scope) throws IOException {
        for (var entry : scope.parents()) {
            Path path = Path.of(entry.path());
            if (PlatformModInstallation.exists(path) || entry.path().equals(scope.dataRoot()))
                PlatformModInstallation.identity(path, entry.identity());
        }
    }

    /** Removes only unchanged remaining owner-private and original-category file entries. / 仅移除未变的剩余所有者私有及原类别文件条目。
     * @param scope original scope / 原范围
     * @param dataRoot original trusted data root / 原可信数据根
     * @param owner exact original owner / 精确原所有者
     * @param targets original categories / 原类别
     * @throws IOException if new, changed or occupied entries prevent cleanup / 新增、变化或占用条目阻止清理时 */
    static void remove(Scope scope, Path dataRoot, String owner, List<LogRepository.Target> targets)
            throws IOException {
        validate(scope, dataRoot, owner, targets);
        parents(scope);
        var auxiliary = privateTree(dataRoot.resolve("mod").resolve(owner));
        var emergency = emergency(dataRoot, targets);
        var accepted = new ArrayList<>(scope.auxiliary());
        accepted.addAll(scope.emergency());
        var remaining = new ArrayList<>(auxiliary);
        remaining.addAll(emergency);
        ModInstallationCleanup.removeEntries(accepted, remaining, scope.parents());
    }
}
