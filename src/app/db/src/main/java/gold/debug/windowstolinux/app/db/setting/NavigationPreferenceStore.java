package gold.debug.windowstolinux.app.db.setting;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;

/** Transactional platform entrance order in desktop_preference. / desktop_preference 中的平台入口事务顺序。 */
public final class NavigationPreferenceStore {
    /** Desktop preference key for the platform entrance order. / 平台入口顺序的桌面偏好键。 */
    private static final String KEY = "platform_navigation_order";

    /** Strict parser rejecting duplicate keys and trailing tokens. / 拒绝重复字段和尾随内容的严格解析器。 */
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Platform transaction and connection owner. / 平台事务及连接所有者。 */
    private final StoreConnections connections;

    /** Existing desktop preference storage. / 既有桌面偏好存储。 */
    private final DesktopPreferenceStore preferences;

    /** Binds platform-owned transactions. / 绑定平台所属事务。
     * @param connections scoped connections / 限定连接 */
    public NavigationPreferenceStore(StoreConnections connections) {
        this.connections = Objects.requireNonNull(connections);
        preferences = new DesktopPreferenceStore(connections);
    }

    /** Reads retained identities and appends newly available defaults. / 读取保留身份并追加新出现的默认入口。
     * @param defaults current default identities / 当前默认身份
     * @return immutable complete order including hidden entries / 包含隐藏入口的不可变完整顺序
     * @throws SQLException if stored content is corrupt / 已存内容损坏时 */
    public List<String> read(List<String> defaults) throws SQLException {
        validate(defaults);
        var saved = preferences.find(KEY);
        var order = new ArrayList<String>();
        if (saved.isPresent()) {
            try {
                String text = saved.orElseThrow();
                if (text.length() > 65536) throw new IllegalArgumentException("Navigation preference is too large");
                var node = JSON.readTree(text);
                if (!node.isObject() || node.size() != 2 || node.path("formatVersion").asInt(-1) != 1
                    || !node.path("formatVersion").isIntegralNumber() || !node.path("formatVersion").canConvertToInt()
                    || !node.path("entries").isArray())
                    throw new IllegalArgumentException("Invalid navigation preference envelope");
                for (var id : node.path("entries")) {
                    if (!id.isTextual()) throw new IllegalArgumentException("Invalid navigation identity");
                    order.add(id.textValue());
                }
                validate(order);
            } catch (Exception failure) {
                throw new SQLException("Invalid platform navigation preference; original value retained", failure);
            }
        }
        for (String id : defaults) if (!order.contains(id)) order.add(id);
        return List.copyOf(order);
    }

    /** Reorders an exact visible subset while retaining hidden positions. / 重排精确可见子集并保留隐藏位置。
     * @param defaults default visible identities / 默认可见身份
     * @param expected complete last confirmed order / 上次确认的完整顺序
     * @param visible requested visible permutation / 请求的可见排列
     * @return committed complete order / 已提交完整顺序
     * @throws SQLException if the snapshot changed or persistence fails / 快照变化或持久化失败时 */
    public List<String> reorder(List<String> defaults, List<String> expected, List<String> visible) throws SQLException {
        validate(visible);
        if (!new HashSet<>(defaults).equals(new HashSet<>(visible)))
            throw new SQLException("Visible navigation identities changed", "40001");
        var result = new ArrayList<String>();
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
            }
            var before = read(defaults);
            if (!before.equals(expected))
                throw new SQLException("Navigation preference changed; reload required", "40001");
            result.addAll(reorderVisible(before, visible));
            try {
                var node = JSON.createObjectNode().put("formatVersion", 1);
                var entries = node.putArray("entries");
                result.forEach(entries::add);
                preferences.save(KEY, JSON.writeValueAsString(node));
            } catch (java.io.IOException failure) {
                throw new SQLException("Cannot encode navigation order", failure);
            }
        });
        return List.copyOf(result);
    }

    /** Permutes only occupied visible slots. / 仅置换可见项占据的位置。
     * @param original complete original order / 完整原顺序
     * @param visible exact visible permutation / 精确可见排列
     * @param <T> stable identity type / 稳定身份类型
     * @return immutable reordered identities / 不可变重排身份 */
    public static <T> List<T> reorderVisible(List<T> original, List<T> visible) {
        var selected = new HashSet<>(visible);
        if (selected.size() != visible.size() || !original.containsAll(visible))
            throw new IllegalArgumentException("Visible order must be a distinct original subset");
        var result = new ArrayList<>(original);
        int next = 0;
        for (int index = 0; index < result.size(); index++)
            if (selected.contains(result.get(index))) result.set(index, visible.get(next++));
        return List.copyOf(result);
    }

    /** Rejects oversized, duplicate or malformed entrance identities. / 拒绝过多、重复或格式错误的入口身份。
     * @param ids complete identity sequence / 完整身份序列 */
    private static void validate(List<String> ids) {
        if (ids.size() > 1024 || new HashSet<>(ids).size() != ids.size()
            || ids.stream().anyMatch(id -> id == null || !id.matches("[a-z][a-z0-9_.-]{0,127}")))
            throw new IllegalArgumentException("Invalid or duplicate navigation identity");
    }
}
