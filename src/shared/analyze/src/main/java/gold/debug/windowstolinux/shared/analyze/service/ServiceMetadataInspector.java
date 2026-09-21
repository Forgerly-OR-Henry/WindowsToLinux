package gold.debug.windowstolinux.shared.analyze.service;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared bounded metadata operations without language-specific rules. / 不包含语言特定规则的公共有界元数据操作。
 */
public final class ServiceMetadataInspector {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ServiceMetadataInspector() {
    }

    /**
     * Returns one bounded first capture or {@code null}. / 返回一个有界的首个捕获值或 {@code null}。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @param pattern pattern / 匹配模式
     * @return one bounded first capture or {@code null} / 一个有界的首个捕获值或 {@code null}
     */
    public static String match(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value != null && value.length() <= 255 ? value : null;
    }

    /**
     * Returns the missing paths from a fixed relative-path list. / 返回固定相对路径列表中的缺失路径。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @return the missing paths from a fixed relative-path list / 固定相对路径列表中的缺失路径
     */
    public static List<String> missing(Path root, String... files) {
        List<String> missing = new ArrayList<>();
        for (String file : files) {
            if (!present(root, file)) {
                missing.add(file);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Returns whether one bounded regular metadata file exists. / 返回一个有界常规元数据文件是否存在。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param relative relative / 相对
     * @return true when returns whether one bounded regular metadata file exists, false otherwise / 返回一个有界常规元数据文件是否存在时为 true，否则为 false
     */
    public static boolean present(Path root, String relative) {
        return BoundedMetadataInspector.regular(root.resolve(relative));
    }

    /**
     * Reads bounded metadata when present, otherwise returns an empty value. / 存在时读取有界元数据，否则返回空值。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return bounded metadata when present, otherwise returns an empty value / 存在时读取有界元数据，否则返回空值
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static String readIfPresent(Path path) throws IOException {
        return BoundedMetadataInspector.regular(path) ? BoundedMetadataInspector.read(path) : "";
    }

    /**
     * Reads a declared runtime entrypoint without executing project code. / 读取已声明的运行入口而不执行项目代码。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param fallback fallback / 回退
     * @return a declared runtime entrypoint without executing project code / 已声明的运行入口而不执行项目代码
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static String applicationEntrypoint(Path root, String fallback) throws IOException {
        var values = new java.util.Properties();
        values.load(new java.io.StringReader(readIfPresent(root.resolve("windowstolinux-application.properties"))));
        String entry = values.getProperty("runtime.secondary", values.getProperty("command.entrypoint", fallback));
        return gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand.relative(entry, false);
    }

    /**
     * Returns an immutable list with one additional missing item. / 返回增加一个缺失项后的不可变列表。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return an immutable list with one additional missing item / 增加一个缺失项后的不可变列表
     */
    public static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }
}
