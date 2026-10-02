package gold.debug.windowstolinux.appapi.file;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.Outcome;

/** Actual per-item results including partial progress and uncertainty. / 包含部分进度和不确定性的逐项真实结果。
 * @param operationId operation correlation identity; refusal before beginning an effect may have no durable record / 操作关联身份，在副作用开始前拒绝时可能没有持久记录
 * @param outcome verified operation result category / 已验证操作结果类别
 * @param items immutable per-object results / 不可变逐对象结果
 */
public record FileReport(UUID operationId, Outcome outcome, List<Item> items) {
    /** Copies bounded results. / 复制有界结果。
     * @param operationId operation correlation identity; refusal before beginning an effect may have no durable record / 操作关联身份，在副作用开始前拒绝时可能没有持久记录
     * @param outcome verified operation result category / 已验证操作结果类别
     * @param items immutable per-object results / 不可变逐对象结果
     */
    public FileReport {
        java.util.Objects.requireNonNull(operationId);
        java.util.Objects.requireNonNull(outcome);
        items = List.copyOf(items);
        if (items.size() > 100000) throw new IllegalArgumentException("Too many results");
    }

    /** One actual operation result. / 单个实际操作结果。
     * @param source source position to which this individual result applies / 此单项结果对应的来源位置
     * @param target actual chosen destination when one exists / 存在时的实际选择目标位置
     * @param outcome verified operation result category / 已验证操作结果类别
     * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
     */
    public record Item(FilePath source, Optional<FilePath> target, Outcome outcome, String detail) {
        /** Validates the immutable result. / 校验不可变结果。
         * @param source source position to which this individual result applies / 此单项结果对应的来源位置
         * @param target actual chosen destination when one exists / 存在时的实际选择目标位置
         * @param outcome verified operation result category / 已验证操作结果类别
         * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
         */
        public Item {
            java.util.Objects.requireNonNull(source);
            java.util.Objects.requireNonNull(target);
            java.util.Objects.requireNonNull(outcome);
            java.util.Objects.requireNonNull(detail);
        }
    }
}
