package gold.debug.windowstolinux.app.service.mod.install;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

import gold.debug.windowstolinux.app.mod.install.ModPackageReader;
import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;

/** Reviews and removes only an explicitly accepted installation's bounded file set. / 仅审阅和移除明确接纳安装的有界文件集合。 */
final class ModInstallationCleanup {
    /** One exact reviewed file or directory, retained before any deletion. / 删除前保留的一个精确审阅文件或目录。
     * @param path absolute canonical path / 绝对规范路径
     * @param identity native file identity / 原生文件身份
     * @param directory whether an empty-after-children directory / 是否为子项处理后为空的目录
     * @param size observed file length, zero for directories / 观察文件长度，目录为零
     * @param sha256 observed file digest, absent for directories / 观察文件摘要，目录为空 */
    record Entry(String path, String identity, boolean directory, long size, String sha256) {
    }

    /** Prevents construction of the physical cleanup helper. / 禁止构造物理清理辅助类。 */
    private ModInstallationCleanup() {
    }

    /** Captures only original package names under proven operation containers. / 仅捕获已证明操作容器下的原包名称。
     * Partial file bytes are displayed and explicitly approved, not called original package bytes. / 部分文件字节须展示并明确批准，不称为原包字节。
     * @param stage exact operation staging root / 精确操作暂存根
     * @param stageId retained original staging identity / 保留的原暂存身份
     * @param target exact unpublished-registration target / 精确尚未登记目标
     * @param programId retained published program identity or absent / 保留的发布程序身份或缺省
     * @param files original package file declarations / 原包文件声明
     * @return immutable exact observations in deletion order / 按删除顺序排列的不可变精确观察
     * @throws IOException if ownership, scope or bytes cannot be established / 无法确认归属、范围或字节时 */
    static List<Entry> inspect(Path stage, String stageId, Path target, String programId,
                               List<ModPackageReader.FileEntry> files) throws IOException {
        var allowed = allowed(stage, target, files);
        var result = new ArrayList<Entry>();
        for (Path root : List.of(stage, target)) {
            if (!PlatformModInstallation.exists(root))
                continue;
            PlatformModInstallation.identity(root, root.equals(stage) ? stageId : programId);
            if (root.equals(stage) && programId != null && PlatformModInstallation.exists(stage.resolve("program")))
                PlatformModInstallation.identity(stage.resolve("program"), programId);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                /** Captures ordinary allowed directories without following links. / 捕获允许普通目录，不跟随链接。
                 * @param path current directory / 当前目录
                 * @param attrs original attributes / 原属性
                 * @return continue traversal / 继续遍历
                 * @throws IOException if unexpected or redirected / 意外或重定向时 */
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                    capture(path);
                    return FileVisitResult.CONTINUE;
                }

                /** Captures exact bounded file content. / 捕获精确有界文件内容。
                 * @param path current file / 当前文件
                 * @param attrs original attributes / 原属性
                 * @return continue traversal / 继续遍历
                 * @throws IOException if unexpected or changed / 意外或变化时 */
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    capture(path);
                    return FileVisitResult.CONTINUE;
                }

