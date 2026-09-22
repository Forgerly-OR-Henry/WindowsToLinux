package gold.debug.windowstolinux.shared.agent.execution.protocol;

import static gold.debug.windowstolinux.shared.ai.execution.protocol.StrictJson.*;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.config.contract.definition.*;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

/** Strict nonsecret deployment inputs, using existing public storage contracts. / 使用既有公共存储契约的严格非秘密部署输入。 */
final class AgentDeliveryInputs {
    /** Prevents construction. / 禁止实例化。 */
    private AgentDeliveryInputs() {
    }

    /** Reads explicit platform-owned persistent resources. / 读取显式平台所属持久资源。
     * @param node resource array / 资源数组
     * @return validated resource bindings / 已校验资源绑定
     */
    static ManagedComponentResourceBindings resources(JsonNode node) {
        boundedArray(node, 32);
        var files = new ArrayList<ManagedFileBinding>();
        for (var item : node) {
            exact(item, Set.of("id", "path", "access", "schema", "reversible", "type", "seedFile", "digest"));
            if (!item.get("reversible").isBoolean())
                throw new IllegalArgumentException("reversible must be boolean");
            var path = new ComponentDataPath(text(item, "path"),
                    ComponentDataPath.AccessMode.valueOf(text(item, "access")), text(item, "schema"),
                    item.get("reversible").booleanValue());
            var kind = ManagedStorageLocation.StorageResourceType.valueOf(text(item, "type"));
            files.add(new ManagedFileBinding(text(item, "id"), path, ManagedStorageLocation.defaults(), kind,
                    text(item, "seedFile"), text(item, "digest")));
        }
        return new ManagedComponentResourceBindings(files, Optional.of(List.of()));
    }

    /** Reads type-checked runtime values; credentials must use a separate user secret workflow. / 读取类型化运行值，凭据必须经独立用户秘密流程。
     * @param node nonsecret entries / 非秘密条目
     * @return immutable runtime entries / 不可变运行条目
     */
    static List<ConfigurationEntry> configuration(JsonNode node) {
        boundedArray(node, 64);
        var entries = new ArrayList<ConfigurationEntry>();
        for (var item : node) {
            exact(item, Set.of("key", "value"));
            String key = text(item, "key");
            var value = item.get("value");
            if (key.matches("(?i).*(password|passwd|secret|token|api_?key|private_?key).*"))
                throw new IllegalArgumentException("secret configuration excluded");
            ConfigurationValue typed;
            if (value.isTextual())
                typed = new ConfigurationValue.Text(value.textValue());
            else if (value.isIntegralNumber() && value.canConvertToLong())
                typed = new ConfigurationValue.Number(value.longValue());
            else if (value.isBoolean())
                typed = new ConfigurationValue.Flag(value.booleanValue());
            else
                throw new IllegalArgumentException("literal configuration value required");
            entries.add(new ConfigurationEntry(key, ConfigurationScope.RUNTIME, typed));
        }
        return List.copyOf(entries);
    }

    /** Rejects absent or oversized arrays. / 拒绝缺失或过大的数组。
     * @param node proposed array / 提议数组
     * @param size item bound / 条目上限
     */
    private static void boundedArray(JsonNode node, int size) {
        if (node == null || !node.isArray() || node.size() > size)
            throw new IllegalArgumentException("bounded explicit input array required");
    }
}
