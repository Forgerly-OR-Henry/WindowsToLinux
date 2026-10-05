package gold.debug.windowstolinux.app.mod.load;

import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.*;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import org.objectweb.asm.Opcodes;

/** Bounded static entry and export admission shared by archives and compiled sources. / 原包与编译来源共用的有界入口及导出静态准入。 */
public final class ModCodeChecker {
    /** Maximum uncompressed bytes of one class. / 单个类的解压字节上限。 */
    public static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    /** Maximum cumulative inspected class bytes per owner. / 每个所有者累计检验类字节上限。 */
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    /** Maximum filesystem or archive entries per owner. / 每个所有者文件系统或归档条目上限。 */
    private static final int MAX_ENTRIES = 100000;

    /** Shared contract's internal name. / 共用契约内部名称。 */
    private static final String ENTRY = "gold/debug/windowstolinux/appapi/mod/ModEntrypoint";

    /** Host API namespace. / 宿主 API 命名空间。 */
    private static final String API = "gold/debug/windowstolinux/appapi/";

    /** Author declaration being inspected. / 正检验的作者声明。 */
    private final ModManifest manifest;

    /** Logical classes with release-specific original metadata. / 带发布版本原元数据的逻辑类。 */
    private final Map<String, NavigableMap<Integer, ModClassShape>> classes = new HashMap<>();

    /** Unique explicit source for each logical class. / 每个逻辑类的唯一明示来源。 */
    private final Map<String, String> owners = new HashMap<>();

    /** Effective views shared by hierarchy traversals within this inspection. / 本次检验的继承遍历共用生效视图。 */
    private final Map<Integer, Map<String, ModClassShape>> views = new HashMap<>();

    /** Proven direct provider bindings retained for inherited provider contracts. / 为继承提供方契约保留的已证明直接提供方绑定。 */
    private Map<String, ModCodeChecker> bindings = Map.of();

    /** Distinct release overlays, bounded independently of class count. / 独立于类数量约束的不同发布覆盖层。 */
    private final Set<Integer> releases = new HashSet<>();

    /** Current JDK package ownership, including nonexported implementation packages. / 当前 JDK 包归属，包括非导出实现包。 */
    private static final Set<String> JDK_PACKAGES = ModuleLayer.boot().modules().stream()
        .flatMap(module -> module.getPackages().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Main source identity supplied by the caller. / 调用方提供的主来源身份。 */
    private String main;

    /** Cumulative original class bytes. / 累计原类字节。 */
    private long bytes;

    /** Number of inspected physical classes. / 检验的物理类数量。 */
    private int count;

    /** Starts one original-byte inspection, without loading classes. / 开始一次原字节检验，不加载类。
     * @param manifest validated author metadata / 已验证作者元数据 */
    public ModCodeChecker(ModManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest);
    }

