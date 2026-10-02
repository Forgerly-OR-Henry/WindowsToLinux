package gold.debug.windowstolinux.app.service.file;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Adapts one preplanned ordinary file to the existing journalled transfer state machine. / 将单个预先计划的普通文件接入既有持久传输状态机。 */
@FunctionalInterface
public interface FileTransfers {
    /** Transfers only to this actual selected destination; the old target must still match. / 仅传输到实际选定目标，旧目标仍须匹配。
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param expectedTarget exact old destination or confirmed absence / 精确旧目标或已确认缺失
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FileReport.Item transfer(FileInfo source, FilePath target, Optional<FileInfo> expectedTarget, TaskContext task) throws ApiException;
}
