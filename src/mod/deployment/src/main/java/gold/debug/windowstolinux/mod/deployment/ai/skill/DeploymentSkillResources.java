package gold.debug.windowstolinux.mod.deployment.ai.skill;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Loads immutable, bounded instructions from the owning Mod classloader. / 从所属 Mod 类加载器加载不可变有界说明。 */
public final class DeploymentSkillResources {
    /** Per-owner resource cache, without external paths or hot reload. / 按所属类缓存资源，无外部路径或热更新。 */
    private static final ClassValue<Map<String, String>> TEXT = new ClassValue<>() {
        /** Creates one resource cache per owning class. / 为每个所属类建立资源缓存。
         * @param type owning class / 所属类
         * @return initially empty cache / 初始空缓存 */
        @Override
        protected Map<String, String> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /** Parsed named descriptions remain bound to the same owner. / 已解析具名说明保持绑定同一所属类。 */
    private static final ClassValue<Map<String, Map<String, String>>> CATALOG = new ClassValue<>() {
        /** Creates one catalog cache per owning class. / 为每个所属类建立目录缓存。
         * @param type owning class / 所属类
         * @return initially empty cache / 初始空缓存 */
        @Override
        protected Map<String, Map<String, String>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /** Prevents instances. / 禁止实例化。 */
    private DeploymentSkillResources() {
    }

    /** Reads an exact UTF-8 instruction, retaining meaningful whitespace. / 读取精确 UTF-8 说明，保留有意义空白。
     * @param owner resource-owning class / 资源所属类
     * @param name stable local resource name / 稳定本地资源名
     * @return cached immutable instruction / 缓存的不可变说明 */
    public static String text(Class<?> owner, String name) {
        validate(owner, name, "md");
        return TEXT.get(owner).computeIfAbsent(name, key -> read(owner, key));
    }

    /** Reads a required named tool description. / 读取必需的具名工具说明。
     * @param owner resource-owning class / 资源所属类
     * @param name stable properties resource / 稳定 properties 资源
     * @param key exact fixed description key / 精确固定说明键
     * @return checked tool description / 已检查工具说明 */
    public static String description(Class<?> owner, String name, String key) {
        validate(owner, name, "properties");
        String value = catalog(owner, name).get(key);
        if (value == null)
            throw new IllegalStateException("Missing Skill description: " + owner.getName() + "/" + name + ":" + key);
        return value;
    }

    /** Validates every offered resource before entering its AI flow. / 进入对应 AI 流程前校验全部所需资源。
     * @param owner resource-owning class / 资源所属类
     * @param names fixed resource names / 固定资源名称 */
    public static void require(Class<?> owner, String... names) {
        for (String name : names) {
            if (name.endsWith(".properties")) {
                validate(owner, name, "properties");
                catalog(owner, name);
            } else text(owner, name);
        }
    }

    /** Checks the complete offered catalog before workflow effects. / 流程效果前检查完整提供的目录。
     * @param owner resource-owning class / 资源所属类
     * @param name stable properties resource / 稳定 properties 资源
     * @param keys required tool identities / 必需工具身份 */
    public static void requireDescriptions(Class<?> owner, String name, String... keys) {
        for (String key : keys) description(owner, name, key);
    }

    /** Restricts reads to fixed resources beside the owning class. / 限制读取所属类旁的固定资源。
     * @param owner owning Mod class / 所属 Mod 类
     * @param name requested resource / 请求资源
     * @param extension required format / 必需格式 */
    private static void validate(Class<?> owner, String name, String extension) {
        if (owner == null || !owner.getName().startsWith("gold.debug.windowstolinux.mod.deployment.")
            || name == null || !name.matches("[a-z][a-z0-9-]*\\." + extension))
            throw new IllegalArgumentException("Invalid bundled Skill resource identity");
    }

    /** Rejects missing, oversized, malformed or empty bundled text. / 拒绝缺失、超限、编码非法或空的随包文本。
     * @param owner resource owner / 资源所属类
     * @param name local resource name / 本地资源名称
     * @return strict decoded text / 严格解码文本 */
    private static String read(Class<?> owner, String name) {
        try (var input = owner.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing bundled resource");
            byte[] bytes = input.readNBytes(65_537);
            if (bytes.length > 65_536) throw new IOException("Skill exceeds 64 KiB");
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.isBlank() || value.indexOf('\0') >= 0 || value.startsWith("\uFEFF"))
                throw new IOException("Invalid instruction text");
            return value;
        } catch (IOException invalid) {
            throw new IllegalStateException("Invalid bundled Skill: " + owner.getName() + "/" + name, invalid);
        }
    }

    /** Parses required nonempty descriptions and rejects duplicate keys. / 解析必需非空说明并拒绝重复键。
     * @param owner resource owner / 资源所属类
     * @param name catalog resource / 目录资源
     * @return immutable descriptions / 不可变说明 */
    private static Map<String, String> catalog(Class<?> owner, String name) {
        return CATALOG.get(owner).computeIfAbsent(name, key -> {
            var properties = new Properties() {
                /** Rejects duplicate parsed description identities. / 拒绝重复解析的说明身份。
                 * @param entry description key / 说明键
                 * @param value description text / 说明正文
                 * @return previous value, always absent / 前值，始终不存在 */
                @Override
                public synchronized Object put(Object entry, Object value) {
                    if (containsKey(entry)) throw new IllegalArgumentException("Duplicate description key");
                    return super.put(entry, value);
                }
            };
            try {
                properties.load(new StringReader(read(owner, key)));
                var values = new java.util.TreeMap<String, String>();
                for (String entry : properties.stringPropertyNames()) {
                    String value = properties.getProperty(entry);
                    if (entry.isBlank() || value.isBlank()) throw new IllegalArgumentException("Empty description");
                    values.put(entry, value);
                }
                if (values.isEmpty()) throw new IllegalArgumentException("Empty description catalog");
                return Map.copyOf(values);
            } catch (IOException | IllegalArgumentException invalid) {
                throw new IllegalStateException("Invalid Skill catalog: " + owner.getName() + "/" + name, invalid);
            }
        });
    }
}
