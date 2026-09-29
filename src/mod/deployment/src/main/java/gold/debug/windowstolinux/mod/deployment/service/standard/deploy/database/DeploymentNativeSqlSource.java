package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Validates every frozen native SQL source before the first remote database effect. / 首个远端数据库效果前校验每份冻结的原生 SQL 来源。 */
public final class DeploymentNativeSqlSource {
    /** Old combined SQL input limit. / 旧组合 SQL 输入上限。 */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    /** Exact-size temporary SQL buffer whose internal array is always cleared. / 内部数组总被清零的精确大小临时 SQL 缓冲。 */
    private static final class WipingBuffer extends ByteArrayOutputStream {
        /** Reserves full validated SQL size. / 预留完整已校验 SQL 大小。
         * @param capacity exact size / 精确大小 */
        private WipingBuffer(int capacity) {
            super(capacity);
        }

        /** Clears the owned internal array. / 清零所属内部数组。 */
        private void wipe() {
            Arrays.fill(buf, (byte) 0);
        }
    }

    /** Prevents construction of source reader. / 禁止构造来源读取器。 */
    private DeploymentNativeSqlSource() {
    }

    /** Non-secret immutable local identity of a checked SQL source set. / 已检查 SQL 来源集合的不含秘密本机不可变身份。
     * @param root original frozen component root / 原冻结组件根
     * @param files reviewed relative SQL files / 经审阅相对 SQL 文件
     * @param sha256 exact combined bytes digest / 精确组合字节摘要 */
    public record Plan(Path root, List<String> files, String sha256) {
        /** Copies source identity without keeping SQL text. / 复制来源身份而不持有 SQL 文本。
         * @param root original frozen component root / 原冻结组件根
         * @param files reviewed relative paths / 经审阅相对路径
         * @param sha256 combined digest / 组合摘要 */
        public Plan {
            root = root.toAbsolutePath().normalize();
            files = List.copyOf(files);
            if (files.size() > 32 || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid native SQL source plan");
        }

        /** Rereads only the same frozen bytes, leaving clearance to the caller. / 仅读取相同冻结字节，清零由调用方负责。
         * @return newly owned combined UTF-8 SQL bytes / 新所属组合 UTF-8 SQL 字节
         * @throws IOException if the original files changed or are unsafe / 原文件变化或不安全时 */
        public byte[] load() throws IOException {
            byte[] bytes = combined(root, files);
            if (!sha256.equals(digest(bytes))) {
                Arrays.fill(bytes, (byte) 0);
                throw new IOException("Native SQL source changed after local validation");
            }
            return bytes;
        }
    }

    /** Checks original paths, total size and exact contents before any native effect. / 原生效果前检查原路径、总大小和精确内容。
     * @param root original frozen component root / 原冻结组件根
     * @param files reviewed relative SQL files / 经审阅相对 SQL 文件
     * @return source plan without retained SQL text / 不持有 SQL 文本的来源计划
     * @throws IOException if source verification fails / 来源核验失败时 */
    public static Plan plan(Path root, List<String> files) throws IOException {
        byte[] bytes = combined(root.toAbsolutePath().normalize(), files);
        try {
            if (!files.isEmpty())
                verifyUtf8(bytes);
            return new Plan(root, files, digest(bytes));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Reads a bounded sequence without a StringBuilder copy of SQL. / 不通过 StringBuilder 复制 SQL 地读取有界序列。
     * @param root original component root / 原组件根
     * @param files reviewed paths / 经审阅路径
     * @return caller-owned combined bytes / 调用方持有的组合字节
     * @throws IOException if any source fact differs / 任一来源事实不符时 */
    private static byte[] combined(Path root, List<String> files) throws IOException {
        /** One verified SQL file and measured bound. / 一项已核实 SQL 文件及测得边界。
         * @param path exact frozen file / 精确冻结文件
         * @param size measured bytes / 测得字节数 */
        record Checked(Path path, int size) {
        }
        var checked = new java.util.ArrayList<Checked>();
        Path realRoot = root.toRealPath();
        long size = 0;
        for (String name : files) {
            if (name == null || !name.matches("[a-zA-Z0-9_./-]{1,255}") || name.startsWith("/")
                    || Arrays.asList(name.split("/")).contains("..") || !name.endsWith(".sql"))
                throw new IOException("Invalid native SQL source path");
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root) || !file.toRealPath().startsWith(realRoot) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Native SQL source exceeds frozen file boundary");
            long measuredSize = Files.size(file);
            if (measuredSize > MAX_BYTES || (size += measuredSize + 1) > MAX_BYTES)
                throw new IOException("Native SQL source exceeds frozen file boundary");
            checked.add(new Checked(file, Math.toIntExact(measuredSize)));
        }
        var output = new WipingBuffer((int) size);
        try {
            for (Checked file : checked) {
                byte[] part;
                try (var input = Files.newInputStream(file.path())) {
                    part = input.readNBytes(file.size() + 1);
                }
                try {
                    if (part.length != file.size() || output.size() + part.length + 1 > size || containsNul(part))
                        throw new IOException("Native SQL source changed or contains NUL");
                    output.writeBytes(part);
                    output.write('\n');
                } finally {
                    Arrays.fill(part, (byte) 0);
                }
            }
            if (output.size() != size)
                throw new IOException("Native SQL source size changed");
            return output.toByteArray();
        } finally {
            output.wipe();
        }
    }

    /** Checks the helper's non-text byte prohibition. / 检查 helper 的非文本字节禁止条件。
     * @param bytes source bytes / 来源字节
     * @return whether NUL occurs / 是否存在 NUL */
    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes)
            if (value == 0)
                return true;
        return false;
    }

    /** Validates helper UTF-8 decoding locally without retaining SQL in an immutable String. / 本地校验 helper UTF-8 解码，不在不可变字符串中保留 SQL。
     * @param bytes combined source bytes / 组合来源字节
     * @throws IOException if encoding is invalid / 编码无效时 */
    private static void verifyUtf8(byte[] bytes) throws IOException {
        var characters = CharBuffer.allocate(bytes.length);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var decoded = decoder.decode(ByteBuffer.wrap(bytes), characters, true);
            if (decoded.isError() || decoded.isOverflow())
                throw new IOException("Native SQL is not bounded UTF-8");
            var flushed = decoder.flush(characters);
            if (flushed.isError() || flushed.isOverflow())
                throw new IOException("Native SQL is not bounded UTF-8");
        } finally {
            Arrays.fill(characters.array(), '\0');
        }
    }

    /** Computes exact combined bytes identity. / 计算精确组合字节身份。
     * @param bytes original bytes / 原字节
     * @return lowercase SHA-256 / 小写 SHA-256 */
    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }
}
