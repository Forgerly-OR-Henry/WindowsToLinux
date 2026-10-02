package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

import static gold.debug.windowstolinux.app.file.FileEngine.*;

/** FileContentPublication owns the documented exact file responsibility. / FileContentPublication负责已描述的精确文件职责。
 */
final class FileContentPublication {

    /** Prevents construction of the staging publication utility. / 禁止构造暂存发布工具。
     */
    private FileContentPublication() {
    }

    /** Verifies and atomically publishes bounded staged regular content. / 核对并原子发布有界暂存普通内容。
     * @param step one preplanned object and its old destination identity / 单个预定对象及原目标身份
     * @param source source-host engine that reads and revalidates the original content / 读取并重新核实原内容的来源主机引擎
     * @param destination observed destination host engine / 已观测目标主机引擎
     * @param cancel original cooperative cancellation / 原协作取消
     * @param progress actual byte progress callback / 实际字节进度回调
     * @param completed actual copied byte count / 实际复制字节数
     * @param total planned total regular content bytes / 预定普通内容总字节数
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param operation original durable operation identity / 原持久操作身份
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    static void copy(FileCopyPlan.Step step, FileEngine source, FileEngine destination, CancelToken cancel, Progress progress, long completed, long total, FileJournal journal, UUID operation) throws IOException {
        FilePath staging = step.target.parent().child(".wtl-file-" + UUID.randomUUID() + ".part");
        journal.record(operation, "BEFORE_STAGING", step.source.actual(), Optional.of(step.target), staging.key());
        long count = 0;
        var hash = sha();
        InputStream sourceStream;
        try {
            sourceStream = source.backend().read(step.source.actual());
        } catch (IOException unavailable) {
            throw new FilePreconditionException("Source content cannot be opened");
        }
        try (InputStream in = sourceStream; OutputStream out = destination.backend().create(staging)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                check(cancel);
                count += read;
                if (count > step.source.bytes()) throw new IOException("Source grew during transfer");
                out.write(buffer, 0, read);
                hash.update(buffer, 0, read);
                progress.update(completed + count, total);
            }
        }
        if (count != step.source.bytes()) throw new IOException("Source extent changed during transfer");
        same(step.source, source.inspect(step.source.requested(), false, cancel));
        String digest = HexFormat.of().formatHex(hash.digest());
        if (!digest.equals(destination.digest(staging, count, cancel)))
            throw new IOException("Staged content digest differs");
        destination.verifyTarget(step, cancel);
        destination.backend().publish(staging, step.target, step.existing != null);
        if (!digest.equals(destination.digest(step.target, count, cancel)))
            throw new IOException("Published content digest differs");
    }

    /** Stages and verifies an exact link target before publication. / 发布前暂存并核对精确链接指向。
     * @param step one preplanned object and its old destination identity / 单个预定对象及原目标身份
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param destination observed destination host engine / 已观测目标主机引擎
     * @param cancel original cooperative cancellation / 原协作取消
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param operation original durable operation identity / 原持久操作身份
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    static void link(FileCopyPlan.Step step, FilePath target, FileEngine destination, CancelToken cancel, FileJournal journal, UUID operation) throws IOException {
        FilePath staging = step.target.parent().child(".wtl-link-" + UUID.randomUUID() + ".part");
        journal.record(operation, "BEFORE_LINK_STAGING", step.source.actual(), Optional.of(step.target), staging.key() + " -> " + target.key());
        destination.backend().stageLink(step.source, staging, linkText(step.target, target));
        destination.verifyTarget(step, cancel);
        destination.backend().publish(staging, step.target, step.existing != null);
        FileInfo actual = destination.inspect(step.target, true, cancel);
        if (!resolveTarget(step.target, actual.target().orElseThrow()).equals(target))
            throw new IOException("Published link differs");
    }

    /** Revalidates and replaces the original managed link without deleting its target. / 重新核验并替换原受管链接，不删除其目标。
     * @param link original verified managed link observation / 原已核实受管链接观测
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param engine actual host file engine / 实际主机文件引擎
     * @param cancel original cooperative cancellation / 原协作取消
     * @param journal durable operation and link registry / 持久操作及链接登记
     * @param operation original durable operation identity / 原持久操作身份
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    static void retarget(FileInfo link, FilePath target, FileEngine engine, CancelToken cancel, FileJournal journal, UUID operation) throws IOException {
        same(link, engine.inspect(link.actual(), true, cancel));
        var step = new FileCopyPlan.Step(link, link.actual(), link, false, "");
        step.parent = engine.inspect(link.actual().parent(), false, cancel);
        link(step, target, engine, cancel, journal, operation);
    }
}
