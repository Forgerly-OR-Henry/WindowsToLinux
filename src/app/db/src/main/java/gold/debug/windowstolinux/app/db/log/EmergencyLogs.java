package gold.debug.windowstolinux.app.db.log;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Bounded category files with verified staging and same-directory atomic publication. / 具有已核验暂存及同目录原子发布的有界类别文件。 */
public final class EmergencyLogs {
    /** Fixed confirmed platform data root. / 固定且已确认的平台数据根。 */
    private final Path root;

    /** Canonical newest-first ordering shared with SQL CHAR UUID ordering. / 与 SQL 字符 UUID 排序共用的规范新旧顺序。 */
    public static final Comparator<LogRecordCodec.Record> NEWEST_FIRST = Comparator
            .comparingLong(LogRecordCodec.Record::recordedAt).thenComparing(record -> record.id().toString())
            .reversed();

    /** Binds storage without making fallback directories elsewhere. / 绑定存储，不在其他位置创建备用目录。
     * @param root confirmed database root / 已确认数据库根 */
    public EmergencyLogs(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    /** Lists generated files whose registration is absent without importing or deleting them. / 列出登记缺失的生成文件，不导入或删除它们。
     * @param registered current authoritative keys / 当前权威登记键
     * @return retained unregistered filenames / 保留的未登记文件名
     * @throws IOException if the fixed directory cannot be inspected / 无法检查固定目录时 */
    public List<String> unregistered(Set<UUID> registered) throws IOException {
        var directory = directory(false);
        if (directory == null)
            return List.of();
        try (var paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name
                            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(log|pending)"))
                    .filter(name -> !registered.contains(UUID.fromString(name.substring(0, 36)))).sorted().toList();
        }
    }

    /** Reads complete active records only when no interrupted replacement remains. / 仅在没有中断替换残留时读取完整活动记录。
     * @param target original registered target / 原登记目标
     * @return immutable validated records / 不可变已校验记录
     * @throws IOException if files, framing or attribution cannot be verified / 无法核实文件、帧或归属时 */
    public List<LogRecordCodec.Record> read(LogRepository.Target target) throws IOException {
        var directory = directory(false);
        if (directory == null)
            return List.of();
        var pending = directory.resolve(target.key() + ".pending");
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            // Even an LF-terminated prefix does not prove that the complete replacement was written.
            // 即使前缀以换行结束，也不能证明整个替换集合已写完。
            throw new IOException("Emergency replacement remains unconfirmed; both files are retained");
        }
        return readFile(target, directory.resolve(target.key() + ".log"));
    }

    /** Appends one original event while retaining the newest hundred per category. / 追加一个原事件，每类保留最近一百条。
     * @param target registered destination / 登记目的地
     * @param record original event identity / 原事件身份
     * @throws IOException if existing records or replacement cannot be confirmed / 无法确认既有记录或替换时 */
    public void append(LogRepository.Target target, LogRecordCodec.Record record) throws IOException {
        var records = new ArrayList<>(read(target));
        records.add(record);
        replace(target, merge(records, 100));
    }

    /** Atomically replaces only this registered category after validating all bytes. / 校验全部字节后，仅原子替换此登记类别。
     * @param target original registration / 原登记
     * @param records exact confirmed replacement / 精确已确认替换集合
     * @throws IOException if publication remains unconfirmed / 发布仍未确认时 */
    public void replace(LogRepository.Target target, List<LogRecordCodec.Record> records) throws IOException {
        if (records.size() > 100)
            throw new IOException("Emergency category exceeds its record limit");
        var bytes = new ByteArrayOutputStream();
        for (var record : merge(records, 100)) {
            byte[] line = LogRecordCodec.encode(target.key(), record);
            LogRecordCodec.decode(target, line);
            bytes.write(line);
        }
        read(target);
        var directory = directory(true);
        var pending = directory.resolve(target.key() + ".pending");
        try (var file = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes.toByteArray());
            while (buffer.hasRemaining())
                file.write(buffer);
            file.force(true);
        }
        readFile(target, pending);
        Files.move(pending, directory.resolve(target.key() + ".log"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** Validates fixed directories without following symbolic links or junctions. / 不跟随符号链接或联接地校验固定目录。
     * @param create whether storage is now needed / 当前是否需要存储
     * @return fixed directory, absent if no emergency data exists / 固定目录，无应急数据时不存在
     * @throws IOException if a path is not an ordinary directory / 路径不是普通目录时 */
    private Path directory(boolean create) throws IOException {
        Path current = root;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))
            throw new IOException("Confirmed diagnostic root is unavailable");
        for (String part : List.of("log", "emergency")) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!create)
                    return null;
                Files.createDirectory(current);
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)
                    || !current.toRealPath().equals(current))
                throw new IOException("Emergency directory identity is unsafe");
        }
        return current;
    }

    /** Reads a bounded whole category and rejects partial or corrupt lines without deleting them. / 读取有界完整类别，拒绝残缺或损坏行且不删除它们。
     * @param target expected attribution / 预期归属
     * @param path exact generated file path / 精确生成文件路径
     * @return verified complete records / 已核验完整记录
     * @throws IOException if content or physical path is invalid / 内容或实际路径无效时 */
    private static List<LogRecordCodec.Record> readFile(LogRepository.Target target, Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return List.of();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                || !path.toRealPath().equals(path) || Files.size(path) > 100L * LogRecordCodec.MAX_BYTES)
            throw new IOException("Emergency file is not a bounded regular category file");
        var records = new ArrayList<LogRecordCodec.Record>();
        try (var input = new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))) {
            var line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (line.size() == LogRecordCodec.MAX_BYTES)
                    throw new IOException("Emergency frame exceeds its limit");
                line.write(value);
                if (value == '\n') {
                    if (records.size() == 100)
                        throw new IOException("Emergency category exceeds its record limit");
                    records.add(LogRecordCodec.decode(target, line.toByteArray()));
                    line.reset();
                }
            }
            if (line.size() != 0)
                throw new IOException("Emergency final record is incomplete");
        }
        return merge(records, 100);
    }

    /** Deduplicates original IDs, rejects content conflicts and applies canonical retention. / 对原 ID 去重、拒绝内容冲突并应用规范保留。
     * @param records input records / 输入记录
     * @param keep confirmed retention limit / 已确认保留上限
     * @return newest complete records / 最新完整记录
     * @throws IOException if duplicate IDs have conflicting content / 重复 ID 内容冲突时 */
    public static List<LogRecordCodec.Record> merge(Collection<LogRecordCodec.Record> records, int keep)
            throws IOException {
        var unique = new HashMap<UUID, LogRecordCodec.Record>();
        for (var record : records) {
            var prior = unique.putIfAbsent(record.id(), record);
            if (prior != null && !prior.equals(record))
                throw new IOException("Emergency log identity has conflicting content");
        }
        return unique.values().stream().sorted(NEWEST_FIRST).limit(keep).toList();
    }
}
