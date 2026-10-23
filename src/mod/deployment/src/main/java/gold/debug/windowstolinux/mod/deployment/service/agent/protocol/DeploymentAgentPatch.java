package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.util.*;

/** Revision-bound structured patch for task-owned source only. / 仅用于任务所属源码、与修订绑定的结构化补丁。
 * @param path protected relative source / 受保护相对源码
 * @param beforeDigest exact prior file digest / 精确原文件摘要
 * @param sourceRevision current protected source / 当前受保护源码
 * @param edits bounded exact line replacements / 有界精确行替换 */
public record DeploymentAgentPatch(String path, String beforeDigest, String sourceRevision, List<Edit> edits) {
    /** One bounded exact line replacement. / 一项有界精确行替换。
     * @param line one-based original line / 从一开始的原行号
     * @param removed exact old lines / 精确旧行
     * @param added exact new lines / 精确新行 */
    public record Edit(int line, List<String> removed, List<String> added) {
        /** Copies bounded edit content. / 复制有界编辑内容。
         * @param line original line / 原行号
         * @param removed old lines / 旧行
         * @param added new lines / 新行 */
        public Edit {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
            if (line < 1 || line > 1000000 || removed.size() + added.size() > 200
                || removed.isEmpty() && added.isEmpty())
                throw new IllegalArgumentException("Invalid patch edit range");
            if (removed.stream().anyMatch(v -> v.length() > 4000 || v.indexOf('\0') >= 0)
                || added.stream().anyMatch(v -> v.length() > 4000 || v.indexOf('\0') >= 0))
                throw new IllegalArgumentException("Patch line exceeds budget");
        }
    }

    /** Validates exact revision and protected path. / 校验精确修订及受保护路径。
     * @param path relative source path / 相对源码路径
     * @param beforeDigest old file digest / 旧文件摘要
     * @param sourceRevision current source digest / 当前来源摘要
     * @param edits bounded structured edits / 有界结构化编辑 */
    public DeploymentAgentPatch {
        if (!Objects.requireNonNull(path).matches("[\\p{L}\\p{N}_. /-]{1,500}") || path.startsWith("/")
            || path.contains("..") || path.contains("//"))
            throw new IllegalArgumentException("Invalid patch path");
        if (!beforeDigest.matches("[0-9a-f]{64}") || !sourceRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid patch revision");
        edits = List.copyOf(edits);
        if (edits.isEmpty() || edits.size() > 64 || edits.toString().length() > 60000)
            throw new IllegalArgumentException("Patch exceeds budget");
    }
}
