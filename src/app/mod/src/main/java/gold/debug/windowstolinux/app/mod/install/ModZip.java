package gold.debug.windowstolinux.app.mod.install;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.text.Normalizer;
import java.util.*;

import org.apache.commons.compress.archivers.zip.*;

/** Bounds ZIP metadata and nested views without expanding embedded archives. / 约束 ZIP 元数据及嵌套视图，不展开内嵌归档。 */
final class ModZip implements AutoCloseable {
    /** Original read-only file handle, owned by the caller. / 调用方持有的原只读文件句柄。 */
    private final FileChannel file;

    /** Exact original byte range. / 精确原始字节范围。 */
    private final long start, length;

    /** Parsed ZIP owned only by this view. / 仅此视图持有的已解析 ZIP。 */
    final ZipFile zip;

    /** Validated entries in physical order. / 按物理顺序的已验证条目。 */
    final List<ZipArchiveEntry> entries;

    /** Opens a bounded archive after checking its directory before library allocation. / 库分配之前检查目录，再打开有界归档。
     * @param file original handle / 原句柄
     * @param start range offset / 范围偏移
     * @param length range length / 范围长度
     * @param limit maximum entries / 最大条目数
     * @param portable whether paths will be extracted on Windows / 路径是否将在 Windows 展开
     * @throws IOException if ZIP metadata or paths are invalid / ZIP 元数据或路径无效时 */
    ModZip(FileChannel file, long start, long length, int limit, boolean portable) throws IOException {
        this.file = file;
        this.start = start;
        this.length = length;
        long central = directory(limit);
        zip = ZipFile.builder().setSeekableByteChannel(new Slice())
            .setCharset(portable ? Charset.forName("IBM437") : StandardCharsets.UTF_8)
            .setUseUnicodeExtraFields(false).get();
        try {
            var found = new ArrayList<ZipArchiveEntry>();
            var names = new HashMap<String, String>();
            var kinds = new HashMap<String, Boolean>();
            var listed = zip.getEntriesInPhysicalOrder();
            long previous = 0;
            while (listed.hasMoreElements()) {
                var entry = listed.nextElement();
                if (found.size() >= limit)
                    throw new IOException("ZIP entry limit exceeded");
                String name = name(entry, portable);
                String key = portable ? Normalizer.normalize(name, Normalizer.Form.NFC).toUpperCase(Locale.ROOT) : name;
                if (names.putIfAbsent(key, name) != null)
                    throw new IOException("Duplicate ZIP path");
                kinds.put(key, entry.isDirectory());
                if (entry.getDiskNumberStart() != 0 || !zip.canReadEntryData(entry)
                    || (entry.getRawFlag() & (1 | 64 | 8192)) != 0 || entry.isUnixSymlink()
                    || (entry.getExternalAttributes() & 0x400) != 0)
                    throw new IOException("Unsupported ZIP entry or link");
                int type = entry.getUnixMode() & 0170000;
                if (type != 0 && type != (entry.isDirectory() ? 0040000 : 0100000))
                    throw new IOException("ZIP entry is not a regular file or directory");
                long offset = entry.getLocalHeaderOffset();
                long end = entry.getDataOffset() + entry.getCompressedSize();
                if (offset < previous || entry.getCompressedSize() < 0 || entry.getSize() < 0
                    || entry.getDataOffset() < offset || end < entry.getDataOffset() || end > central)
                    throw new IOException("Overlapping or out-of-range ZIP entry");
                local(entry);
                previous = end;
                found.add(entry);
            }
            for (var key : kinds.keySet()) {
                for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1))
                    if (Boolean.FALSE.equals(kinds.get(key.substring(0, slash))))
                        throw new IOException("A ZIP file is used as a directory");
            }
            entries = List.copyOf(found);
        } catch (IOException | RuntimeException failure) {
            try {
                zip.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    /** Checks the complete central directory before any unbounded entry allocation. / 无界条目分配之前检查完整中央目录。
     * @param limit maximum entries / 最大条目数
     * @return central directory offset / 中央目录偏移
     * @throws IOException if truncated, split or oversized / 截断、分卷或过大时 */
    private long directory(int limit) throws IOException {
        if (length < 22)
            throw new IOException("Incomplete ZIP archive");
        int tailLength = (int) Math.min(length, 65557);
        var tail = bytes(length - tailLength, tailLength);
        int end = -1;
        for (int i = tailLength - 22; i >= 0; i--)
            if (tail.getInt(i) == 0x06054b50 && i + 22 + Short.toUnsignedInt(tail.getShort(i + 20)) == tailLength) {
                end = i;
                break;
            }
        if (end < 0)
            throw new IOException("ZIP end record is missing");
        long boundary = length - tailLength + end;
        long count = Short.toUnsignedInt(tail.getShort(end + 10));
        long size = Integer.toUnsignedLong(tail.getInt(end + 12));
        long offset = Integer.toUnsignedLong(tail.getInt(end + 16));
        if (tail.getShort(end + 4) != 0 || tail.getShort(end + 6) != 0
            || tail.getShort(end + 8) != tail.getShort(end + 10))
            throw new IOException("Split ZIP archives are unsupported");
        if (count == 65535 || size == 0xffffffffL || offset == 0xffffffffL
            || (boundary >= 20 && bytes(boundary - 20, 4).getInt(0) == 0x07064b50)) {
            var locator = bytes(boundary - 20, 20);
            if (locator.getInt(0) != 0x07064b50 || locator.getInt(4) != 0 || locator.getInt(16) != 1)
                throw new IOException("Invalid ZIP64 locator");
            long location = locator.getLong(8);
            var record = bytes(location, 56);
            if (record.getInt(0) != 0x06064b50 || record.getLong(4) < 44
                || location + 12 + record.getLong(4) != boundary - 20 || record.getInt(16) != 0
                || record.getInt(20) != 0 || record.getLong(24) != record.getLong(32))
                throw new IOException("Invalid ZIP64 directory");
            if ((count != 65535 && count != record.getLong(32)) || (size != 0xffffffffL && size != record.getLong(40))
                || (offset != 0xffffffffL && offset != record.getLong(48)))
                throw new IOException("ZIP and ZIP64 end records disagree");
            count = record.getLong(32);
            size = record.getLong(40);
            offset = record.getLong(48);
            boundary = location;
        }
        if (count < 0 || count > limit || size < 0 || size > 32 * 1024 * 1024 || offset < 0 || offset > boundary
            || size != boundary - offset)
            throw new IOException("ZIP directory exceeds limits or has gaps");
        long cursor = offset;
        for (long i = 0; i < count; i++) {
            var header = bytes(cursor, 46);
            if (header.getInt(0) != 0x02014b50)
                throw new IOException("Invalid ZIP central entry");
            int name = Short.toUnsignedInt(header.getShort(28));
            if (name == 0 || name > 4096)
                throw new IOException("ZIP name exceeds limits");
            cursor += 46L + name + Short.toUnsignedInt(header.getShort(30)) + Short.toUnsignedInt(header.getShort(32));
            if (cursor > boundary)
                throw new IOException("Incomplete ZIP directory");
        }
        if (cursor != boundary)
            throw new IOException("ZIP entry count differs from directory");
        return offset;
    }

    /** Requires matching local and central names, flags, methods and declared lengths. / 要求本地头与中央头的名称、标志、方法及声明长度一致。
     * @param entry original central entry / 原中央条目
     * @throws IOException if headers disagree / 头部不一致时 */
    private void local(ZipArchiveEntry entry) throws IOException {
        long offset = entry.getLocalHeaderOffset();
        var header = bytes(offset, 30);
        int names = Short.toUnsignedInt(header.getShort(26));
        int extra = Short.toUnsignedInt(header.getShort(28));
        if (header.getInt(0) != 0x04034b50 || Short.toUnsignedInt(header.getShort(6)) != entry.getRawFlag()
            || Short.toUnsignedInt(header.getShort(8)) != entry.getMethod()
            || entry.getDataOffset() != offset + 30 + names + extra
            || !Arrays.equals(bytes(offset + 30, names).array(), entry.getRawName()))
            throw new IOException("ZIP local header disagrees with central directory");
        boolean descriptor = (entry.getRawFlag() & 8) != 0;
        long crc = Integer.toUnsignedLong(header.getInt(14));
        long compressed = Integer.toUnsignedLong(header.getInt(18));
        long original = Integer.toUnsignedLong(header.getInt(22));
        if ((crc != entry.getCrc() && !(descriptor && crc == 0))
            || (compressed != entry.getCompressedSize() && compressed != 0xffffffffL
            && !(descriptor && compressed == 0))
            || (original != entry.getSize() && original != 0xffffffffL && !(descriptor && original == 0)))
            throw new IOException("ZIP local sizes or CRC disagree");
        if (original == 0xffffffffL || compressed == 0xffffffffL) {
            var fields = bytes(offset + 30 + names, extra);
            boolean found = false;
            while (fields.remaining() >= 4) {
                int tag = Short.toUnsignedInt(fields.getShort());
                int count = Short.toUnsignedInt(fields.getShort());
                if (count > fields.remaining())
                    throw new IOException("Truncated local ZIP extra field");
                int end = fields.position() + count;
                if (tag == 1) {
                    if (found || count < 16)
                        throw new IOException("Invalid local ZIP64 sizes");
                    found = true;
                    long uncompressed = fields.getLong(), packed = fields.getLong();
                    if ((uncompressed != entry.getSize() && !(descriptor && uncompressed == 0))
                        || (packed != entry.getCompressedSize() && !(descriptor && packed == 0)))
                        throw new IOException("Local ZIP64 sizes disagree with directory");
                }
                fields.position(end);
            }
            if (!found || fields.hasRemaining())
                throw new IOException("Missing or truncated local ZIP64 size field");
        }
    }

    /** Decodes the original name before any ZIP-library separator normalization. / 在 ZIP 库分隔符规范化前解码原名。
     * @param entry original entry / 原条目
     * @param portable whether Windows extraction rules apply / 是否应用 Windows 展开规则
     * @return relative path without a directory's terminal slash / 不含目录末尾斜线的相对路径
     * @throws IOException if a path is unsafe or ambiguous / 路径不安全或有歧义时 */
    static String name(ZipArchiveEntry entry, boolean portable) throws IOException {
        var charset = !portable || (entry.getRawFlag() & 2048) != 0
            ? StandardCharsets.UTF_8
            : Charset.forName("IBM437");
        String original = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(entry.getRawName())).toString();
        if (!original.equals(entry.getName()))
            throw new IOException("Ambiguous ZIP name");
        String path = entry.isDirectory() ? original.substring(0, original.length() - 1) : original;
        if (path.isEmpty() || path.length() > 1024)
            throw new IOException("Invalid ZIP path length");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0
                || part.indexOf('\\') >= 0
                || (portable
                && (part.endsWith(".") || part.endsWith(" ") || part.matches(".*[<>:\"|?*\\x00-\\x1f].*")
                || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?"))))
                throw new IOException("Unsafe ZIP path");
        return path;
    }

    /** Reads an exact small range from the original file. / 从原文件读取精确小范围。
     * @param offset relative position / 相对位置
     * @param size exact bytes / 精确字节数
     * @return little-endian buffer / 小端缓冲区
     * @throws IOException if the range is unavailable / 范围不可用时 */
    private ByteBuffer bytes(long offset, int size) throws IOException {
        if (offset < 0 || size < 0 || offset > length || size > length - offset)
            throw new IOException("ZIP range is outside archive");
        var result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (result.hasRemaining())
            if (file.read(result, start + offset + result.position()) < 0)
                throw new EOFException("Truncated ZIP");
        return result.flip();
    }

    /** Closes only this ZIP view, never the caller's original file. / 仅关闭此 ZIP 视图，不关闭调用方原文件。
     * @throws IOException if ZIP cleanup fails / ZIP 收尾失败时 */
    @Override
    public void close() throws IOException {
        zip.close();
    }

    /** Gives the library a bounded independent cursor without copying a nested JAR. / 向库提供有界独立游标，不复制嵌套 JAR。 */
    private final class Slice implements SeekableByteChannel {
        /** Relative read cursor. / 相对读取游标。 */
        private long cursor;

        /** This view's lifetime, independent of its parent. / 此视图独立于父句柄的寿命。 */
        private boolean open = true;

        /** Reads within the exact range. / 在精确范围内读取。
         * @param target destination / 目标
         * @return bytes or EOF / 字节数或结束
         * @throws IOException if closed or unreadable / 已关闭或不可读时 */
        public int read(ByteBuffer target) throws IOException {
            check();
            if (!target.hasRemaining())
                return 0;
            if (cursor >= length)
                return -1;
            int limit = target.limit();
            target.limit(target.position() + (int) Math.min(target.remaining(), length - cursor));
            try {
                int read = file.read(target, start + cursor);
                if (read > 0)
                    cursor += read;
                return read;
            } finally {
                target.limit(limit);
            }
        }

        /** Rejects writes. / 拒绝写入。
         * @param source unused bytes / 未使用字节
         * @return never returns / 不返回 */
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        /** Returns current position. / 返回当前位置。
         * @return relative cursor / 相对游标
         * @throws IOException if closed / 已关闭时 */
        public long position() throws IOException {
            check();
            return cursor;
        }

        /** Seeks within the archive. / 在归档内定位。
         * @param value new cursor / 新游标
         * @return this view / 此视图
         * @throws IOException if closed or out of range / 已关闭或越界时 */
        public SeekableByteChannel position(long value) throws IOException {
            check();
            if (value < 0 || value > length)
                throw new IOException("Invalid ZIP seek");
            cursor = value;
            return this;
        }

        /** Returns the bounded size. / 返回有界大小。
         * @return range length / 范围长度
         * @throws IOException if closed / 已关闭时 */
        public long size() throws IOException {
            check();
            return length;
        }

        /** Rejects truncation. / 拒绝截断。
         * @param value unused length / 未使用长度
         * @return never returns / 不返回 */
        public SeekableByteChannel truncate(long value) {
            throw new NonWritableChannelException();
        }

        /** Reports this view's state. / 报告此视图状态。
         * @return whether open / 是否打开 */
        public boolean isOpen() {
            return open;
        }

        /** Releases this view only. / 仅释放此视图。 */
        public void close() {
            open = false;
        }

        /** Checks both original and view handles. / 检查原句柄及视图句柄。
         * @throws IOException if closed / 已关闭时 */
        private void check() throws IOException {
            if (!open || !file.isOpen())
                throw new ClosedChannelException();
        }
    }
}
