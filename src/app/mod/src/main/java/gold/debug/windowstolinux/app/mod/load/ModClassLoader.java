package gold.debug.windowstolinux.app.mod.load;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.PluginClassLoader;

/** Shares declared public types while isolating author code from host implementation. / 共享声明的公开类型，同时将作者代码与宿主实现隔离。 */
public final class ModClassLoader extends PluginClassLoader {
    /** Exact package exports mapped to the unique dependency provider loader. / 精确公开包到唯一依赖提供方加载器的映射。 */
    private final Map<String, ModClassLoader> exports;

    /** Validated author metadata bound to this loading session. / 绑定此加载会话的已验证作者元数据。 */
    private final ModManifest manifest;

    /** Main source before companion outputs or private libraries. / 从输出或私有库之前的主来源。 */
    private final Optional<URL> mainSource;

    /**
     * Adapts validated local code sources to PF4J without requiring author PF4J types. / 将已验证本地代码来源适配到 PF4J，不要求作者使用 PF4J 类型。
     * @param manifest validated author identity and entry root / 已验证的作者身份与入口根包
     * @param sources ordered main, companion and private-library sources / 有序的主输出、从输出及私有库来源
     * @param exports required-provider public packages / 必需提供方的公开包
     */
    public ModClassLoader(ModManifest manifest, List<URL> sources, Map<String, ModClassLoader> exports) {
        super(null, new DefaultPluginDescriptor(manifest.id(), manifest.name(), manifest.entryClass(),
            manifest.version().toString(), "*", "", ""), ModClassLoader.class.getClassLoader());
        this.exports = Map.copyOf(exports);
        this.manifest = Objects.requireNonNull(manifest);
        var orderedSources = List.copyOf(sources);
        this.mainSource = orderedSources.isEmpty() ? Optional.empty() : Optional.of(orderedSources.getFirst());
        for (var first : exports.keySet()) {
            if (overlaps(first, manifest.rootPackage()))
                throw new IllegalArgumentException("Dependency export overlaps the current entry package");
            for (var second : exports.keySet())
                if (!first.equals(second) && overlaps(first, second))
                    throw new IllegalArgumentException("Ambiguous dependency exports");
        }
        for (var source : orderedSources) {
            if (!source.getProtocol().equals("file")
                || source.getAuthority() != null && !source.getAuthority().isEmpty())
                throw new IllegalArgumentException("Mod code sources must be local files");
            try {
                var path = Path.of(source.toURI());
                if (Files.isRegularFile(path))
                    try (var jar = new JarFile(path.toFile(), false)) {
                        rejectManifestClasspath(jar.getManifest());
                    }
            } catch (IOException | java.net.URISyntaxException failure) {
                throw new IllegalArgumentException("Invalid or implicit Mod code source", failure);
            }
            addURL(source);
        }
    }

