package gold.debug.windowstolinux.shared.linux.workspace;

import java.util.*;

import gold.debug.windowstolinux.shared.model.agent.AgentAction;

/** Revision-bound explicit line edits to one Linux task file. / 绑定修订并作用于一个 Linux 任务文件的显式行编辑。
 * @param path relative source path / 相对源码路径
 * @param beforeDigest required pre-edit file digest / 编辑前必需的文件摘要
 * @param sourceRevision current remote source revision / 当前远端源码修订
 * @param edits sorted nonoverlapping line edits / 排序且不重叠的行编辑
 */
public record RemoteSourcePatch(String path, String beforeDigest, String sourceRevision, List<Edit> edits) {
    /** Validates bounded paths and explicit diffs. / 校验有界路径及显式差异。
     * @param path target path / 目标路径
     * @param beforeDigest original file digest / 原文件摘要
     * @param sourceRevision source revision / 源码修订
     * @param edits explicit changes / 显式变更
     */
    public RemoteSourcePatch {
        path = gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand.relative(path, false);
        if (!beforeDigest.matches("[0-9a-f]{64}") || !sourceRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("patch requires exact digests");
        edits = List.copyOf(edits);
        if (edits.isEmpty() || edits.size() > 64)
            throw new IllegalArgumentException("invalid patch size");
        int end = 0;
        int bytes = 0;
        int differenceSize = 0;
        for (var edit : edits) {
            if (edit.line() <= end)
                throw new IllegalArgumentException("overlapping patch edits");
            end = edit.line() + Math.max(1, edit.removed().size()) - 1;
            for (String line : java.util.stream.Stream.concat(edit.removed().stream(), edit.added().stream())
                    .toList()) {
                bytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                differenceSize += path.length() + 12 + line.length();
            }
        }
        if (bytes > 24000 || differenceSize > 24000)
            throw new IllegalArgumentException("patch exceeds review budget");
    }

    /** Returns every changed line for exact human confirmation. / 返回每一变更行，用于精确人工确认。
     * @return deterministic diff lines / 确定性差异行
     */
    public List<String> difference() {
        var lines = new ArrayList<String>();
        for (var edit : edits) {
            for (int i = 0; i < edit.removed().size(); i++)
                lines.add("- " + path + ":" + (edit.line() + i) + " " + edit.removed().get(i));
            for (int i = 0; i < edit.added().size(); i++)
                lines.add("+ " + path + ":" + (edit.line() + i) + " " + edit.added().get(i));
        }
        return List.copyOf(lines);
    }

    /** Returns an exact semantic patch binding. / 返回精确语义补丁绑定。
     * @return patch digest / 补丁摘要
     */
    public String binding() {
        var value = new StringBuilder();
        for (String field : List.of(path, beforeDigest, sourceRevision))
            value.append(field.length()).append(':').append(field);
        value.append(edits.size()).append(':');
        for (var edit : edits) {
            value.append(edit.line()).append(':');
            for (var lines : List.of(edit.removed(), edit.added())) {
                value.append(lines.size()).append(':');
                for (String line : lines)
                    value.append(line.length()).append(':').append(line);
            }
        }
        return AgentAction.digest(value.toString());
    }
    /** One explicit line edit; contents omit line terminators. / 一个显式行编辑，内容不含行终止符。
     * @param line one-based original line / 从一开始的原始行
     * @param removed exact original lines / 精确原始行
     * @param added replacement lines / 替换行
     */
    public record Edit(int line, List<String> removed, List<String> added) {
        /** Bounds every line and rejects invisible embedded line breaks. / 限制每行并拒绝隐含换行。
         * @param line original position / 原始位置
         * @param removed removed lines / 删除行
         * @param added added lines / 增加行
         */
        public Edit {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
            if (line < 1 || line > 100000 || removed.size() + added.size() == 0 || removed.size() + added.size() > 200)
                throw new IllegalArgumentException("invalid patch edit");
            for (String value : java.util.stream.Stream.concat(removed.stream(), added.stream()).toList())
                if (value.length() > 4000 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                        || value.indexOf('\0') >= 0)
                    throw new IllegalArgumentException("invalid patch line");
        }
    }
}
