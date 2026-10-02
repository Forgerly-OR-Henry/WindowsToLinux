package gold.debug.windowstolinux.appapi.file;

import java.util.*;

/** Original durable operation evidence; it never restores an old authorization. / 原持久操作依据，不恢复旧授权。
 * @param id original durable operation identity / 原持久操作身份
 * @param taskId original authentic task identity / 原真实任务身份
 * @param type exact operation category / 精确操作类别
 * @param phase confirmed or pending effect stage / 已确认或待核对副作用阶段
 * @param source original host-qualified source position retained in the record / 记录保留的原带主机来源位置
 * @param target recorded host-qualified destination, absent for operations without a destination / 已记录的带主机目标位置，无目标操作为空
 * @param steps original durable effect observations / 原持久副作用观测
 */
public record FileOperation(UUID id, UUID taskId, String type, String phase, FilePath source,
                            Optional<FilePath> target, List<Step> steps) {
    /** Copies bounded original evidence. / 复制有界原依据。
     * @param id original durable operation identity / 原持久操作身份
     * @param taskId original authentic task identity / 原真实任务身份
     * @param type exact operation category / 精确操作类别
     * @param phase confirmed or pending effect stage / 已确认或待核对副作用阶段
     * @param source original host-qualified source position retained in the record / 记录保留的原带主机来源位置
     * @param target recorded host-qualified destination, absent for operations without a destination / 已记录的带主机目标位置，无目标操作为空
     * @param steps original durable effect observations / 原持久副作用观测
     */
    public FileOperation {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(taskId);
        java.util.Objects.requireNonNull(type);
        java.util.Objects.requireNonNull(phase);
        java.util.Objects.requireNonNull(source);
        java.util.Objects.requireNonNull(target);
        steps = List.copyOf(steps);
    }

    /** An original observed execution phase. / 原观测执行阶段。
     * @param phase confirmed or pending effect stage / 已确认或待核对副作用阶段
     * @param source original host-qualified source position retained in the record / 记录保留的原带主机来源位置
     * @param target recorded host-qualified destination, absent for operations without a destination / 已记录的带主机目标位置，无目标操作为空
     * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
     */
    public record Step(String phase, FilePath source, Optional<FilePath> target, String detail) {
        /** Validates phase evidence. / 校验阶段依据。
         * @param phase confirmed or pending effect stage / 已确认或待核对副作用阶段
         * @param source original host-qualified source position retained in the record / 记录保留的原带主机来源位置
         * @param target recorded host-qualified destination, absent for operations without a destination / 已记录的带主机目标位置，无目标操作为空
         * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
         */
        public Step {
            java.util.Objects.requireNonNull(phase);
            java.util.Objects.requireNonNull(source);
            java.util.Objects.requireNonNull(target);
            java.util.Objects.requireNonNull(detail);
        }
    }
}
