package gold.debug.windowstolinux.app.service.setting;

import java.util.Objects;

/** A stable platform target with optional exact Mod or management subpage. / 稳定平台目标及可选的精确 Mod 或管理子页。
 * @param platformId platform navigation identity / 平台导航身份
 * @param ownerId exact Mod owner or empty / 精确 Mod 所有者或空
 * @param pageId exact Mod page or empty / 精确 Mod 页面或空
 * @param detailId management tab or Mod internal navigation identity, or empty / 管理标签或 Mod 内部导航身份，或空 */
public record StartupPageLocation(String platformId, String ownerId, String pageId, String detailId) {
    /** Validates bounded literal identities without accepting drafts or paths. / 校验有界原样身份，不接收草稿或路径。
     * @param platformId platform identity / 平台身份
     * @param ownerId Mod owner / Mod 所有者
     * @param pageId Mod page / Mod 页面
     * @param detailId management tab or Mod internal navigation / 管理标签或 Mod 内部导航 */
    public StartupPageLocation {
        Objects.requireNonNull(platformId);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(pageId);
        Objects.requireNonNull(detailId);
        if (!platformId.matches("[a-z][a-z0-9_.-]{0,127}") || ownerId.length() > 512 || pageId.length() > 1024
            || ownerId.indexOf('\0') >= 0 || pageId.indexOf('\0') >= 0 || ownerId.isEmpty() != pageId.isEmpty()
            || !ownerId.isEmpty() && (ownerId.isBlank() || pageId.isBlank()
            || !java.util.Set.of("shell.workspace", "platform.modSettings").contains(platformId))
            || !detailId.isEmpty() && (!(platformId.equals("shell.mods")
            || platformId.equals("shell.workspace") && !ownerId.isEmpty()) || !detailId.matches("[a-z][a-z0-9_.-]{0,127}")))
            throw new IllegalArgumentException("Invalid startup page identity");
    }

    /** Creates a platform-only target. / 创建仅平台目标。
     * @param id platform identity / 平台身份
     * @return stable platform target / 稳定平台目标 */
    public static StartupPageLocation platform(String id) {
        return new StartupPageLocation(id, "", "", "");
    }
}
