package gold.debug.windowstolinux.mod.files.file.model;

import java.util.List;

import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.action.FileReport;

/** Actual reports and their original-task journal, including successful link updates. / 实际报告及原任务日志，包含成功链接更新。
 * @param reports actual platform reports in execution order / 按执行顺序保存的实际平台报告
 * @param operations original durable operation observations / 原持久操作观测 */
public record FilesOutcome(List<FileReport> reports, List<FileOperation> operations) {
    /** Copies evidence without inferring missing effects. / 复制依据，不推断缺失副作用。
     * @param reports actual reports / 实际报告
     * @param operations actual original journal / 实际原日志 */
    public FilesOutcome {
        reports = List.copyOf(reports);
        operations = List.copyOf(operations);
    }
}
