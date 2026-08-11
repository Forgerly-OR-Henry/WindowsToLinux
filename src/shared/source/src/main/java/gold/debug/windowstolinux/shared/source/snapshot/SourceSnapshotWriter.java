package gold.debug.windowstolinux.shared.source.snapshot;

import gold.debug.windowstolinux.shared.source.manifest.SourceEntry;
import gold.debug.windowstolinux.shared.source.manifest.SourceManifest;
import gold.debug.windowstolinux.shared.source.validation.SourceBoundaryValidator;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

/**
 * Writes the canonical gzip-compressed ustar representation of a source manifest.
 *
 * <p>写入源码清单的规范 gzip 压缩 ustar 表示。
 */
public final class SourceSnapshotWriter {
    private static final int TAR_BLOCK_BYTES = 512;
    private static final int TAR_NAME_BYTES = 100;
    private static final int TAR_PREFIX_OFFSET = 345;
    private static final int TAR_PREFIX_BYTES = 155;
    private static final int TAR_SIZE_OFFSET = 124;
    private static final int TAR_SIZE_BYTES = 12;
    private static final int TAR_CHECKSUM_OFFSET = 148;
    private static final int TAR_CHECKSUM_BYTES = 8;

    private final SourceBoundaryValidator validator;

    /**
     * Creates a {@code SourceSnapshotWriter} instance.
     *
     * <p>创建 {@code SourceSnapshotWriter} 实例。
     *
     * @param validator the {@code validator} value / {@code validator} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourceSnapshotWriter(SourceBoundaryValidator validator) {
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
    }

    /**
     * Stores data through {@code write}.
     *
     * <p>通过 {@code write} 保存数据。
     *
     * @param root the {@code root} value / {@code root} 值
     * @param manifest the {@code manifest} value / {@code manifest} 值
     * @param archive the {@code archive} value / {@code archive} 值
     * @throws IOException if the operation cannot be completed / 无法完成操作时
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

    private static void writeBytes(byte[] destination, int offset, int length, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > length) {
            throw new IOException("tar header value is too long");
        }
        System.arraycopy(bytes, 0, destination, offset, bytes.length);
    }

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

    private record TarPath(String name, String prefix) {
    }
}