    /** Reads exact directory and JAR sources in loading order. / 按加载顺序读取精确目录及 JAR 来源。
     * @param manifest validated author declaration / 已验证作者声明
     * @param sources main, companion and private-library paths / 主、从及私有库路径
     * @return complete static index / 完整静态索引
     * @throws IOException if paths or class metadata are invalid / 路径或类元数据无效时 */
    public static ModCodeChecker read(ModManifest manifest, List<Path> sources) throws IOException {
        var inspection = new ModCodeChecker(manifest);
        int[] entries = {0};
        for (int i = 0; i < sources.size(); i++) {
            Path source = sources.get(i);
            ordinary(source);
            boolean primary = i == 0;
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    /** Rejects redirected source directories. / 拒绝重定向来源目录。
                     * @param path visited directory / 访问目录
                     * @param attrs original attributes / 原属性
                     * @return continue traversal / 继续遍历
                     * @throws IOException if redirected or excessive / 重定向或超限时 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                        ordinary(path);
                        limit(++entries[0]);
                        return FileVisitResult.CONTINUE;
                    }

                    /** Reads original class files only. / 仅读取原类文件。
                     * @param path visited file / 访问文件
                     * @param attrs original attributes / 原属性
                     * @return continue traversal / 继续遍历
                     * @throws IOException if redirected, excessive or invalid / 重定向、超限或无效时 */
                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                        ordinary(path);
                        limit(++entries[0]);
                        String name = source.relativize(path).toString().replace('\\', '/');
                        if (name.endsWith(".class") && !name.startsWith("META-INF/"))
                            try (var input = Files.newInputStream(path)) {
                                inspection.add(source.toString(), primary, name, 0, input, attrs.size());
                            }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                try (var jar = new JarFile(source.toFile(), false)) {
                    ModClassLoader.rejectManifestClasspath(jar.getManifest());
                    var names = new HashSet<String>();
                    var iterator = jar.entries();
                    while (iterator.hasMoreElements()) {
                        var entry = iterator.nextElement();
                        limit(++entries[0]);
                        if (!names.add(entry.getName()))
                            throw new IOException("Duplicate JAR resource");
                        if (entry.isDirectory())
                            continue;
                        String name = entry.getName();
                        int release = release(name, jar.isMultiRelease());
                        if (release < 0 || !name.endsWith(".class"))
                            continue;
                        if (release > 0)
                            name = name.split("/", 4)[3];
                        try (var input = jar.getInputStream(entry)) {
                            inspection.add(source.toString(), primary, name, release, input, entry.getSize());
                        }
                    }
                }
            }
        }
        return inspection;
    }

    /** Resolves a physical JAR name according to the Multi-Release header. / 按 Multi-Release 头解析物理 JAR 名称。
     * @param name original resource name / 原资源名
     * @param multiRelease actual JAR header flag / 实际 JAR 头标志
     * @return zero for base, positive release, or minus one for inactive metadata / 基础为零、发布为正、非活动元数据为负一
     * @throws IOException if a release path is malformed / 发布路径畸形时 */
    public static int release(String name, boolean multiRelease) throws IOException {
        if (!name.startsWith("META-INF/"))
            return 0;
        if (!name.startsWith("META-INF/versions/") || !multiRelease)
            return -1;
        String[] parts = name.split("/", 4);
        try {
            if (parts.length != 4 || !parts[2].matches("[1-9][0-9]*"))
                throw new NumberFormatException();
            int result = Integer.parseInt(parts[2]);
            if (result < 9)
                throw new NumberFormatException();
            return result;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid multi-release class path", invalid);
        }
    }

    /** Adds one bounded class from an already validated source container. / 从已验证来源容器加入一个有界类。
     * @param source exact source identity / 精确来源身份
     * @param primary whether this is the main source / 是否为主来源
     * @param path logical class resource path / 逻辑类资源路径
     * @param release zero for base or Java release overlay / 基础为零或 Java 发布覆盖版本
     * @param input original uncompressed stream, owned by caller / 调用方持有的原解压流
     * @param size declared uncompressed size / 声明解压大小
     * @throws IOException if metadata, ownership or bounds fail / 元数据、归属或界限失败时 */
    public void add(String source, boolean primary, String path, int release, InputStream input, long size)
        throws IOException {
        if (path.equals("module-info.class"))
            return;
        if (!path.endsWith(".class") || size < 24 || size > MAX_CLASS_BYTES || bytes + size > MAX_TOTAL_BYTES)
            throw new IOException("Invalid or excessive class payload");
        limit(++count);
        bytes += size;
        releases.add(release);
        if (release < 0 || releases.size() > 64)
            throw new IOException("Too many class release overlays");
        views.clear();
        String name = path.substring(0, path.length() - 6);
        if (name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/") || reserved(name)
            || name.startsWith(API) || ClassLoader.getPlatformClassLoader().getResource(path) != null)
            throw new IOException("Reserved host or JDK class: " + name);
        var prior = owners.putIfAbsent(name, source);
        if (prior != null && !prior.equals(source))
            throw new IOException("Duplicate class across Mod sources: " + name);
        byte[] original = input.readNBytes((int) size + 1);
        if (original.length != size)
            throw new IOException("Class length differs from declaration");
        var shape = ModClassShape.read(original, name);
        if (classes.computeIfAbsent(name, key -> new TreeMap<>()).putIfAbsent(release, shape) != null)
            throw new IOException("Duplicate class release: " + name);
        if (primary)
            main = source;
    }

    /** Validates all archive release surfaces or the actual running Java surface. / 验证全部归档发布表面或实际运行 Java 表面。
     * Installation may defer absent declared providers; activation never does. / 安装可延后缺失的声明提供方，启用从不延后。
     * @param activation whether every reference must resolve now / 是否要求每个引用立即解析
     * @param providers exact dependency export packages and inspected owners / 精确依赖导出包及已检验所有者
     * @throws IOException if the entry or public contract is invalid / 入口或公开契约无效时 */
    public void validate(boolean activation, Map<String, ModCodeChecker> providers) throws IOException {
        bindings = Map.copyOf(providers);
        String entry = manifest.entryClass().replace('.', '/');
        if (!Objects.equals(main, owners.get(entry)) || !classes.containsKey(entry)
            || !classes.get(entry).containsKey(0))
            throw new IOException("Main source must contain the base entry class");
        var releases = new TreeSet<Integer>();
        if (activation)
            releases.add(Runtime.version().feature());
        else {
            releases.add(0);
            classes.values().forEach(variants -> releases.addAll(variants.keySet()));
        }
        for (int release : releases) {
            var effective = effective(release);
            if (activation && effective.keySet().stream().anyMatch(
                name -> providers.keySet().stream().anyMatch(pkg -> name.startsWith(pkg.replace('.', '/') + "/"))))
                throw new IOException("Owned class collides with a dependency export");
            var shape = effective.get(entry);
            if ((shape.access & Opcodes.ACC_PUBLIC) == 0
                || (shape.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 || !shape.constructor
                || !accessible(shape, effective, new HashSet<>()))
                throw new IOException("Entry must be public, concrete and have a public zero-argument constructor");
            if (activation
                && effective.values().stream().anyMatch(type -> type.major > Runtime.version().feature() + 44))
                throw new IOException("Class version is incompatible with the current Java runtime");
            if (activation && manifest.dependencies().stream().anyMatch(ModManifest.Dependency::optional))
                for (String reference : shape.references) {
                    var provider = provider(reference, providers);
                    if (provider != null && manifest.dependencies().stream().anyMatch(d -> d.optional() && d.id().equals(provider.manifest.id()))
                        || provider == null && !effective.containsKey(reference) && !trusted(reference))
                        throw new IOException("Entry declaration requires an unavailable or optional type: " + reference);
                }
            int contract = inherits(this, entry, release, providers, activation, new HashSet<>(), 0);
            if (contract == 0)
                throw new IOException("Entry does not implement ModEntrypoint");
            for (var type : effective.values()) {
                if (!exported(type.name) || !accessible(type, effective, new HashSet<>()))
                    continue;
                for (String reference : type.references)
                    if (!allowed(reference, effective, release, providers, activation))
                        throw new IOException("Exported signature exposes unavailable or private type: " + type.name
                            + " -> " + reference);
            }
        }
    }

    /** Resolves visible classes for one Java release. / 解析一个 Java 发布的可见类。
     * @param release requested release / 请求发布
     * @return effective metadata / 生效元数据 */
    private Map<String, ModClassShape> effective(int release) {
        var cached = views.get(release);
        if (cached != null)
            return cached;
        var result = new HashMap<String, ModClassShape>();
        classes.forEach((name, variants) -> {
            var entry = variants.floorEntry(release);
            if (entry != null)
                result.put(name, entry.getValue());
        });
        views.put(release, result);
        return result;
    }

    /** Checks a direct public signature reference using actual ownership. / 用实际归属检查直接公开签名引用。
     * @param name referenced internal name / 引用内部名称
     * @param own effective owned types / 生效自有类型
     * @param release inspected release / 检验发布
     * @param providers exact selected providers / 精确已选提供方
     * @param activation require full resolution / 要求完整解析
     * @return whether permitted or explicitly deferred / 是否允许或明确延后 */
    private boolean allowed(String name, Map<String, ModClassShape> own, int release,
                            Map<String, ModCodeChecker> providers, boolean activation) {
        if (reserved(name))
            return false;
        if (trusted(name))
            return true;
        if (name.startsWith(API))
            return !activation;
        if (jdkPackage(name))
            return !activation && manifest.minJavaVersion() > Runtime.version().feature();
        if (own.containsKey(name))
            return exported(name) && accessible(own.get(name), own, new HashSet<>());
        var provider = provider(name, providers);
        if (provider != null) {
            if (manifest.dependencies().stream().anyMatch(d -> d.id().equals(provider.manifest.id()) && d.optional()))
                return false;
            var actual = provider.effective(release == 0 ? Runtime.version().feature() : release);
            return provider.exported(name) && actual.containsKey(name)
                && accessible(actual.get(name), actual, new HashSet<>());
        }
        return !activation && !manifest.dependencies().isEmpty();
    }

    /** Traverses author hierarchy metadata without class linking. / 不链接类地遍历作者继承元数据。
     * @param owner original metadata owner / 原元数据所有者
     * @param name current internal name / 当前内部名称
     * @param release inspected Java release / 检验 Java 发布
     * @param providers direct selected exports / 直接已选导出
     * @param activation disallow unresolved hierarchy / 禁止未解析继承关系
     * @param visiting cycle guard / 循环保护
     * @param depth bounded traversal depth / 有界遍历深度
     * @return one for proven, zero for absent, minus one for deferred / 已证明为一、无实现为零、延后为负一
     * @throws IOException if cyclic or excessive / 循环或超限时 */
    private static int inherits(ModCodeChecker owner, String name, int release,
                                Map<String, ModCodeChecker> providers, boolean activation, Set<String> visiting, int depth)
        throws IOException {
        if (name.equals(ENTRY))
            return 1;
        if (depth > 256 || !visiting.add(owner.manifest.id() + ":" + name))
            throw new IOException("Cyclic or excessive class hierarchy");
        try {
            var shape = owner.effective(release).get(name);
            if (shape == null) {
                var provider = owner.provider(name, providers);
                if (provider != null && owner.manifest.dependencies().stream()
                    .anyMatch(d -> d.id().equals(provider.manifest.id()) && d.optional()))
                    throw new IOException("Entry hierarchy requires an optional provider: " + name);
                if (provider != null && provider.exported(name))
                    return inherits(provider, name, release == 0 ? Runtime.version().feature() : release,
                        provider.bindings, activation, visiting, depth + 1);
                if (trusted(name))
                    return 0;
                if (reserved(name))
                    throw new IOException("Entry inherits host implementation: " + name);
                if (jdkPackage(name) && (activation || owner.manifest.minJavaVersion() <= Runtime.version().feature()))
                    throw new IOException("Unavailable JDK entry ancestor: " + name);
                if (!activation && (name.startsWith(API) || !owner.manifest.dependencies().isEmpty()
                    || jdkPackage(name) && owner.manifest.minJavaVersion() > Runtime.version().feature()))
                    return -1;
                throw new IOException("Unresolved entry ancestor: " + name);
            }
            int result = 0;
            for (String parent : shape.parents) {
                int next = inherits(owner, parent, release, providers, activation, visiting, depth + 1);
                if (next == 1)
                    result = 1;
                else if (next == -1 && result == 0)
                    result = -1;
            }
            return result;
        } finally {
            visiting.remove(owner.manifest.id() + ":" + name);
        }
    }

    /** Finds only an explicitly declared compatible provider export. / 仅查找明确声明且兼容的提供方导出。
     * @param name internal referenced type / 内部引用类型
     * @param providers bound exports / 绑定导出
     * @return original provider or absent / 原提供方或缺省 */
    private ModCodeChecker provider(String name, Map<String, ModCodeChecker> providers) {
        String binary = name.replace('/', '.');
        for (var entry : providers.entrySet()) {
            var candidate = entry.getValue();
            if (binary.startsWith(entry.getKey() + ".") && candidate.manifest.exports().contains(entry.getKey())
                && manifest.dependencies().stream()
                .anyMatch(dependency -> dependency.id().equals(candidate.manifest.id())
                    && dependency.versionRange().contains(candidate.manifest.version())))
                return candidate;
        }
        return null;
    }

    /** Checks a declaration against this owner's exported package boundaries. / 按所有者导出包边界检查声明。
     * @param name internal name / 内部名称
     * @return whether an export contains the type / 导出是否包含类型 */
    private boolean exported(String name) {
        return manifest.exports().stream().anyMatch(pkg -> name.startsWith(pkg.replace('.', '/') + "/"));
    }

    /** Checks effective enclosing visibility without assuming dollar signs imply nesting. / 检查生效外层可见性，不假设美元符号代表嵌套。
     * @param shape inspected declaration / 检验声明
     * @param types effective source index / 生效来源索引
     * @param visited cycle guard / 循环保护
     * @return accessible public or protected member declaration / 可访问公开或受保护成员声明 */
    private static boolean accessible(ModClassShape shape, Map<String, ModClassShape> types, Set<String> visited) {
        if (shape.local || !ModClassShape.visible(shape.access) || visited.size() > 256 || !visited.add(shape.name))
            return false;
        if (shape.outer == null)
            return (shape.access & Opcodes.ACC_PUBLIC) != 0;
        var outer = types.get(shape.outer);
        return outer != null && accessible(outer, types, visited);
    }

    /** Resolves only trusted JDK or shared API classes without initialization. / 仅解析受信 JDK 或共用 API 类，不初始化。
     * @param name internal name / 内部名称
     * @return whether the trusted public type is accessible / 受信公开类型是否可访问 */
    private static boolean trusted(String name) {
        try {
            boolean api = name.startsWith(API);
            Class<?> type = Class.forName(name.replace('/', '.'), false,
                api ? ModEntrypoint.class.getClassLoader() : ClassLoader.getPlatformClassLoader());
            return Modifier.isPublic(type.getModifiers())
                && (api || type.getModule().isExported(type.getPackageName()));
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /** Identifies host implementation namespaces forbidden in author contracts. / 识别作者契约禁止的宿主实现命名空间。
     * @param name internal name / 内部名称
     * @return whether host-private / 是否为宿主私有 */
    private static boolean reserved(String name) {
        return name.startsWith("gold/debug/windowstolinux/app/") || name.startsWith("org/h2/");
    }

    /** Recognizes current platform packages and reserved Java roots. / 识别当前平台包及保留 Java 根。
     * @param name internal type name / 内部类型名称
     * @return whether the platform owns the package / 平台是否持有此包 */
    private static boolean jdkPackage(String name) {
        int slash = name.lastIndexOf('/');
        return name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")
            || slash > 0 && JDK_PACKAGES.contains(name.substring(0, slash).replace('/', '.'));
    }

    /** Enforces a shared entry work budget. / 强制共用条目工作预算。
     * @param count observed count / 已观察计数
     * @throws IOException if excessive / 超限时 */
    private static void limit(int count) throws IOException {
        if (count > MAX_ENTRIES)
            throw new IOException("Too many Mod code entries");
    }

    /** Rejects redirected or nonordinary explicit paths. / 拒绝重定向或非普通明示路径。
     * @param path original path / 原路径
     * @throws IOException if absent or redirected / 缺失或重定向时 */
    private static void ordinary(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.toRealPath().equals(path))
            throw new IOException("Noncanonical code source");
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther() || (!attrs.isRegularFile() && !attrs.isDirectory()))
            throw new IOException("Redirected code source");
    }
}
