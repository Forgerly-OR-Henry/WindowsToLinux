package gold.debug.windowstolinux.appapi.task.action;

import java.util.Objects;

/** Resolved business target on an admitted server, not a localized label or button name. / 准入服务器上已解析的业务目标，不是本地化标签或按钮名称。
 * @param serverId exact saved server identity / 精确已保存服务器身份
 * @param kind stable target category such as application / 稳定目标类别，例如 application
 * @param id exact resolved business identity / 精确已解析业务身份 */
public record ActionTarget(String serverId, String kind, String id) {
    /** Validates explicit target identities without inferring containment from text. / 校验明确目标身份，不从文本推断包含关系。
     * @param serverId exact server identity / 精确服务器身份
     * @param kind stable category / 稳定类别
     * @param id resolved business identity / 已解析业务身份 */
    public ActionTarget {
        Objects.requireNonNull(serverId, "serverId");
        if (kind == null || !kind.matches("[a-z][a-z0-9_.-]{0,127}") || id == null || id.isBlank() || id.length() > 4096
            || id.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid resolved action target");
    }
}
