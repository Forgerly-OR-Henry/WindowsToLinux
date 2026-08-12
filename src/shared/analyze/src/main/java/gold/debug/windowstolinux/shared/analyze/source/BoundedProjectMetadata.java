package gold.debug.windowstolinux.shared.analyze.source;

import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Shared bounded operations for fixed project metadata names; it never evaluates project content.
 *
 * <p>针对固定项目元数据名称的共享有界操作；绝不求值项目内容。
 */
public final class BoundedProjectMetadata {
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;

    private BoundedProjectMetadata() {
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

    /** Checks one fixed entry in a bounded ZIP metadata file without loading its classes. / 在不加载类的前提下检查有界 ZIP 元数据文件中的固定条目。 */
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

    /** Derives a managed identifier from fixed metadata or the selected root name. / 从固定元数据或选定根目录名称推导受管标识。 */
    public static String applicationId(Path root, String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? normalizeApplicationId(matcher.group(1)) : rootApplicationId(root);
    }

    /** Derives a managed identifier from the selected root name. / 从选定根目录名称推导受管标识。 */
    public static String rootApplicationId(Path root) {
        Path name = root.getFileName();
        return normalizeApplicationId(name == null ? "application" : name.toString());
    }

    /** Creates high-confidence metadata evidence. / 创建高置信度元数据证据。 */
    public static AnalysisEvidence evidence(String subject, String source, String conclusion) {
        return new AnalysisEvidence(LocalizedMessage.of(subject), source, LocalizedMessage.of(conclusion),
                EvidenceConfidence.HIGH);
    }

    /** Creates a required-input message. / 创建所需输入消息。 */
    public static LocalizedMessage required(String key) {
        return LocalizedMessage.of(key);
    }

    private static String normalizeApplicationId(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            return "application";
        }
        return normalized.length() > 63 ? normalized.substring(0, 63).replaceAll("-+$", "") : normalized;
    }
}
