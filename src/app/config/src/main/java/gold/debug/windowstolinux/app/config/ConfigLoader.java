package gold.debug.windowstolinux.app.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads a strict common/mode YAML document without project dependencies. / 无项目依赖的严格通用/模式YAML读取器。 */
public final class ConfigLoader {
    /** Config-owned resource bundled with the module JAR. / 随模块 JAR 发布的 Config 所属资源。 */
    public static final String RESOURCE = "/gold/debug/windowstolinux/app/config/config.yaml";

    /** Accepted configuration mode sections. / 允许的配置模式分区。 */
    private static final Set<String> MODES = Set.of("class", "jar", "app");

    /** Complete supported leaf keys, including the CLASS-only Mod switch. / 全部支持叶字段，包含 CLASS 专属 Mod 开关。 */
    private static final Set<String> KEYS = Set.of("database.data-directory", "ui.navigation-collapsed",
        "ui.advanced-expanded", "defaults.locale", "defaults.theme", "defaults.connect-seconds", "defaults.idle-seconds",
        "defaults.delete-ai-explanation", "ssh.heartbeat-seconds", "ssh.recovery.observation-seconds",
        "ssh.recovery.probe-interval-seconds", "ssh.recovery.probe-timeout-seconds", "ai.connect-seconds",
        "ai.recovery.timeout-seconds", "ai.recovery.max-tokens", "ai.deletion.timeout-seconds", "ai.deletion.max-tokens",
        "ai.file.timeout-seconds", "ai.file.max-tokens", "logs.emergency-records", "mod.auto-load", "mod.store.base-url");

    /** Prevents instances of the static YAML loader. / 禁止创建静态 YAML 加载器实例。 */
    private ConfigLoader() {
    }

    /** Lists all leaf keys for documentation verification. / 列出文档核对用全部叶字段。
     * @return immutable key set / 不可变字段集合 */
    public static Set<String> keys() {
        return KEYS;
    }

    /** Loads the module resource and closes its stream before returning. / 加载模块资源并在返回前关闭流。
     * @param mode class, jar or app / class、jar或app
     * @return immutable effective configuration / 不可变生效配置
     * @throws IOException if the resource is missing or invalid / 资源缺失或无效时 */
    public static AppConfig load(String mode) throws IOException {
        try (var input = ConfigLoader.class.getResourceAsStream(RESOURCE)) {
            return load(input, mode);
        }
    }

