package gold.debug.windowstolinux.shared.analyze.source.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads only fixed project metadata within static-inspection bounds.
 *
 * <p>只在静态检查边界内读取固定项目元数据。
 */
public final class BoundedMetadataReader {
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;

    private BoundedMetadataReader() {
    }

    /** Checks a regular non-link metadata file. / 检查普通非链接元数据文件。 */
    public static boolean regular(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** Reads one fixed metadata file under the common bound. / 在公共限制内读取一个固定元数据文件。 */
    public static String read(Path path) throws IOException {
        if (Files.size(path) > MAX_METADATA_BYTES) {
            throw new IOException("project metadata exceeds the static inspection bound");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Checks one fixed entry in a bounded ZIP metadata file. / 检查有界 ZIP 元数据文件中的固定条目。 */
    public static boolean containsZipEntry(Path path, String entryName) throws IOException {
        Objects.requireNonNull(entryName, "entryName");
        if (!regular(path)) {
            return false;
        }
        long size = Files.size(path);
        if (size < 1 || size > MAX_METADATA_BYTES) {
            return false;
        }
        try (ZipFile archive = new ZipFile(path.toFile())) {
            if (archive.size() > 10_000) {
                return false;
            }
            var entry = archive.getEntry(entryName);
            return entry != null && !entry.isDirectory();
        } catch (ZipException invalidArchive) {
            return false;
        }
    }

    /** Returns present fixed names in declaration order. / 按声明顺序返回存在的固定名称。 */
    public static List<String> existingNames(Path root, String... names) {
        List<String> present = new ArrayList<>();
        for (String name : names) {
            if (regular(root.resolve(name))) {
                present.add(name);
            }
        }
        return List.copyOf(present);
    }
}
