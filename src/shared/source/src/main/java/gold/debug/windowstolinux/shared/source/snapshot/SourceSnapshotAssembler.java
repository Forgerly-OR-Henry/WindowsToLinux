package gold.debug.windowstolinux.shared.source.snapshot;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import gold.debug.windowstolinux.shared.source.contract.validation.SourceBoundaryValidator;
import gold.debug.windowstolinux.shared.source.manifest.SourceEntry;
import gold.debug.windowstolinux.shared.source.manifest.SourceManifest;

/**
 * Writes the canonical gzip-compressed ustar representation of a source manifest.
 *
 *  <p>写入源码清单的规范 gzip 压缩 ustar 表示。
 */
public final class SourceSnapshotAssembler {
    /**
     * TAR BLOCK BYTES.
     * <p>TAR块字节。
     */
    private static final int TAR_BLOCK_BYTES = 512;

    /**
     * TAR NAME BYTES.
     * <p>TAR名称字节。
     */
    private static final int TAR_NAME_BYTES = 100;

    /**
     * TAR PREFIX OFFSET.
     * <p>TAR前缀偏移量。
     */
    private static final int TAR_PREFIX_OFFSET = 345;

    /**
     * TAR PREFIX BYTES.
     * <p>TAR前缀字节。
     */
    private static final int TAR_PREFIX_BYTES = 155;

    /**
     * TAR SIZE OFFSET.
     * <p>TAR大小偏移量。
     */
    private static final int TAR_SIZE_OFFSET = 124;

    /**
     * TAR SIZE BYTES.
     * <p>TAR大小字节。
     */
    private static final int TAR_SIZE_BYTES = 12;

    /**
     * TAR CHECKSUM OFFSET.
     * <p>TAR校验和偏移量。
     */
    private static final int TAR_CHECKSUM_OFFSET = 148;

    /**
     * TAR CHECKSUM BYTES.
     * <p>TAR校验和字节。
     */
    private static final int TAR_CHECKSUM_BYTES = 8;

    /**
     * Validator.
     * <p>校验器。
     */
    private final SourceBoundaryValidator validator;

