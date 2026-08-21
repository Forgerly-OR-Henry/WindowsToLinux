package gold.debug.windowstolinux.shared.source.archive;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeSourceArchivePreparerTest {
    @TempDir
    Path temporaryDirectory;

    private final SafeSourceArchivePreparer archiver = new SafeSourceArchivePreparer();

    @Test
    void archivesOnlyAllowedSourceFilesAsReproducibleRegularTarEntries() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("pom.xml"), "<project/>");
        Files.createDirectories(source.resolve("src/main"));
        Files.writeString(source.resolve("src/main/App.java"), "class App {}");
        Files.createDirectories(source.resolve("target"));
        Files.writeString(source.resolve("target/generated.txt"), "excluded");
        Files.writeString(source.resolve(".env"), "excluded");

        SourceArchive archive = archiver.archive(source, temporaryDirectory.resolve("out/source.tar.gz"));
        SourceArchive repeat = archiver.archive(source, temporaryDirectory.resolve("out/source-repeat.tar.gz"));

        assertEquals(2, archive.fileCount());
        assertEquals(Files.size(source.resolve("pom.xml")) + Files.size(source.resolve("src/main/App.java")),
                archive.uncompressedByteCount());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive.archivePath()))),
                archive.contentSha256());
        assertArrayEquals(Files.readAllBytes(archive.archivePath()), Files.readAllBytes(repeat.archivePath()));
        assertTrue(archive.archivePath().getFileName().toString().endsWith(".tar.gz"));
        assertTrue(archive.excludedEntries().contains("target/"));
        assertTrue(archive.excludedEntries().contains(".env"));

        List<TarEntry> entries = readTarEntries(archive.archivePath());
        assertEquals(List.of("pom.xml", "src/main/App.java"), entries.stream().map(TarEntry::path).toList());
        assertTrue(entries.stream().allMatch(entry -> entry.type() == '0'));
        assertFalse(entries.stream().anyMatch(entry -> entry.path().contains("target")));
    }

    @Test
    void rejectsArchiveDestinationInsideSourceBoundaryOrOutsideTheTarGzipContract() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("pom.xml"), "<project/>");

        assertEquals(SourceArchiveFailureType.BOUNDARY_ESCAPE, assertThrows(SourceArchiveException.class,
                () -> archiver.archive(source, source.resolve("source.tar.gz"))).failure().definition());
        assertEquals(SourceArchiveFailureType.DESTINATION_INVALID, assertThrows(SourceArchiveException.class,
                () -> archiver.archive(source, temporaryDirectory.resolve("source.zip"))).failure().definition());
        assertThrows(IllegalArgumentException.class, () -> new SourceArchiveDescriptor(
                temporaryDirectory.resolve("source.zip"), "a".repeat(64), 1, 1
        ));
    }

    @Test
    void rejectsSymbolicLinksInsteadOfFollowingThemIntoTheTarArchive() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("pom.xml"), "<project/>");
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "outside");
        Path link = source.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("symbolic links are unavailable in this test environment");
        }

        assertEquals(SourceArchiveFailureType.SYMBOLIC_LINK_REJECTED, assertThrows(SourceArchiveException.class,
                () -> archiver.archive(source, temporaryDirectory.resolve("out/source.tar.gz")))
                .failure().definition());

        Path linkedOutputParent = temporaryDirectory.resolve("linked-output");
        Files.createSymbolicLink(linkedOutputParent, source);
        assertEquals(SourceArchiveFailureType.DESTINATION_INVALID, assertThrows(SourceArchiveException.class,
                () -> archiver.archive(source, linkedOutputParent.resolve("source.tar.gz")))
                .failure().definition());
    }

    @Test
    void preservesInterruptionAndClassifiesCleanupFailure() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("interrupted-source"));
        Files.writeString(source.resolve("pom.xml"), "<project/>");
        Thread.currentThread().interrupt();
        try {
            SourceArchiveException interrupted = assertThrows(SourceArchiveException.class,
                    () -> archiver.archive(source, temporaryDirectory.resolve("out/interrupted.tar.gz")));
            assertEquals(SourceArchiveFailureType.INTERRUPTED, interrupted.failure().definition());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        Path nonEmptyDirectory = Files.createDirectories(temporaryDirectory.resolve("cleanup-failure"));
        Files.writeString(nonEmptyDirectory.resolve("owned.txt"), "fixture");
        SourceArchiveException original = SourceArchiveException.create(SourceArchiveFailureType.WRITE_FAILED,
                "fixture archive write failure", new IOException("fixture"));
        SourceArchiveException cleanup = SafeSourceArchivePreparer.cleanupThen(nonEmptyDirectory, original);
        assertEquals(SourceArchiveFailureType.CLEANUP_FAILED, cleanup.failure().definition());
    }

    private static List<TarEntry> readTarEntries(Path archive) throws IOException {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            List<TarEntry> entries = new ArrayList<>();
            while (true) {
                byte[] header = input.readNBytes(512);
                if (header.length != 512) {
                    throw new IOException("truncated tar header");
                }
                if (isZeroBlock(header)) {
                    byte[] secondEndBlock = input.readNBytes(512);
                    if (secondEndBlock.length != 512 || !isZeroBlock(secondEndBlock) || input.read() != -1) {
                        throw new IOException("tar archive must end with exactly two zero blocks");
                    }
                    return entries;
                }
                verifyChecksum(header);
                String name = readString(header, 0, 100);
                String prefix = readString(header, 345, 155);
                String path = prefix.isEmpty() ? name : prefix + "/" + name;
                long size = readOctal(header, 124, 12);
                entries.add(new TarEntry(path, (char) header[156], size));
                input.skipNBytes(size);
                input.skipNBytes((512 - (size % 512)) % 512);
            }
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static void verifyChecksum(byte[] header) throws IOException {
        long expected = readOctal(header, 148, 8);
        byte[] copy = header.clone();
        Arrays.fill(copy, 148, 156, (byte) ' ');
        long actual = 0;
        for (byte value : copy) {
            actual += Byte.toUnsignedInt(value);
        }
        if (actual != expected) {
            throw new IOException("tar checksum mismatch");
        }
        if (!"ustar\0".equals(new String(header, 257, 6, StandardCharsets.UTF_8))) {
            throw new IOException("tar header is not ustar");
        }
    }

    private static String readString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long readOctal(byte[] bytes, int offset, int length) throws IOException {
        long value = 0;
        boolean seenDigit = false;
        for (int index = offset; index < offset + length; index++) {
            byte current = bytes[index];
            if (current == 0 || current == ' ') {
                continue;
            }
            if (current < '0' || current > '7') {
                throw new IOException("tar numeric field is not octal");
            }
            seenDigit = true;
            value = Math.addExact(Math.multiplyExact(value, 8), current - '0');
        }
        return seenDigit ? value : 0;
    }

    private record TarEntry(String path, char type, long byteCount) {
    }
}
