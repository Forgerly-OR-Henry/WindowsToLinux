package gold.debug.windowstolinux.app.file.model;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.model.FilePath;

/** Exact original link and moved-target evidence retained before file effects. / 文件副作用前保留的精确原链接及移动目标依据。
 * @param link original registered link position / 原登记链接位置
 * @param source original target position before moving / 移动前的原目标位置
 * @param target actual mapped destination / 实际映射目标位置
 * @param linkIdentity original no-follow link identity / 原不跟随链接身份
 * @param targetIdentity original target object identity / 原目标对象身份
 * @param targetText original link target text / 原始链接指向文本 */
public record FileLinkMaintenance(FilePath link, FilePath source, FilePath target, String linkIdentity,
                                  String targetIdentity, String targetText) {
    /** Validates same-host original evidence without granting modification authority. / 校验同主机原依据，不授予修改权限。
     * @param link original registered link position / 原登记链接位置
     * @param source original target position / 原目标位置
     * @param target actual mapped destination / 实际映射目标位置
     * @param linkIdentity original link identity / 原链接身份
     * @param targetIdentity original target identity / 原目标身份
     * @param targetText original link target text / 原始链接指向文本 */
    public FileLinkMaintenance {
        Objects.requireNonNull(link);
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Objects.requireNonNull(linkIdentity);
        Objects.requireNonNull(targetIdentity);
        Objects.requireNonNull(targetText);
        if (!link.server().equals(source.server()) || !link.server().equals(target.server()))
            throw new IllegalArgumentException("Link maintenance requires one host");
    }

    /** Encodes four independent fields without ambiguous path delimiters. / 不使用歧义路径分隔符地编码四个独立字段。
     * @return bounded original link evidence / 有界原链接依据 */
    public String encode() {
        return String.join("\n", encoded(source.path()), encoded(linkIdentity), encoded(targetIdentity), encoded(targetText));
    }

    /** Restores only a dedicated durable maintenance plan entry. / 仅恢复专用持久维护计划条目。
     * @param step original PLANNED_LINK record / 原 PLANNED_LINK 记录
     * @return exact original maintenance evidence / 精确原维护依据 */
    public static FileLinkMaintenance decode(FileOperation.Step step) {
        if (!step.phase().equals("PLANNED_LINK") || step.target().isEmpty())
            throw new IllegalArgumentException("Dedicated link plan required");
        String[] fields = step.detail().split("\n", -1);
        if (fields.length != 4) throw new IllegalArgumentException("Invalid original link plan");
        return new FileLinkMaintenance(step.source(), new FilePath(step.source().server(), decoded(fields[0])), step.target().orElseThrow(), decoded(fields[1]), decoded(fields[2]), decoded(fields[3]));
    }

    /** Encodes one exact UTF-8 field. / 编码单个精确 UTF-8 字段。
     * @param value original field / 原字段
     * @return unpadded base64url field / 无补齐的 base64url 字段 */
    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes one exact UTF-8 field. / 解码单个精确 UTF-8 字段。
     * @param value recorded base64url field / 已记录 base64url 字段
     * @return original field / 原字段 */
    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
