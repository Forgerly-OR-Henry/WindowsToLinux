package gold.debug.windowstolinux.shared.analyze.service;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared bounded metadata operations without language-specific rules. / 不包含语言特定规则的公共有界元数据操作。 */
public final class ServiceMetadataInspector {
    private ServiceMetadataInspector() {
    }

    /** Returns one bounded first capture or {@code null}. / 返回一个有界的首个捕获值或 {@code null}。 */
    public static String match(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value != null && value.length() <= 255 ? value : null;
    }

    /** Returns the missing paths from a fixed relative-path list. / 返回固定相对路径列表中的缺失路径。 */
    public static List<String> missing(Path root, String... files) {
        List<String> missing = new ArrayList<>();
        for (String file : files) {
            if (!present(root, file)) {
                missing.add(file);
            }
        }
        return List.copyOf(missing);
    }

    /** Returns whether one bounded regular metadata file exists. / 返回一个有界常规元数据文件是否存在。 */
    public static boolean present(Path root, String relative) {
        return BoundedMetadataInspector.regular(root.resolve(relative));
    }

    /** Reads bounded metadata when present, otherwise returns an empty value. / 存在时读取有界元数据，否则返回空值。 */
    public static String readIfPresent(Path path) throws IOException {
        return BoundedMetadataInspector.regular(path) ? BoundedMetadataInspector.read(path) : "";
    }

    /** Returns an immutable list with one additional missing item. / 返回增加一个缺失项后的不可变列表。 */
    public static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }
}
