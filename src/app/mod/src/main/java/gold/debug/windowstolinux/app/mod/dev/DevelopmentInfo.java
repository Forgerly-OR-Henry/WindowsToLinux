package gold.debug.windowstolinux.app.mod.dev;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.JarFile;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.config.ModManifest;

/** Reads and generates build-owned CLASS sources using the same explicit input digest. / 使用相同显式输入摘要读取及生成构建所属 CLASS 来源。
 * @param manifestPath original author manifest / 原作者清单
 * @param mainOutput compiled main output / 已编译主输出
 * @param authorOutputs resolved companion outputs in classpath order / 按类路径顺序解析的从模块输出
 * @param buildInputs resolved build configuration files / 已解析构建配置文件
 * @param libraries resolved private dependency JARs in classpath order / 按类路径顺序解析的私有依赖 JAR
 * @param inputDigest digest of the explicit input set / 显式输入集合的摘要 */
public record DevelopmentInfo(Path manifestPath, Path mainOutput, List<Path> authorOutputs,
                              List<Path> buildInputs, List<Path> libraries, String inputDigest) {
    /** Strict parser without trailing values or duplicate fields. / 不允许尾随值或重复字段的严格解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** All required format-one fields. / 格式一的全部必需字段。 */
    private static final Set<String> FIELDS = Set.of("formatVersion", "manifestPath", "mainOutput", "authorOutputs",
        "buildInputs", "libraries", "inputDigest");

    /** Maximum generated description bytes. / 已生成描述的最大字节数。 */
    private static final int MAX_BYTES = 1024 * 1024;

    /** Maximum entries in one resolved file list. / 单个已解析文件列表的最大项数。 */
    private static final int MAX_PATHS = 4096;

    /** Main-output evidence created after compilation. / 编译后创建的主输出证据。 */
    private static final String MARKER = "META-INF/wtlmod-development-input.sha256";

    /** Copies collections without interpreting unvalidated author paths. / 复制集合，不解释尚未校验的作者路径。
     * @param manifestPath original manifest / 原清单
     * @param mainOutput compiled main output / 已编译主输出
     * @param authorOutputs resolved companion outputs / 已解析从输出
     * @param buildInputs resolved build inputs / 已解析构建输入
     * @param libraries resolved private JARs / 已解析私有 JAR
     * @param inputDigest explicit input digest / 显式输入摘要 */
    public DevelopmentInfo {
        Objects.requireNonNull(manifestPath);
        Objects.requireNonNull(mainOutput);
        authorOutputs = List.copyOf(authorOutputs);
        buildInputs = List.copyOf(buildInputs);
        libraries = List.copyOf(libraries);
        Objects.requireNonNull(inputDigest);
    }

    /** Validates the explicitly supplied workspace without guessing from the working directory. / 验证明示工作区，不从工作目录猜测。
     * @param workspace explicit absolute workspace / 明示绝对工作区
     * @return canonical existing workspace / 规范化的现有工作区
     * @throws IOException if required project anchors are missing / 缺少必需工程锚点时 */
    public static Path workspace(Path workspace) throws IOException {
        if (workspace == null || !workspace.isAbsolute())
            throw new IOException("CLASS loading requires an explicit absolute workspace");
        Path root = workspace.toRealPath();
        for (String anchor : List.of("pom.xml", "src/app/pom.xml", "src/mod/pom.xml"))
            localFile(root, root.resolve(anchor));
        return root;
    }

    /** Reads and checks the description, compiled marker, current inputs and exact main entry file. / 读取并核对描述、编译标记、当前输入及精确主入口文件。
     * @param workspace explicit workspace / 明示工作区
     * @param descriptor fixed generated description path / 固定生成描述路径
     * @return validated current compiled sources / 已校验的当前编译来源
     * @throws IOException if malformed, stale, incomplete or outside its owner / 格式错误、过期、不完整或越过归属时 */
    public static DevelopmentInfo read(Path workspace, Path descriptor) throws IOException {
        Path root = workspace(workspace);
        Path file = localFile(root, descriptor);
        try {
            var node = JSON.readTree(utf8(file, MAX_BYTES));
            if (node == null || !node.isObject() || node.size() != FIELDS.size())
                throw new IOException("Development description must contain exactly the format-one fields");
            for (var fields = node.fields(); fields.hasNext(); ) {
                var field = fields.next();
                if (!FIELDS.contains(field.getKey()) || field.getValue().isNull())
                    throw new IOException("Unknown or null development field");
            }
            if (!node.get("formatVersion").isIntegralNumber() || !node.get("formatVersion").canConvertToInt()
                || node.get("formatVersion").intValue() != 1)
                throw new IOException("Unsupported development description format");
            var value = new DevelopmentInfo(path(node.get("manifestPath")), path(node.get("mainOutput")),
                paths(node.get("authorOutputs")), paths(node.get("buildInputs")), paths(node.get("libraries")),
                text(node.get("inputDigest")));
            value.validate(root, file);
            if (!value.inputDigest.matches("[0-9a-f]{64}") || !value.inputDigest.equals(value.digest())
                || !utf8(localFile(root, value.mainOutput.resolve(MARKER)), 65).equals(value.inputDigest + "\n"))
                throw new IOException("Mod inputs or compiled marker changed; compile the development Mod again");
            return value;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid development description", failure);
        }
    }

    /** Generates evidence from already compiled outputs and the build's resolved dependency lists. / 从已编译输出及构建解析的依赖列表生成证据。
     * @param workspace explicit workspace / 明示工作区
     * @param manifestPath original manifest / 原清单
     * @param mainOutput compiled main output / 已编译主输出
     * @param authorOutputs resolved companion outputs / 已解析从输出
     * @param buildInputs resolved build configuration inputs / 已解析构建配置输入
     * @param libraries resolved private dependencies / 已解析私有依赖
     * @return generated description path / 已生成描述路径
     * @throws IOException if inputs or outputs are invalid or publication fails / 输入输出无效或发布失败时 */
    public static Path generate(Path workspace, Path manifestPath, Path mainOutput, List<Path> authorOutputs,
                                List<Path> buildInputs, List<Path> libraries) throws IOException {
        Path root = workspace(workspace);
        var value = new DevelopmentInfo(manifestPath, mainOutput, authorOutputs, buildInputs, libraries, "");
        Path file = mainOutput.getParent().resolve("wtlmod-development.json");
        value.validate(root, file);
        gold.debug.windowstolinux.app.mod.load.ModCodeChecker.read(value.manifest(), value.sources())
            .validate(false, Map.of());
        String digest = value.digest();
        var node = JSON.createObjectNode().put("formatVersion", 1).put("manifestPath", key(manifestPath))
            .put("mainOutput", key(mainOutput));
        for (var group : Map.of("authorOutputs", authorOutputs, "buildInputs", buildInputs, "libraries", libraries)
            .entrySet()) {
            var array = node.putArray(group.getKey());
            for (var path : group.getValue())
                array.add(key(path));
        }
        node.put("inputDigest", digest);
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        if (bytes.length > MAX_BYTES)
            throw new IOException("Development description exceeds the size limit");
        Path marker = mainOutput.resolve(MARKER);
        Files.createDirectories(marker.getParent());
        if (!marker.getParent().toRealPath().equals(marker.getParent()) || Files.isSymbolicLink(marker)
            || Files.isSymbolicLink(file))
            throw new IOException("Generated evidence must remain in its compiled workspace");
        // An interrupted pair remains mismatched and is rejected on read. / 中断的证据对保持不匹配，读取时拒绝。
        Files.writeString(marker, digest + "\n", StandardCharsets.UTF_8);
        Files.write(file, bytes);
        return file;
    }

    /** Reads the current strict manifest after validating its description. / 校验描述后读取当前严格清单。
     * @return author metadata / 作者元数据
     * @throws IOException if the current manifest is invalid / 当前清单无效时 */
    public ModManifest manifest() throws IOException {
        return ManifestReader.read(utf8(manifestPath, ManifestReader.MAX_BYTES).getBytes(StandardCharsets.UTF_8));
    }

    /** Reads source identity even when its generated output is missing. / 即使缺少生成输出也读取来源身份。
     * @param root canonical workspace / 规范工作区
     * @param manifest original author manifest / 原作者清单
     * @return validated declaration / 已校验声明
     * @throws IOException if redirected, unreadable or malformed / 重定向、不可读或格式错误时 */
    static ModManifest manifest(Path root, Path manifest) throws IOException {
        return ManifestReader
            .read(utf8(localFile(root, manifest), ManifestReader.MAX_BYTES).getBytes(StandardCharsets.UTF_8));
    }

    /** Returns main, companions and private libraries in the build's explicit order. / 按构建明示顺序返回主输出、从输出和私有库。
     * @return immutable ordered code sources / 不可变有序代码来源 */
    public List<Path> sources() {
        var result = new ArrayList<Path>();
        result.add(mainOutput);
        result.addAll(authorOutputs);
        result.addAll(libraries);
        return List.copyOf(result);
    }

    /** Checks ownership and required build provenance without reparsing Maven at runtime. / 检查归属及必需构建来源，不在运行时重新解析 Maven。
     * @param root validated workspace / 已校验工作区
     * @param descriptor expected generated description / 预期生成描述
     * @throws IOException if a path, output or required input is invalid / 路径、输出或必需输入无效时 */
    private void validate(Path root, Path descriptor) throws IOException {
        Path manifest = localFile(root, manifestPath);
        Path module = manifest.getParent();
        if (!manifest.equals(module.resolve("mod.json")) || !module.startsWith(root.resolve("src/mod"))
            || module.equals(root.resolve("src/mod")) || !mainOutput.equals(module.resolve("target/classes"))
            || !descriptor.equals(module.resolve("target/wtlmod-development.json")))
            throw new IOException("Development main sources must belong to one Mod module");
        var inputs = new HashSet<Path>();
        for (Path input : buildInputs)
            inputs.add(localFile(root, input));
        if (!inputs.contains(root.resolve("pom.xml")) || !inputs.contains(root.resolve("src/mod/pom.xml"))
            || !inputs.contains(module.resolve("pom.xml")))
            throw new IOException("Build inputs must include the workspace, Mod aggregate and main module POMs");
        var outputs = new HashSet<Path>();
        for (Path output : concat(List.of(mainOutput), authorOutputs)) {
            Path canonical = local(root, output);
            Path owner = canonical.getParent() == null ? null : canonical.getParent().getParent();
            if (!Files.isDirectory(canonical) || owner == null || !canonical.equals(owner.resolve("target/classes"))
                || owner.startsWith(root.resolve("src/app")) || owner.startsWith(root.resolve("src/appapi"))
                || !inputs.contains(owner.resolve("pom.xml")) || !outputs.add(canonical))
                throw new IOException(
                    "Companion outputs require distinct compiled Mod modules and declared POM inputs");
        }
        var privateJars = new HashSet<Path>();
        for (Path library : libraries) {
            if (!library.isAbsolute() || !library.equals(library.normalize()) || !Files.isRegularFile(library)
                || !library.getFileName().toString().endsWith(".jar") || !privateJars.add(library.toRealPath()))
                throw new IOException("Private libraries require distinct existing absolute JAR paths");
            if (library.toRealPath().startsWith(root.resolve("history-src"))
                || library.toRealPath().startsWith(root.resolve(".git")))
                throw new IOException("Archived or repository-internal libraries are not active Mod dependencies");
            try (var jar = new JarFile(library.toFile())) {
                jar.size();
            }
        }
        if (authorOutputs.size() > MAX_PATHS || buildInputs.size() > MAX_PATHS || libraries.size() > MAX_PATHS)
            throw new IOException("Too many development input paths");
        var metadata = manifest();
        localFile(root, mainOutput.resolve(metadata.entryClass().replace('.', '/') + ".class"));
    }

    /** Computes the v1 digest over deduplicated explicit regular files. / 对去重后的明示普通文件计算 v1 摘要。
     * @return lowercase SHA-256 / 小写 SHA-256
     * @throws IOException if an input cannot be fully read / 无法完整读取输入时 */
    private String digest() throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("WTLMOD-DEVELOPMENT-1\n".getBytes(StandardCharsets.US_ASCII));
            var files = new TreeMap<String, Path>((a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)));
            for (Path file : new LinkedHashSet<>(concat(concat(List.of(manifestPath), buildInputs), libraries)))
                files.putIfAbsent(key(file), file);
            for (var entry : files.entrySet()) {
                var content = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (var stream = Files.newInputStream(entry.getValue())) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = stream.read(buffer)) != -1; ) {
                        length += count;
                        content.update(buffer, 0, count);
                    }
                }
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(8).putLong(length).array());
                digest.update(content.digest());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }

    /** Requires canonical workspace ownership, including ancestors resolved through links. / 要求规范工作区归属，包括通过链接解析的祖先。
     * @param root canonical workspace / 规范工作区
     * @param path declared absolute path / 声明的绝对路径
     * @return canonical admitted path / 已准入规范路径
     * @throws IOException if redirected or outside the active workspace / 重定向或越过活动工作区时 */
    private static Path local(Path root, Path path) throws IOException {
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(root)
            || path.startsWith(root.resolve("history-src")) || path.startsWith(root.resolve(".git")))
            throw new IOException("Development path is outside the active workspace");
        Path real = path.toRealPath();
        if (!path.equals(real))
            throw new IOException("Development paths must not redirect through links");
        return real;
    }

    /** Requires one ordinary existing workspace file. / 要求一个普通的现有工作区文件。
     * @param root canonical workspace / 规范工作区
     * @param path declared file / 声明文件
     * @return admitted file / 已准入文件
     * @throws IOException if not a regular owned file / 不是归属内普通文件时 */
    private static Path localFile(Path root, Path path) throws IOException {
        Path real = local(root, path);
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Development input must be a regular file");
        return real;
    }

    /** Reads bounded strict UTF-8 without a byte-order mark. / 读取有界严格 UTF-8，不接受字节序标记。
     * @param file original file / 原文件
     * @param limit maximum bytes / 最大字节数
     * @return decoded text / 解码文本
     * @throws IOException if unreadable, oversized or malformed / 不可读、超限或编码错误时 */
    private static String utf8(Path file, int limit) throws IOException {
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) {
            bytes = stream.readNBytes(limit + 1);
        }
        if (bytes.length == 0 || bytes.length > limit)
            throw new IOException("Development input size is invalid");
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (value.charAt(0) == '\ufeff')
            throw new IOException("Development input BOM is forbidden");
        return value;
    }

    /** Requires a nonempty JSON string. / 要求非空 JSON 字符串。
     * @param node candidate value / 候选值
     * @return original text / 原文本
     * @throws IOException if not nonempty text / 不是非空文本时 */
    private static String text(JsonNode node) throws IOException {
        if (!node.isTextual() || node.textValue().isEmpty())
            throw new IOException("Development field must be a nonempty string");
        return node.textValue();
    }

    /** Parses an explicit absolute path. / 解析明示绝对路径。
     * @param node path text / 路径文本
     * @return absolute normalized path / 绝对规范路径
     * @throws IOException if relative or noncanonical / 相对或不规范时 */
    private static Path path(JsonNode node) throws IOException {
        Path value = Path.of(text(node));
        if (!value.isAbsolute() || !value.equals(value.normalize()))
            throw new IOException("Development path must be absolute and normalized");
        return value;
    }

    /** Reads a bounded path array in its build order. / 按构建顺序读取有界路径数组。
     * @param node expected array / 预期数组
     * @return copied paths / 路径副本
     * @throws IOException if not a valid bounded array / 不是有效有界数组时 */
    private static List<Path> paths(JsonNode node) throws IOException {
        if (!node.isArray() || node.size() > MAX_PATHS)
            throw new IOException("Development path list is invalid");
        var result = new ArrayList<Path>();
        for (var element : node)
            result.add(path(element));
        return List.copyOf(result);
    }

    /** Encodes the explicit path key used by the descriptor and digest. / 编码描述及摘要采用的明示路径键。
     * @param path absolute path / 绝对路径
     * @return slash-separated path / 使用正斜线的路径 */
    private static String key(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** Concatenates two ordered path lists without removing classpath entries. / 拼接两个有序路径列表，不移除类路径项。
     * @param first first paths / 第一组路径
     * @param second following paths / 后续路径
     * @return ordered combined paths / 合并后的有序路径 */
    private static List<Path> concat(List<Path> first, List<Path> second) {
        var result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }
}