    /**
     * Validates and binds the inputs required by source snapshot assembler.
     * <p>校验并绑定源码快照Assembler所需输入。
     *
     * @param validator validator / 校验器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SourceSnapshotAssembler(SourceBoundaryValidator validator) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
    }

    /**
     * Stores data through {@code write}.
     *
     *  <p>通过 {@code write} 保存数据。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public void write(Path root, SourceManifest manifest, Path archive) throws IOException {
        try (OutputStream output = Files.newOutputStream(archive);
                GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            for (SourceEntry entry : manifest.entries()) {
                validator.verifyUnchangedRegularFile(root, entry);
                gzip.write(tarHeader(entry.relativePath(), entry.byteCount()));
                writeFile(gzip, entry);
            }
            gzip.write(new byte[TAR_BLOCK_BYTES * 2]);
        }
    }

    /**
     * Writes file.
     * <p>写入文件。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param entry entry / 条目
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeFile(OutputStream output, SourceEntry entry) throws IOException {
        long remaining = entry.byteCount();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(entry.path()))) {
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new IOException("source entry shrank while creating archive: " + entry.relativePath());
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
            if (input.read() != -1) {
                throw new IOException("source entry grew while creating archive: " + entry.relativePath());
            }
        }
        int padding = (int) ((TAR_BLOCK_BYTES - (entry.byteCount() % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES);
        if (padding > 0) {
            output.write(new byte[padding]);
        }
    }

    /**
     * Builds a deterministic regular-file TAR header for the relative path and declared byte count.
     * <p>根据相对路径及声明字节数构建确定性的常规文件 TAR 头部。
     *
     * @param relativePath relative path / 相对路径
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @return a deterministic regular-file TAR header for the relative path and declared byte count / 根据相对路径及声明字节数构建确定性的常规文件 TAR 头部
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static byte[] tarHeader(String relativePath, long byteCount) throws IOException {
        TarPath path = splitTarPath(relativePath);
        byte[] header = new byte[TAR_BLOCK_BYTES];
        writeBytes(header, 0, TAR_NAME_BYTES, path.name());
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, TAR_SIZE_OFFSET, TAR_SIZE_BYTES, byteCount);
        writeOctal(header, 136, 12, 0);
        header[156] = '0';
        writeBytes(header, 257, 6, "ustar\0");
        writeBytes(header, 263, 2, "00");
        writeBytes(header, TAR_PREFIX_OFFSET, TAR_PREFIX_BYTES, path.prefix());
        writeChecksum(header);
        return header;
    }

    /**
     * Builds tar path from the supplied split tar path inputs.
     * <p>根据所提供splitTar路径输入构建Tar路径。
     *
     * @param relativePath relative path / 相对路径
     * @return tar path from the supplied split tar path inputs / 根据所提供splitTar路径输入构建Tar路径
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static TarPath splitTarPath(String relativePath) throws IOException {
        if (relativePath.getBytes(StandardCharsets.UTF_8).length <= TAR_NAME_BYTES) {
            return new TarPath(relativePath, "");
        }
        for (int slash = relativePath.lastIndexOf('/'); slash > 0; slash = relativePath.lastIndexOf('/', slash - 1)) {
            String prefix = relativePath.substring(0, slash);
            String name = relativePath.substring(slash + 1);
            if (prefix.getBytes(StandardCharsets.UTF_8).length <= TAR_PREFIX_BYTES
                    && name.getBytes(StandardCharsets.UTF_8).length <= TAR_NAME_BYTES) {
                return new TarPath(name, prefix);
            }
        }
        throw new IOException("source entry path is too long for a reproducible ustar archive: " + relativePath);
    }

    /**
     * Writes content buffer processed by the current codec or stream.
     * <p>写入当前编解码器或流处理的内容缓冲区。
     *
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param offset offset / 偏移量
     * @param length length / 长度
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeBytes(byte[] destination, int offset, int length, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > length) {
            throw new IOException("tar header value is too long");
        }
        System.arraycopy(bytes, 0, destination, offset, bytes.length);
    }

    /**
     * Writes octal.
     * <p>写入八进制。
     *
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param offset offset / 偏移量
     * @param length length / 长度
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeOctal(byte[] destination, int offset, int length, long value) throws IOException {
        if (value < 0) {
            throw new IOException("tar numeric header values must not be negative");
        }
        String octal = Long.toOctalString(value);
        if (octal.length() > length - 1) {
            throw new IOException("tar numeric header value is too large");
        }
        Arrays.fill(destination, offset, offset + length, (byte) '0');
        int digitsStart = offset + length - 1 - octal.length();
        for (int index = 0; index < octal.length(); index++) {
            destination[digitsStart + index] = (byte) octal.charAt(index);
        }
        destination[offset + length - 1] = 0;
    }

    /**
     * Computes and writes the TAR header checksum using space-filled checksum bytes and the required octal encoding.
     * <p>使用空格填充的校验和字节及规定八进制编码，计算并写入 TAR 头部校验和。
     *
     * @param header header / 头部
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void writeChecksum(byte[] header) throws IOException {
        Arrays.fill(header, TAR_CHECKSUM_OFFSET, TAR_CHECKSUM_OFFSET + TAR_CHECKSUM_BYTES, (byte) ' ');
        long checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        String octal = Long.toOctalString(checksum);
        if (octal.length() > 6) {
            throw new IOException("tar header checksum is too large");
        }
        Arrays.fill(header, TAR_CHECKSUM_OFFSET, TAR_CHECKSUM_OFFSET + 6, (byte) '0');
        int digitsStart = TAR_CHECKSUM_OFFSET + 6 - octal.length();
        for (int index = 0; index < octal.length(); index++) {
            header[digitsStart + index] = (byte) octal.charAt(index);
        }
        header[TAR_CHECKSUM_OFFSET + 6] = 0;
        header[TAR_CHECKSUM_OFFSET + 7] = (byte) ' ';
    }

    /**
     * Splits a canonical TAR member path into the fields supported by the archive header.
     * <p>将规范 TAR 成员路径拆分为归档头支持的字段。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param prefix prefix / 前缀
     */
    private record TarPath(String name, String prefix) {
    }
}
