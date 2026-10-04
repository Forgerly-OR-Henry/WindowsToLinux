package gold.debug.windowstolinux.appapi.file.action;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;

/** Exact platform file intents for ordinary and pre-authorized operations. / 普通及前置授权文件操作的精确平台意图。 */
public final class FileActions {
    /** Prevents construction of the exact-intent utility. / 禁止构造精确意图工具。
     */
    private FileActions() {
    }

    /** Binds deletion to its object, link chain and recoverability. / 将删除绑定到对象、解析链和可恢复性。
     * @param info exact observed object and link chain / 精确观测对象及链接链
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param effect exact recoverability approved for deletion / 删除获批的精确可恢复性
     * @return canonical deletion intent binding File or Link semantics, full observation and recovery effect / 绑定 File 或 Link 语义、完整观测及恢复效果的规范删除意图
     */
    public static ActionIntent delete(FileInfo info, boolean link, DeleteEffect effect) {
        String action = "delete " + (link ? "Link" : "File") + " " + effect + "\n" + stamp(info)
            + "\nrequested=" + Base64.getUrlEncoder().withoutPadding().encodeToString(info.requested().key().getBytes(StandardCharsets.UTF_8));
        String description = (effect == DeleteEffect.RECYCLE ? "Recycle " : "Permanently delete (irreversible) ")
            + (link ? "the Link itself: " : "the actual object: ") + info.actual().path()
            + "\nRequested position: " + info.requested().path() + "\nObject type: " + info.kind()
            + info.target().map(t -> "\nLink target: " + t).orElse("");
        if (!info.links().isEmpty())
            description += "\nResolved links: " + info.links().stream().map(l -> l.path().path() + " -> " + l.target()).toList();
        if (description.length() > 16384)
            throw new IllegalArgumentException("Deletion description exceeds the approval limit");
        return intent("file-delete", info, "file-v1", action,
            description);
    }

    /** Builds a bounded identity-sensitive operation intent. / 构造有界且对身份敏感的操作意图。
     * @param type exact operation category / 精确操作类别
     * @param info exact observed object and link chain / 精确观测对象及链接链
     * @param revision exact reviewed business revision / 精确已审核业务修订
     * @param exact complete immutable effect text / 完整不可变副作用文本
     * @param summary readable exact effect description / 可读精确副作用说明
     * @return immutable intent scoped to the actual object and its observed host / 限定实际对象及其观测主机的不可变意图
     */
    public static ActionIntent intent(String type, FileInfo info, String revision, String exact, String summary) {
        List<String> servers = info.actual().server().map(s -> List.of(s.id())).orElseGet(List::of);
        return new ActionIntent(type, servers, info.actual().key(), revision, exact, summary);
    }

    /** Returns the stable full identity stamp used by approval. / 返回审批使用的完整稳定身份摘要。
     * @param info exact observed object and link chain / 精确观测对象及链接链
     * @return hexadecimal SHA-256 of the requested position, actual identity and complete link observations / 输入位置、实际身份及完整链接观测的十六进制 SHA-256
     */
    public static String stamp(FileInfo info) {
        try {
            String value = info.requested().key() + "\n" + info.actual().key() + "\n" + info.kind() + "\n" + info.identity() + "\n" + info.target() + "\n" + info.links();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
