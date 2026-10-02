package gold.debug.windowstolinux.appapi.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Exact platform file intents for ordinary and pre-authorized operations. / 普通及前置授权文件操作的精确平台意图。 */
public final class FileActions {
    private FileActions() { }
    /** Binds deletion to its object, link chain and recoverability. / 将删除绑定到对象、解析链和可恢复性。 */
    public static ActionIntent delete(FileInfo info, boolean link, DeleteEffect effect) {
        String action = "delete " + (link ? "Link" : "File") + " " + effect + "\n" + stamp(info)
            + "\nrequested=" + Base64.getUrlEncoder().withoutPadding().encodeToString(info.requested().key().getBytes(StandardCharsets.UTF_8));
        String description = (effect == DeleteEffect.RECYCLE ? "回收" : "永久删除（不可恢复）")
            + (link ? "链接本身：" : "实际对象：") + info.actual().path()
            + "\n输入位置：" + info.requested().path() + "\n对象类型：" + info.kind()
            + info.target().map(t -> "\n链接指向：" + t).orElse("");
        if (!info.links().isEmpty()) description += "\n经过的链接：" + info.links().stream().map(l -> l.path().path() + " → " + l.target()).toList();
        if (description.length() > 16384) throw new IllegalArgumentException("Deletion description exceeds the approval limit");
        return intent("file-delete", info, "file-v1", action,
            description);
    }
    /** Builds a bounded identity-sensitive operation intent. / 构造有界且对身份敏感的操作意图。 */
    public static ActionIntent intent(String type, FileInfo info, String revision, String exact, String summary) {
        List<String> servers = info.actual().server().map(s -> List.of(s.id())).orElseGet(List::of);
        return new ActionIntent(type, servers, info.actual().key(), revision, exact, summary);
    }
    /** Returns the stable full identity stamp used by approval. / 返回审批使用的完整稳定身份摘要。 */
    public static String stamp(FileInfo info) {
        try {
            String value = info.requested().key() + "\n" + info.actual().key() + "\n" + info.kind() + "\n" + info.identity() + "\n" + info.target() + "\n" + info.links();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
