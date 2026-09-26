package gold.debug.windowstolinux.app.db.mod;

import java.sql.*;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

/** Shares strict navigation persistence between user choices and whole-Mod cleanup. / 在用户选择及整 Mod 清理间共用严格导航持久化。 */
public final class ModNavigationPreferences {
    /** Strict persisted preference decoder. / 严格持久偏好解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Prevents construction of this transaction helper. / 禁止构造此事务助手。 */
    private ModNavigationPreferences() {
    }

    /** Stable navigation preference without author objects or runtime authority. / 不含作者对象及运行权限的稳定导航偏好。
     * @param ownerId exact registered owner / 精确登记所有者
     * @param pageId exact owner-local page identity / 精确所有者内部页面身份 */
    public record PagePin(String ownerId, String pageId) {
        /** Requires bounded literal identities, never paths or concatenated keys. / 要求有界原样身份，不使用路径或拼接键。
         * @param ownerId exact owner / 精确所有者
         * @param pageId local page / 内部页面 */
        public PagePin {
            if (ownerId == null || ownerId.isBlank() || ownerId.length() > 512 || ownerId.indexOf('\0') >= 0
                    || pageId == null || pageId.isBlank() || pageId.length() > 1024 || pageId.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid navigation identity");
        }
    }

    /** Decodes the original bounded preferences and rejects corruption without replacing it. / 解码原有界偏好，拒绝损坏且不替换。
     * @param c original serialized transaction / 原串行事务
     * @return exact ordered identities / 精确有序身份
     * @throws SQLException if retained data is invalid / 保留数据无效时 */
    public static List<PagePin> read(Connection c) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT preference_value FROM desktop_preference WHERE preference_key='mod_navigation_pins'");
                var r = p.executeQuery()) {
            if (!r.next())
                return List.of();
            String stored = r.getString(1);
            try {
                if (stored.length() > 1024 * 1024)
                    throw new IllegalArgumentException("Oversized pins");
                var node = JSON.readTree(stored);
                if (node == null || !node.isObject() || node.size() != 2 || !node.path("formatVersion").isInt()
                        || node.path("formatVersion").asInt() != 1 || !node.path("pages").isArray()
                        || node.path("pages").size() > 1024)
                    throw new IllegalArgumentException("Invalid pins");
                var pins = new LinkedHashSet<PagePin>();
                for (var page : node.path("pages")) {
                    if (!page.isObject() || page.size() != 2 || !page.path("ownerId").isTextual()
                            || !page.path("pageId").isTextual() || !pins.add(
                                    new PagePin(page.path("ownerId").textValue(), page.path("pageId").textValue())))
                        throw new IllegalArgumentException("Invalid pin identity");
                }
                return List.copyOf(pins);
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                throw new SQLException("Stored navigation preferences cannot be verified", "MOD_NAVIGATION_INVALID");
            }
        }
    }

    /** Writes validated identities in the caller's existing admission transaction. / 在调用方既有准入事务写入已校验身份。
     * @param c serialized platform transaction / 串行平台事务
     * @param pins complete retained order / 完整保留顺序
     * @throws SQLException if values exceed bounds or persistence fails / 值超限或持久化失败时 */
    public static void write(Connection c, List<PagePin> pins) throws SQLException {
        if (c.getAutoCommit() || pins.size() > 1024 || pins.stream().anyMatch(Objects::isNull)
                || new HashSet<>(pins).size() != pins.size())
            throw new SQLException("Invalid navigation write", "MOD_NAVIGATION_FULL");
        var node = JSON.createObjectNode().put("formatVersion", 1);
        node.set("pages", JSON.valueToTree(pins));
        if (node.toString().length() > 1024 * 1024)
            throw new SQLException("Navigation preference limit reached", "MOD_NAVIGATION_FULL");
        try (var p = c.prepareStatement(
                "MERGE INTO desktop_preference(preference_key,preference_value) KEY(preference_key) VALUES('mod_navigation_pins',?)")) {
            p.setString(1, node.toString());
            p.executeUpdate();
        }
    }

    /** Removes only one exact owner's entries and preserves every other entry's order. / 仅移除一个精确所有者条目，保留其他条目顺序。
     * @param c serialized platform transaction / 串行平台事务
     * @param owner exact deleted owner / 精确被删所有者
     * @throws SQLException if retained preferences are corrupt or saving fails / 保留偏好损坏或保存失败时 */
    public static void removeOwner(Connection c, String owner) throws SQLException {
        var before = read(c);
        var after = before.stream().filter(pin -> !pin.ownerId().equals(owner)).toList();
        if (!before.equals(after))
            write(c, after);
    }
}
