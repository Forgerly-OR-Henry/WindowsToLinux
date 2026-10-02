package gold.debug.windowstolinux.app.windows.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Validates bounded Windows recycle metadata rather than inferring recovery from absence. / 校验有界 Windows 回收元数据，不根据缺失推断可恢复性。 */
final class WindowsRecycleInformation {
    /** Maximum metadata size covering the Windows UTF-16 path limit. / 覆盖 Windows UTF-16 路径上限的最大元数据大小。 */
    private static final int LIMIT = 28 + 32768 * 2;

    /** Prevents construction of the metadata validator. / 禁止构造元数据校验器。 */
    private WindowsRecycleInformation() {
    }

    /** Reads unchanged no-follow metadata and proves its original path. / 读取身份未变的不跟随元数据并证明原路径。
     * @param information exact paired metadata / 精确配对元数据
     * @param identity captured native identity / 捕获的原生身份
     * @param original exact original path / 精确原路径
     * @throws IOException if identity, binary format or path validation fails / 身份、二进制格式或路径校验失败时
     */
    static void validate(Path information, String identity, Path original) throws IOException {
        if (!Files.isRegularFile(information, LinkOption.NOFOLLOW_LINKS)
            || !identity.equals(WindowsFileIdentity.read(information)))
            throw new IOException("Native recycle metadata identity changed");
        byte[] bytes;
        try (var input = Files.newInputStream(information, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(LIMIT + 1);
        }
        if (bytes.length > LIMIT || !identity.equals(WindowsFileIdentity.read(information)))
            throw new IOException("Native recycle metadata cannot be bounded or identified");
        if (!original.equals(original(bytes))) throw new IOException("Native recycle metadata original path differs");
    }

    /** Parses the supported binary versions with strict UTF-16 and termination checks. / 以严格 UTF-16 及终止检查解析支持的二进制版本。
     * @param bytes bounded metadata / 有界元数据
     * @return exact absolute original path / 精确绝对原路径
     * @throws IOException if a version, size, timestamp or path is malformed / 版本、大小、时间戳或路径畸形时
     */
    static Path original(byte[] bytes) throws IOException {
        if (bytes.length < 28) throw new IOException("Truncated native recycle metadata");
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long version = data.getLong();
        if (data.getLong() < 0 || data.getLong() <= 0) throw new IOException("Invalid native recycle metadata fields");
        int offset;
        int characters;
        if (version == 1 && bytes.length == 544) {
            offset = 24;
            characters = 260;
        } else if (version == 2) {
            characters = data.getInt();
            offset = 28;
            if (characters < 2 || characters > 32768 || bytes.length != offset + characters * 2)
                throw new IOException("Invalid native recycle path length");
        } else {
            throw new IOException("Unsupported native recycle metadata version");
        }
        int end = offset;
        while (end < bytes.length && (bytes[end] != 0 || bytes[end + 1] != 0)) end += 2;
        if (end == offset || end == bytes.length || (version == 2 && end != bytes.length - 2))
            throw new IOException("Invalid native recycle path termination");
        for (int index = end; index < bytes.length; index++)
            if (bytes[index] != 0) throw new IOException("Invalid native recycle path padding");
        String text = StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, end - offset)).toString();
        try {
            Path original = Path.of(text);
            if (!original.isAbsolute()) throw new IOException("Native recycle original path is not absolute");
            return original.normalize();
        } catch (java.nio.file.InvalidPathException malformed) {
            throw new IOException("Invalid native recycle original path", malformed);
        }
    }
}
