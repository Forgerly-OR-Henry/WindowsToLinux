package gold.debug.windowstolinux.web.service.persistence.serialization;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Provides bounded strict JSON without polymorphic class loading or browser-controlled type names.
 * <p>提供有界严格 JSON，不允许多态类加载或浏览器控制类型名。
 */
public final class WebJsonCodec {
    /**
     * Shares strict JSON parsing with depth 40, string length 1,048,576 and number length 100 limits, duplicate detection and trailing-token rejection.
     * <p>共享严格 JSON 解析器，限制深度为 40、字符串长度为 1,048,576、数字长度为 100，并拒绝重复键和尾随内容。
     */
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(1_048_576).maxNumberLength(100).build()).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private WebJsonCodec() { }
    /**
     * Parses request JSON with the shared bounded mapper and translates parser failures to a safe request error.
     * <p>使用共享有界映射器解析请求 JSON，并将解析失败转换为安全请求错误。
     *
     * @param json JSON serialization / JSON 序列化
     * @return the parsed JSON tree using Jackson's tree semantics / 按 Jackson 树模型语义解析得到的 JSON 树
     * @throws IllegalArgumentException if the JSON is invalid or violates the configured parser limits / JSON 无效或违反已配置的解析限制时
     */
    public static JsonNode read(String json) {
        try { return MAPPER.readTree(json); } catch (JacksonException failure) { throw new IllegalArgumentException("Invalid JSON request"); }
    }
    /**
     * Serializes a Web contract value with the shared mapper and translates serialization failures to a safe error.
     * <p>使用共享映射器序列化 Web 契约值，并将序列化失败转换为安全错误。
     *
     * @param value contract value to serialize / 待序列化的契约值
     * @return JSON text, with dates represented without numeric timestamps / JSON 文本，日期不使用数字时间戳表示
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); } catch (JacksonException failure) { throw new IllegalArgumentException("Invalid JSON value"); }
    }
    /**
     * Creates an empty mutable JSON object for the caller to populate.
     * <p>创建空的可变 JSON 对象，供调用方填充。
     *
     * @return a new empty object node owned by the caller / 调用方持有的新建空对象节点
     */
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    /**
     * Exposes the shared mapper for integration with Web serialization boundaries.
     * <p>提供共享映射器，供 Web 序列化边界集成使用。
     *
     * @return the shared preconfigured mapper / 已预配置的共享映射器
     */
    public static JsonMapper mapper() { return MAPPER; }
    /**
     * Converts a supported value to a JSON tree using the shared Web mapper.
     * <p>使用共享 Web 映射器将受支持值转换为 JSON 树。
     *
     * @param value supported Java value to represent as a tree / 待表示为树的受支持 Java 值
     * @return the corresponding JSON tree, including a JSON null node for a null value / 对应 JSON 树，null 值对应 JSON null 节点
     */
    public static JsonNode tree(Object value) { return MAPPER.valueToTree(value); }
    /**
     * Binds a JSON tree to the requested Java contract type using the shared Web mapper.
     * <p>使用共享 Web 映射器将 JSON 树绑定到请求的 Java 契约类型。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param value JSON tree to bind / 待绑定的 JSON 树
     * @param type caller-selected target Java type / 调用方指定的目标 Java 类型
     * @return the value converted to the requested type according to Jackson binding rules / 按 Jackson 绑定规则转换为目标类型的值
     */
    public static <T> T convert(JsonNode value, Class<T> type) { return MAPPER.convertValue(value, type); }
}
