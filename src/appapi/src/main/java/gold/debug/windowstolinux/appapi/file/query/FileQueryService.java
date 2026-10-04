package gold.debug.windowstolinux.appapi.file.query;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Task-bound file observations. / 绑定任务的文件观测。 */
public interface FileQueryService {

    /** Observes a File's actual object and resolution chain. / 观测 File 实际对象及解析链。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @return immutable actual-object metadata and all links followed from the requested position / 实际对象元数据及从输入位置跟随的全部链接
     * @throws ApiException if task admission, link resolution, supported type, capacity or actual I/O validation fails / 任务准入、链接解析、支持类型、容量或实际 I/O 校验失败时
     */
    FileInfo getFile(FilePath path, TaskContext task) throws ApiException;

    /** Observes a Link itself without reading target content. / 观测 Link 自身，不读取目标内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @param task authentic original executing task / 真实原执行任务
     * @return immutable metadata of the link itself, including its original target text / 链接自身的不可变元数据及原始指向文本
     * @throws ApiException if task admission, link resolution, supported type, capacity or actual I/O validation fails / 任务准入、链接解析、支持类型、容量或实际 I/O 校验失败时
     */
    FileInfo readLink(FilePath path, TaskContext task) throws ApiException;

    /** Reads bounded actual regular content. / 读取有界实际普通内容。
     * @param path exact host-qualified position / 精确带主机位置
     * @param maxBytes maximum complete regular-file content bytes from one byte to sixty-four MiB / 一字节到六十四 MiB 的完整普通文件内容字节上限
     * @param task authentic original executing task / 真实原执行任务
     * @return independent content bytes; no truncated result is returned when the limit is exceeded / 独立内容字节，超过上限时不返回截断结果
     * @throws ApiException if task admission, link resolution, supported type, capacity or actual I/O validation fails / 任务准入、链接解析、支持类型、容量或实际 I/O 校验失败时
     */
    byte[] readFile(FilePath path, int maxBytes, TaskContext task) throws ApiException;

    /** Lists sorted immediate entries without following their links. / 按序列举直接子项，不跟随其链接。
     * @param directory absolute local or server-bound directory to observe / 待观测的绝对本机目录或服务器绑定目录
     * @param limit maximum immediate entries from one to one hundred thousand; overflow fails the whole listing / 一到十万个直接子项的上限，超限使整个列举失败
     * @param task authentic original executing task / 真实原执行任务
     * @return immutable sorted immediate entries; link entries retain their own metadata / 不可变且排序的直接子项，链接子项保留自身元数据
     * @throws ApiException if task admission, link resolution, supported type, capacity or actual I/O validation fails / 任务准入、链接解析、支持类型、容量或实际 I/O 校验失败时
     */
    java.util.List<FileInfo> list(FilePath directory, int limit, TaskContext task) throws ApiException;

    /** Lists bounded entries with explicit type and name filters. / 按明确类型及名称条件列举有界子项。
     * @param directory absolute local or server-bound directory to observe / 待观测的绝对本机目录或服务器绑定目录
     * @param limit maximum immediate entries from one to one hundred thousand; overflow fails the whole listing / 一到十万个直接子项的上限，超限使整个列举失败
     * @param kind optional object type filter / 可选对象类型筛选
     * @param nameContains case-sensitive substring of the immediate child name; empty accepts every name / 区分大小写的直接子项名称子串，空串接受全部名称
     * @param task authentic original executing task / 真实原执行任务
     * @return immutable sorted immediate entries; link entries retain their own metadata / 不可变且排序的直接子项，链接子项保留自身元数据
     * @throws ApiException if task admission, link resolution, supported type, capacity or actual I/O validation fails / 任务准入、链接解析、支持类型、容量或实际 I/O 校验失败时
     */
    java.util.List<FileInfo> list(FilePath directory, int limit, java.util.Optional<FileInfo.Kind> kind, String nameContains, TaskContext task) throws ApiException;
}
