package gold.debug.windowstolinux.app.file.model;

import java.io.IOException;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;

/** Durable pre-effect evidence and a bounded owner-specific link registry. / 持久副作用前依据及有界所有者链接登记。 */
public interface FileActionLog {
    /** Commits the complete bounded plan before any effect. / 在任何副作用前提交完整有界计划。
     * @param type exact operation category / 精确操作类别
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param plan exact preapproved operation plan / 精确预批准操作计划
     * @return newly committed operation identity bound to this plan / 绑定此计划的新已提交操作身份
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    UUID begin(String type, FilePath source, Optional<FilePath> target, String plan) throws IOException;

    /** Commits each phase before dispatch and its observed result afterwards. / 发出前提交阶段，之后提交观测结果。
     * @param operation original durable operation identity / 原持久操作身份
     * @param phase confirmed or pending effect stage / 已确认或待核对副作用阶段
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void record(UUID operation, String phase, FilePath source, Optional<FilePath> target, String detail) throws IOException;

    /** Returns only the caller's registered links on this host. / 只返回调用方在当前主机的登记链接。
     * @param host host-qualified registry scope / 带主机登记作用域
     * @return bounded immutable no-follow registered link observations / 有界不可变的不跟随登记链接观测
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    List<FileInfo> links(FilePath host) throws IOException;

    /** Verifies a registered target identity before following a moved original. / 跟随移动原件前核对登记目标身份。
     * @param link verified no-follow registered link observation / 已验证不跟随登记链接观测
     * @param target actual currently observed target identity / 实际当前观测目标身份
     * @return whether the recorded confirmed target identity matches the actual observation / 已登记确认目标身份是否符合实际观测
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    boolean targetMatches(FileInfo link, FileInfo target) throws IOException;

    /** Saves an actually observed link and target identity. / 保存实际观测的链接及目标身份。
     * @param link verified no-follow link observation / 已验证不跟随链接观测
     * @param target actual target identity, or empty for an unverified association / 实际目标身份，未证实关联则为空
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void saveLink(FileInfo link, Optional<FileInfo> target) throws IOException;

    /** Removes only the caller's existing link registration. / 仅解除调用方既有链接登记。
     * @param link exact caller-owned registered link position / 精确调用方所属登记链接位置
     * @throws IOException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void removeLink(FilePath link) throws IOException;
}