    /** Rejects implicit sources that URL loading would add outside the explicit dependency graph. / 拒绝 URL 加载会在明确依赖图之外追加的隐式来源。
     * @param manifest original JAR manifest or absent / 原 JAR 清单或缺省
     * @throws IOException if an additional class path is declared / 声明额外类路径时 */
    public static void rejectManifestClasspath(Manifest manifest) throws IOException {
        if (manifest != null && manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH) != null)
            throw new IOException("JAR Class-Path is unsupported; declare each Mod code source explicitly");
    }

    /** Returns author metadata bound to this loader. / 返回绑定此加载器的作者元数据。
     * @return validated manifest / 已验证清单 */
    public ModManifest manifest() {
        return manifest;
    }

    /** Rechecks original code and exact provider signatures before author execution. / 作者执行前重检原代码及精确提供方签名。
     * @return inspected owner metadata / 已检验所有者元数据
     * @throws IOException if code admission fails / 代码准入失败时 */
    public ModCodeInspection inspectCode() throws IOException {
        return inspectCode(new IdentityHashMap<>(), Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** Visits each original provider once per inspection, retaining cycle and depth guards. / 每次检验仅访问各原提供方一次，并保留循环及深度保护。
     * @param inspected already verified originals / 已验证原对象
     * @param visiting active recursion path / 活动递归路径
     * @return inspected metadata / 已检验元数据
     * @throws IOException if unavailable, cyclic or invalid / 不可用、循环或无效时 */
    private ModCodeInspection inspectCode(Map<ModClassLoader, ModCodeInspection> inspected,
                                          Set<ModClassLoader> visiting) throws IOException {
        if (isClosed())
            throw new IOException("Mod loader is closed");
        if (inspected.containsKey(this))
            return inspected.get(this);
        if (visiting.size() > 256 || !visiting.add(this))
            throw new IOException("Cyclic or excessive provider graph");
        try {
            var sources = new ArrayList<Path>();
            for (var source : getURLs()) {
                try {
                    sources.add(Path.of(source.toURI()));
                } catch (java.net.URISyntaxException invalid) {
                    throw new IOException("Invalid code source", invalid);
                }
            }
            var bindings = new HashMap<String, ModCodeInspection>();
            for (var entry : exports.entrySet()) {
                bindings.put(entry.getKey(), entry.getValue().inspectCode(inspected, visiting));
            }
            var code = ModCodeInspection.read(manifest, sources);
            code.validate(true, bindings);
            inspected.put(this, code);
            return code;
        } finally {
            visiting.remove(this);
        }
    }

    /** Verifies that the explicit main source defines an entry class. / 验证入口类由显式主来源定义。
     * @param type loaded entry candidate / 已加载的入口候选
     * @return whether the main source owns the type / 主来源是否持有此类型 */
    public boolean ownsMainClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return type.getClassLoader() == this && source != null && mainSource.isPresent()
            && mainSource.get().toExternalForm().equals(source.getLocation().toExternalForm());
    }

    /**
     * Checks equality or ancestor relationships between Java packages. / 检查 Java 包的相同或祖先关系。
     * @param first first package / 第一个包
     * @param second second package / 第二个包
     * @return whether either export includes the other / 任一公开包是否包含另一包
     */
    private static boolean overlaps(String first, String second) {
        return first.equals(second) || first.startsWith(second + ".") || second.startsWith(first + ".");
    }

    /**
     * Resolves JDK, platform API, explicit dependency exports and owned sources in order. / 依次解析 JDK、平台 API、显式依赖公开包和所属来源。
     * @param name fully qualified requested class / 请求类的全限定名
     * @return the unique class from its owning loader / 来自所属加载器的唯一类
     * @throws ClassNotFoundException if closed or no permitted source defines the class / 已关闭或无允许来源定义此类时
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if (isClosed())
                throw new ClassNotFoundException("Mod loader is closed: " + name);
            var loaded = findLoadedClass(name);
            if (loaded != null)
                return loaded;
            if (name.startsWith("gold.debug.windowstolinux.appapi."))
                return getParent().loadClass(name);
            if (name.startsWith("gold.debug.windowstolinux.app.") || name.startsWith("org.h2."))
                throw new ClassNotFoundException("Reserved host implementation: " + name);
            try {
                return ClassLoader.getPlatformClassLoader().loadClass(name);
            } catch (ClassNotFoundException absent) {
                if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun."))
                    throw absent;
                for (var entry : exports.entrySet())
                    if (name.startsWith(entry.getKey() + "."))
                        return entry.getValue().loadClass(name);
                return findClass(name);
            }
        }
    }

    /**
     * Resolves one permitted resource without host-private fallback or JAR caching. / 解析一个允许资源，不回退到宿主私有来源或使用 JAR 缓存。
     * @param name canonical class-loader resource path / 规范的类加载器资源路径
     * @return owned resource URL, or null when unavailable / 所属资源地址，不可用时为空
     */
    @Override
    public URL getResource(String name) {
        if (isClosed() || !resourceName(name))
            return null;
        if (name.startsWith("gold/debug/windowstolinux/appapi/"))
            return getParent().getResource(name);
        var jdk = ClassLoader.getPlatformClassLoader().getResource(name);
        if (jdk != null)
            return jdk;
        for (var entry : exports.entrySet())
            if (name.startsWith(entry.getKey().replace('.', '/') + "/"))
                return entry.getValue().getResource(name);
        return uncached(findResource(name));
    }

    /**
     * Enumerates resources only from the selected owner and its ordered code sources. / 仅枚举选定所有者及其有序代码来源中的资源。
     * @param name canonical class-loader resource path / 规范的类加载器资源路径
     * @return ordered resource enumeration / 有序资源枚举
     * @throws IOException if a permitted source cannot be read / 无法读取允许来源时
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (isClosed() || !resourceName(name))
            return Collections.emptyEnumeration();
        if (name.startsWith("gold/debug/windowstolinux/appapi/"))
            return getParent().getResources(name);
        var jdk = ClassLoader.getPlatformClassLoader().getResources(name);
        if (jdk.hasMoreElements())
            return jdk;
        for (var entry : exports.entrySet())
            if (name.startsWith(entry.getKey().replace('.', '/') + "/"))
                return entry.getValue().getResources(name);
        var result = new ArrayList<URL>();
        var found = findResources(name);
        while (found.hasMoreElements())
            result.add(uncached(found.nextElement()));
        return Collections.enumeration(result);
    }

    /**
     * Rejects resource names that could escape a permitted resource prefix. / 拒绝可能逃离允许资源前缀的名称。
     * @param name requested resource name / 请求资源名
     * @return whether the name has canonical relative segments / 名称是否由规范相对路径段组成
     */
    private static boolean resourceName(String name) {
        return name != null && !name.isEmpty() && !name.contains("\\") && !name.contains(":") && name.indexOf('\0') < 0
            && Arrays.stream(name.split("/", -1))
            .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }

    /**
     * Wraps URL connections to avoid process-wide JAR caches retaining Windows handles. / 包装 URL 连接，避免进程级 JAR 缓存持有 Windows 文件句柄。
     * @param original owned original URL, possibly null / 所属原始地址，可为空
     * @return URL opening uncached connections, or null / 打开无缓存连接的地址，或为空
     */
    @SuppressWarnings("deprecation")
    private URL uncached(URL original) {
        if (original == null)
            return null;
        try {
            // URL.of forbids a file handler; this constructor preserves CLASS and JAR resource URLs. / URL.of 禁止自定义 file 处理器；此构造器保留 CLASS 与 JAR 资源地址。
            return new URL(null, original.toExternalForm(), new URLStreamHandler() {
                /** Opens the original resource without a shared JAR cache. / 打开原始资源，不使用共享 JAR 缓存。
                 * @param ignored wrapper URL / 包装地址
                 * @return uncached resource connection / 无缓存资源连接
                 * @throws IOException if the owner closed or opening fails / 所有者已关闭或打开失败时 */
                @Override
                protected URLConnection openConnection(URL ignored) throws IOException {
                    if (isClosed())
                        throw new IOException("Mod loader is closed");
                    var connection = original.openConnection();
                    connection.setUseCaches(false);
                    return connection;
                }
            });
        } catch (java.net.MalformedURLException failure) {
            throw new IllegalStateException("Invalid source resource URL", failure);
        }
    }
}