    /** Loads and freezes one bounded YAML stream; the caller owns its lifetime. / 加载并冻结有界 YAML 流；调用方负责流寿命。
     * @param input YAML resource stream / YAML 资源流
     * @param mode class, jar or app / class、jar或app
     * @return immutable effective configuration / 不可变生效配置
     * @throws IOException if loading or validation fails / 读取或校验失败时 */
    public static AppConfig load(InputStream input, String mode) throws IOException {
        if (!MODES.contains(mode)) throw new IOException("Unknown configuration mode: " + mode);
        if (input == null) throw new IOException("Configuration resource is missing: " + RESOURCE);
        byte[] bytes = input.readNBytes(262145);
        if (bytes.length > 262144) throw new IOException("Configuration resource exceeds 256 KiB: " + RESOURCE);
        var factory = YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        var mapper = new ObjectMapper(factory).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode root = mapper.readTree(bytes);
        if (root == null || !root.isObject()) throw new IOException("Configuration root must be a mapping");
        var sections = new LinkedHashMap<String, Map<String, JsonNode>>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getKey().equals("common") && !MODES.contains(entry.getKey()))
                throw new IOException("Unknown section: " + entry.getKey());
            var flattened = new LinkedHashMap<String, JsonNode>();
            flatten(entry.getValue(), "", flattened);
            if (!entry.getKey().equals("class") && flattened.containsKey("mod.auto-load"))
                throw new IOException("mod.auto-load is only allowed in the class section");
            sections.put(entry.getKey(), flattened);
        }
        var values = new LinkedHashMap<>(sections.getOrDefault("common", Map.of()));
        values.putAll(sections.getOrDefault(mode, Map.of()));
        var required = new java.util.TreeSet<>(KEYS);
        if (!mode.equals("class")) required.remove("mod.auto-load");
        if (!values.keySet().equals(required)) {
            var missing = new java.util.TreeSet<>(required);
            missing.removeAll(values.keySet());
            throw new IOException("Missing effective configuration fields: " + missing);
        }
        try {
            String directory = string(values, "database.data-directory");
            if (directory.isBlank() || directory.contains(";") || directory.indexOf('\0') >= 0)
                throw new IOException("Invalid database.data-directory");
            Path.of(directory);
            String locale = choice(values, "defaults.locale", Set.of("system", "zh-CN", "en-US"));
            String theme = choice(values, "defaults.theme", Set.of("SYSTEM", "LIGHT", "DARK"));
            Optional<URI> store = storeUrl(string(values, "mod.store.base-url"));
            return new AppConfig(directory,
                new AppConfig.Ui(bool(values, "ui.navigation-collapsed"), bool(values, "ui.advanced-expanded")),
                new AppConfig.Defaults(locale, theme, number(values, "defaults.connect-seconds", 86400), number(values, "defaults.idle-seconds", 86400), bool(values, "defaults.delete-ai-explanation")),
                new AppConfig.Ssh(number(values, "ssh.heartbeat-seconds", 86400), number(values, "ssh.recovery.observation-seconds", 86400),
                    number(values, "ssh.recovery.probe-interval-seconds", 86400), number(values, "ssh.recovery.probe-timeout-seconds", 86400)),
                new AppConfig.Ai(number(values, "ai.connect-seconds", 86400), budget(values, "recovery"), budget(values, "deletion"), budget(values, "file")),
                number(values, "logs.emergency-records", 100), !mode.equals("class") || bool(values, "mod.auto-load"), store);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid configuration value: " + invalid.getMessage(), invalid);
        }
    }

    /** Validates the configured store root, including the numeric loopback test boundary. / 校验商店根地址，包含数字回环测试边界。
     * @param address exact configured text or empty / 精确配置文本或空字符串
     * @return validated root or no configured store / 已校验根地址或未配置商店
     * @throws IOException if the root is malformed or outside the transport boundary / 根地址无效或越过传输边界时 */
    public static Optional<URI> storeUrl(String address) throws IOException {
        if (address.isEmpty()) return Optional.empty();
        try {
            URI uri = URI.create(address);
            boolean local = "http".equals(uri.getScheme())
                && uri.getHost() != null
                && Set.of("127.0.0.1", "[::1]").contains(uri.getHost());
            if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IOException("mod.store.base-url requires HTTPS or numeric loopback HTTP without credentials, query or fragment");
            return Optional.of(uri);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid mod.store.base-url", invalid);
        }
    }

    /** Flattens supported YAML fields and rejects unknown or container-valued leaves. / 展平支持的 YAML 字段并拒绝未知字段或容器叶值。
     * @param node current section mapping / 当前分区映射
     * @param prefix current dotted parent path / 当前点分父路径
     * @param target validated scalar field collection / 已校验标量字段集合
     * @throws IOException if structure or a field is invalid / 结构或字段无效时 */
    private static void flatten(JsonNode node, String prefix, Map<String, JsonNode> target) throws IOException {
        if (!node.isObject()) throw new IOException("Configuration section must be a mapping: " + prefix);
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (KEYS.contains(key)) {
                if (entry.getValue().isContainerNode() || entry.getValue().isNull())
                    throw new IOException("Expected scalar: " + key);
                target.put(key, entry.getValue());
            } else if (KEYS.stream().anyMatch(known -> known.startsWith(key + ".")))
                flatten(entry.getValue(), key, target);
            else throw new IOException("Unknown configuration field: " + key);
        }
    }

    /** Requires a textual scalar for one effective field. / 要求生效字段为文本标量。
     * @param values merged effective fields / 合并后生效字段
     * @param key supported field key / 支持的字段键
     * @return exact text value / 精确文本值
     * @throws IOException if the value is not textual / 值不是文本时 */
    private static String string(Map<String, JsonNode> values, String key) throws IOException {
        var node = values.get(key);
        if (!node.isTextual()) throw new IOException("Expected string: " + key);
        return node.textValue();
    }

    /** Requires a boolean scalar for one effective field. / 要求生效字段为布尔标量。
     * @param values merged effective fields / 合并后生效字段
     * @param key supported field key / 支持的字段键
     * @return configured boolean / 配置布尔值
     * @throws IOException if the value is not boolean / 值不是布尔时 */
    private static boolean bool(Map<String, JsonNode> values, String key) throws IOException {
        if (!values.get(key).isBoolean()) throw new IOException("Expected boolean: " + key);
        return values.get(key).booleanValue();
    }

    /** Requires an integer within the field-specific positive bound. / 要求整数位于字段规定的正数界限内。
     * @param values merged effective fields / 合并后生效字段
     * @param key supported field key / 支持的字段键
     * @param maximum inclusive positive upper bound / 含端点正数上限
     * @return validated integer / 已校验整数
     * @throws IOException if type or range is invalid / 类型或范围无效时 */
    private static int number(Map<String, JsonNode> values, String key, int maximum) throws IOException {
        var node = values.get(key);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > maximum)
            throw new IOException("Expected integer 1.." + maximum + ": " + key);
        return node.intValue();
    }

    /** Requires a supported textual choice without normalization. / 要求支持的文本选项，不作归一化。
     * @param values merged effective fields / 合并后生效字段
     * @param key supported field key / 支持的字段键
     * @param allowed exact accepted values / 精确接受值
     * @return confirmed choice / 已确认选项
     * @throws IOException if type or choice is invalid / 类型或选项无效时 */
    private static String choice(Map<String, JsonNode> values, String key, Set<String> allowed) throws IOException {
        String value = string(values, key);
        if (!allowed.contains(value)) throw new IOException("Expected " + allowed + ": " + key);
        return value;
    }

    /** Reads the timeout and token bounds for one model purpose. / 读取一个模型用途的超时及 token 界限。
     * @param values merged effective fields / 合并后生效字段
     * @param name recovery, deletion or file purpose / 救援、删除或文件用途
     * @return immutable request budget / 不可变请求预算
     * @throws IOException if either bound is invalid / 任一界限无效时 */
    private static AppConfig.Budget budget(Map<String, JsonNode> values, String name) throws IOException {
        return new AppConfig.Budget(number(values, "ai." + name + ".timeout-seconds", 86400), number(values, "ai." + name + ".max-tokens", Integer.MAX_VALUE));
    }
}
