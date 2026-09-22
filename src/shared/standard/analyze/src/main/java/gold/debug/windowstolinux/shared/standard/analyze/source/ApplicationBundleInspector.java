package gold.debug.windowstolinux.shared.standard.analyze.source;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand;

/**
 * Reads build ownership before component discovery without executing declarations.
 * <p>在组件发现前读取构建归属，不执行声明。
 */
public final class ApplicationBundleInspector {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ApplicationBundleInspector() {
    }

    /**
     * Loads the fixed application declaration file while rejecting duplicate property keys.
     * <p>加载固定应用声明文件，并拒绝重复属性键。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return the fixed application declaration file while rejecting duplicate property keys / 固定应用声明文件，并拒绝重复属性键
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static Properties declaration(Path root) throws IOException {
        Path file = root.resolve("windowstolinux-application.properties");
        Properties properties = new Properties() {
            /**
             * Adds a declaration property only when its key has not already been declared.
             * <p>仅在属性键尚未声明时添加声明属性。
             *
             * @param key lookup key within the current contract / 当前契约内的查找键
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @return constructed or resolved object / 构造或解析得到的对象
             * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
             */
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key))
                    throw new IllegalArgumentException("duplicate application declaration");
                return super.put(key, value);
            }
        };
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return properties;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || Files.size(file) > 65536)
            throw new IOException("invalid application declaration");
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        if (!properties.getProperty("version", "1").equals("1"))
            throw new IOException("unsupported application declaration version");
        return properties;
    }

    /**
     * Resolves the declared build directory and requires its real path to remain within the source root.
     * <p>解析声明的构建目录，并要求其真实路径仍位于源码根目录内。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param properties properties / 属性集合
     * @return the declared build directory and requires its real path to remain within the source root / 声明的构建目录，并要求其真实路径仍位于源码根目录内
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static Path mainRoot(Path root, Properties properties) throws IOException {
        String directory = ApplicationCommand.relative(properties.getProperty("buildDirectory", ""), true);
        Path target = root.resolve(directory).normalize();
        if (!target.startsWith(root) || !target.toRealPath().equals(root.toRealPath().resolve(directory).normalize())
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("application build directory escapes the source bundle");
        return target;
    }

    /**
     * Maps the supplied engine or protocol discriminator to the supported type contract.
     * <p>将提供的引擎或协议判别码映射为受支持的类型契约。
     *
     * @param properties properties / 属性集合
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    public static Optional<DeploymentProjectType> type(Properties properties) {
        return Optional.ofNullable(properties.getProperty("projectType")).map(DeploymentProjectType::valueOf);
    }

    /**
     * Checks companions syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查配套单元集合语法及边界。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param properties properties / 属性集合
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static List<Path> companions(Path root, Properties properties) throws IOException {
        String ids = properties.getProperty("companions", "");
        if (ids.isBlank())
            return List.of();
        var result = new ArrayList<Path>();
        for (String id : ids.split(",", -1)) {
            if (result.size() >= 32 || !id.trim().matches("[a-z0-9][a-z0-9-]{0,62}"))
                throw new IOException("invalid companion identifier");
            if (!properties.getProperty("companion." + id.trim() + ".type", "").equals("CMAKE_SERVICE"))
                throw new IOException("companion build type requires a supported C/C++ build declaration");
            Properties source = new Properties();
            source.setProperty("buildDirectory", properties.getProperty("companion." + id.trim() + ".sourcePath", ""));
            Path target = mainRoot(root, source);
            if (target.equals(root) || result.contains(target))
                throw new IOException("conflicting companion source");
            result.add(target);
        }
        return List.copyOf(result);
    }
}