                /** Enforces original path and length bounds before hashing. / 摘要前强制原路径及长度界限。
                 * @param path current path / 当前路径
                 * @throws IOException if outside the accepted package layout / 超出接纳包布局时 */
                private void capture(Path path) throws IOException {
                    Long maximum = allowed.get(path.toString());
                    if (maximum == null)
                        throw new IOException("Unexpected installation file must be resolved before cancellation");
                    result.add(observe(path, maximum));
                }
            });
        }
        result.sort(Comparator.<Entry>comparingInt(entry -> Path.of(entry.path()).getNameCount()).reversed()
            .thenComparing(Entry::path));
        return List.copyOf(result);
    }

    /** Validates retained deletion evidence against fixed operation paths and original file declarations. / 按固定操作路径及原文件声明校验保留删除依据。
     * @param entries original accepted snapshot / 原接纳快照
     * @param stage exact staging root / 精确暂存根
     * @param stageId original root identity / 原根身份
     * @param target exact target / 精确目标
     * @param programId original published identity / 原发布身份
     * @param files original package files / 原包文件
     * @throws IOException if evidence is inconsistent or out of scope / 依据不一致或越界时 */
    static void validate(List<Entry> entries, Path stage, String stageId, Path target, String programId,
                         List<ModPackageReader.FileEntry> files) throws IOException {
        var allowed = allowed(stage, target, files);
        var indexed = new HashMap<String, Entry>();
        if (entries.size() > allowed.size())
            throw new IOException("Excessive cancellation evidence");
        for (var entry : entries) {
            Long maximum = allowed.get(entry.path());
            if (maximum == null || indexed.putIfAbsent(entry.path(), entry) != null
                || !entry.identity().matches("[0-9a-f]{48}") || entry.directory() != (maximum == -1)
                || entry.size() < 0
                || (entry.directory()
                ? entry.size() != 0 || entry.sha256() != null
                : entry.size() > maximum || entry.sha256() == null
                || !entry.sha256().matches("[0-9a-f]{64}")))
                throw new IOException("Invalid cancellation file evidence");
        }
        for (var entry : entries) {
            Path path = Path.of(entry.path());
            if (path.equals(stage)) {
                if (!entry.identity().equals(stageId))
                    throw new IOException("Cancellation staging identity differs");
            } else if (path.equals(target)) {
                if (!entry.identity().equals(programId))
                    throw new IOException("Cancellation publication identity differs");
            } else {
                var parent = indexed.get(path.getParent().toString());
                if (parent == null || !parent.directory())
                    throw new IOException("Missing cancellation ancestor evidence");
                if (path.equals(stage.resolve("program")) && programId != null && !entry.identity().equals(programId))
                    throw new IOException("Cancellation program identity differs");
            }
        }
    }

    /** Removes the still-present subset of an accepted snapshot, retaining changed or foreign files. / 移除接纳快照中仍存在的子集，保留变化或外来文件。
     * @param accepted persisted original snapshot / 已持久原快照
     * @param stage exact staging root / 精确暂存根
     * @param stageId original staging identity / 原暂存身份
     * @param target exact target / 精确目标
     * @param programId original publication identity / 原发布身份
     * @param files original package declarations / 原包声明
     * @throws IOException if cleanup is unconfirmed / 清理未确认时 */
    static void remove(List<Entry> accepted, Path stage, String stageId, Path target, String programId,
                       List<ModPackageReader.FileEntry> files) throws IOException {
        validate(accepted, stage, stageId, target, programId, files);
        var indexed = new HashMap<String, Entry>();
        accepted.forEach(entry -> indexed.put(entry.path(), entry));
        var remaining = inspect(stage, stageId, target, programId, files);
        for (var actual : remaining)
            if (!actual.equals(indexed.get(actual.path())))
                throw new IOException("Cancellation files changed after confirmation");
        for (var entry : remaining) {
            Path path = Path.of(entry.path());
            for (Path ancestor = path.getParent(); indexed
                .containsKey(ancestor.toString()); ancestor = ancestor.getParent())
                PlatformModInstallation.identity(ancestor, indexed.get(ancestor.toString()).identity());
            if (!entry.equals(observe(path, entry.directory() ? -1 : entry.size())))
                throw new IOException("Cancellation file changed before deletion");
            Files.delete(path);
        }
        if (PlatformModInstallation.exists(stage) || PlatformModInstallation.exists(target))
            throw new IOException("Cancellation directories remain");
    }

    /** Derives a closed path set, never trusting retained arbitrary absolute paths. / 推导封闭路径集合，绝不信任保留的任意绝对路径。
     * @param stage operation staging root / 操作暂存根
     * @param target publication target / 发布目标
     * @param files original files / 原文件
     * @return paths mapped to maximum file length or minus one for directories / 路径到文件最大长度的映射，目录为负一
     * @throws IOException if original file evidence is malformed / 原文件依据畸形时 */
    private static Map<String, Long> allowed(Path stage, Path target, List<ModPackageReader.FileEntry> files)
        throws IOException {
        var result = new HashMap<String, Long>();
        result.put(stage.toString(), -1L);
        for (Path root : List.of(stage.resolve("program"), target)) {
            for (String name : List.of("", "main", "lib"))
                result.put(root.resolve(name).toString(), -1L);
            long total = 0;
            var names = new HashSet<String>();
            for (var file : files) {
                String name = file.path();
                if ((!Set.of("mod.json", "README.md", "LICENSE", "CHANGELOG.md").contains(name)
                    && !name.matches("(?:main|lib)/[^/\\\\:]+\\.jar")) || !names.add(name) || file.size() < 0
                    || file.size() > ModPackageReader.MAX_BYTES)
                    throw new IOException("Invalid original cancellation scope");
                total += file.size();
                if (total > ModPackageReader.MAX_BYTES)
                    throw new IOException("Excessive cancellation content");
                Path path = root.resolve(name);
                if (!path.normalize().equals(path))
                    throw new IOException("Invalid cancellation path");
                result.put(path.toString(), file.size());
            }
        }
        return result;
    }

    /** Captures stable original identity and bounded content without claiming package integrity. / 捕获稳定原身份及有界内容，不声称包完整性。
     * @param path original path / 原路径
     * @param maximum file size bound or minus one for a directory / 文件大小界限，目录为负一
     * @return exact current evidence / 精确当前依据
     * @throws IOException if changed, redirected or oversized / 变化、重定向或超限时 */
    static Entry observe(Path path, long maximum) throws IOException {
        boolean directory = maximum == -1;
        PlatformModInstallation.ordinary(path, directory);
        String identity = WindowsFileIdentity.read(path);
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String hash = null;
        if (!directory) {
            if (before.size() > maximum)
                throw new IOException("Cancellation file exceeds original package length");
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                long length = 0;
                byte[] buffer = new byte[65536];
                try (var input = Files.newInputStream(path)) {
                    for (int read; (read = input.read(buffer)) != -1; ) {
                        length += read;
                        if (length > maximum)
                            throw new IOException("Cancellation file grew while reading");
                        digest.update(buffer, 0, read);
                    }
                }
                if (length != before.size())
                    throw new IOException("Cancellation file changed while reading");
                hash = HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }
        PlatformModInstallation.identity(path, identity);
        var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!directory
            && (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())))
            throw new IOException("Cancellation file changed during review");
        return new Entry(path.toString(), identity, directory, directory ? 0 : before.size(), hash);
    }

    /** Captures the current ordinary installed package without executing or trusting its code. / 捕获当前普通安装包，不执行或信任其代码。
     * @param root registered installed root / 登记安装根
     * @return exact files in deletion order, empty if already absent / 按删除顺序的精确文件，已不存在则为空
     * @throws IOException if unknown layout, redirection or bounds prevent review / 未知布局、重定向或界限阻止审阅时 */
    static List<Entry> installed(Path root) throws IOException {
        if (!PlatformModInstallation.exists(root))
            return List.of();
        var entries = new ArrayList<Entry>();
        long[] total = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /** Captures only ordinary package directories. / 仅捕获普通包目录。
             * @param path current directory / 当前目录
             * @param attrs original attributes / 原属性
             * @return continued traversal / 继续遍历
             * @throws IOException if outside layout / 超出布局时 */
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                capture(path, true);
                return FileVisitResult.CONTINUE;
            }

            /** Captures bounded actual bytes, including damaged program bytes. / 捕获有界实际字节，包括损坏程序字节。
             * @param path current file / 当前文件
             * @param attrs original attributes / 原属性
             * @return continued traversal / 继续遍历
             * @throws IOException if outside layout or bounds / 超出布局或界限时 */
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                capture(path, false);
                return FileVisitResult.CONTINUE;
            }

            /** Checks layout before reading file contents. / 读取文件内容前检查布局。
             * @param path current entry / 当前条目
             * @param directory expected kind / 预期种类
             * @throws IOException if unsafe / 不安全时 */
            private void capture(Path path, boolean directory) throws IOException {
                if (!installedPath(root, path, directory) || entries.size() >= ModPackageReader.MAX_ENTRIES + 3)
                    throw new IOException("Unexpected installed content must be resolved before deletion");
                var entry = observe(path, directory ? -1 : ModPackageReader.MAX_BYTES - total[0]);
                total[0] += entry.size();
                entries.add(entry);
            }
        });
        PlatformModInstallation.identity(root, entries.getFirst().identity());
        return ordered(entries);
    }

    /** Validates persisted installed evidence against the registered root and package layout. / 按登记根和包布局校验持久安装依据。
     * @param entries accepted physical scope / 接纳物理范围
     * @param root registered root / 登记根
     * @throws IOException if malformed or out of scope / 畸形或越界时 */
    static void validateInstalled(List<Entry> entries, Path root) throws IOException {
        if (entries == null || entries.size() > ModPackageReader.MAX_ENTRIES + 3)
            throw new IOException("Installed deletion scope exceeds bounds");
        var indexed = new HashMap<String, Entry>();
        long total = 0;
        for (var entry : entries) {
            validateEntry(entry);
            if (!installedPath(root, Path.of(entry.path()), entry.directory())
                || indexed.putIfAbsent(entry.path(), entry) != null)
                throw new IOException("Invalid installed deletion path");
            total += entry.size();
            if (total > ModPackageReader.MAX_BYTES)
                throw new IOException("Installed deletion content exceeds bounds");
        }
        for (var entry : entries) {
            Path path = Path.of(entry.path());
            if (path.equals(root))
                continue;
            var parent = indexed.get(path.getParent().toString());
            if (parent == null || !parent.directory())
                throw new IOException("Missing installed ancestor evidence");
        }
    }

    /** Deletes only the unchanged still-present subset of the confirmed installed scope. / 仅删除确认安装范围中未变且仍存在的子集。
     * @param accepted original accepted entries / 原接纳条目
     * @param root exact installed root / 精确安装根
     * @throws IOException if replacement, extras or deletion remain unconfirmed / 替代物、额外内容或删除未确认时 */
    static void removeInstalled(List<Entry> accepted, Path root) throws IOException {
        validateInstalled(accepted, root);
        removeEntries(accepted, installed(root), List.of());
        if (PlatformModInstallation.exists(root))
            throw new IOException("Installed program still remains");
    }

    /** Removes a verified remaining subset while rechecking all captured ancestor identities. / 移除已核验剩余子集，并复核全部捕获祖先身份。
     * Callers validate the fixed layout and obtain current entries before invoking this helper. / 调用方须先校验固定布局并取得当前条目。
     * @param accepted original authorized entries / 原授权条目
     * @param remaining actually observed remaining entries / 实际观察剩余条目
     * @param parents additional shared ancestors that must never be removed / 绝不移除的额外共用祖先
     * @throws IOException if bytes, ancestry or deletion are unconfirmed / 字节、祖先或删除未确认时 */
    static void removeEntries(List<Entry> accepted, List<Entry> remaining, List<Entry> parents) throws IOException {
        var indexed = new HashMap<String, Entry>();
        accepted.forEach(e -> indexed.put(e.path(), e));
        for (var entry : remaining)
            if (!entry.equals(indexed.get(entry.path())))
                throw new IOException("Deletion scope changed after confirmation");
        for (var parent : parents) {
            var previous = indexed.putIfAbsent(parent.path(), parent);
            if (previous != null && !previous.equals(parent))
                throw new IOException("Conflicting deletion ancestor");
        }
        for (var entry : ordered(remaining)) {
            Path path = Path.of(entry.path());
            for (Path parent = path.getParent(); parent != null
                && indexed.containsKey(parent.toString()); parent = parent.getParent())
                PlatformModInstallation.identity(parent, indexed.get(parent.toString()).identity());
            if (!entry.equals(observe(path, entry.directory() ? -1 : entry.size())))
                throw new IOException("Reviewed entry changed before deletion");
            Files.delete(path);
        }
    }

    /** Validates one exact ordinary native identity and bounded content record. / 校验一个精确普通原生身份及有界内容记录。
     * @param entry persisted entry / 持久条目
     * @throws IOException if invalid / 无效时 */
    static void validateEntry(Entry entry) throws IOException {
        if (entry == null || entry.path() == null || entry.identity() == null
            || !entry.identity().matches("[0-9a-f]{48}") || entry.size() < 0
            || entry.size() > ModPackageReader.MAX_BYTES
            || (entry.directory()
            ? entry.size() != 0 || entry.sha256() != null
            : entry.sha256() == null || !entry.sha256().matches("[0-9a-f]{64}")))
            throw new IOException("Invalid physical deletion evidence");
    }

    /** Tests a fixed installed layout without trusting persisted absolute strings. / 检查固定安装布局，不信任持久绝对字符串。
     * @param root registered root / 登记根
     * @param path candidate / 候选
     * @param directory expected kind / 预期种类
     * @return whether within the closed layout / 是否在封闭布局内 */
    private static boolean installedPath(Path root, Path path, boolean directory) {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(root))
            return false;
        String name = root.relativize(path).toString().replace('\\', '/');
        return directory
            ? Set.of("", "main", "lib").contains(name)
            : Set.of("mod.json", "README.md", "LICENSE", "CHANGELOG.md").contains(name)
            || name.matches("(?:main|lib)/[^/\\\\:]+\\.jar");
    }

    /** Orders actual children before their empty parent directories. / 将实际子项排在其空父目录前。
     * @param entries observed entries / 观察条目
     * @return immutable deterministic deletion order / 不可变确定删除顺序 */
    static List<Entry> ordered(List<Entry> entries) {
        return entries.stream().sorted(Comparator.<Entry>comparingInt(e -> Path.of(e.path()).getNameCount()).reversed()
            .thenComparing(Entry::path)).toList();
    }
}
