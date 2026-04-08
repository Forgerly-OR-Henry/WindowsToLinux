package gold.debug.wintolin.attribute.system;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class APackageInfo {

    /**
     * =========================
     * 基础输入属性
     * =========================
     */

    /**
     * 本地待打包的源码根目录
     */
    private Path localSourceDirectory;

    /**
     * 本地临时目录，用于生成归档文件
     */
    private Path localTempDirectory;

    /**
     * 归档基础名称，不包含扩展名
     */
    private String archiveBaseName;

    /**
     * 排除规则
     */
    private List<String> excludeRules = new ArrayList<>();

    /**
     * 远端临时目录
     */
    private String remoteTempDirectory;

    /**
     * 远端最终部署目录
     */
    private String remoteTargetDirectory;

    /**
     * 期望的解包根目录名称
     */
    private String expectedRootDirectoryName;

    /**
     * 若远端已存在同名归档文件，是否允许覆盖
     */
    private boolean overwriteRemoteIfExists;

    /**
     * 部署前是否备份旧目录
     */
    private boolean backupTargetBeforeReplace;

    /**
     * 部署完成后是否清理临时文件
     */
    private boolean cleanTempFilesAfterSuccess;


    /**
     * =========================
     * 打包阶段补全属性
     * =========================
     */

    /**
     * 本地生成的归档文件路径
     */
    private Path localArchivePath;

    /**
     * 本地归档文件名
     */
    private String localArchiveFileName;

    /**
     * 本地归档文件大小
     */
    private long localArchiveSize;

    /**
     * 本地归档文件 SHA-256
     */
    private String localArchiveSha256;

    /**
     * 被打包的文件数量
     */
    private int packedFileCount;


    /**
     * =========================
     * 上传阶段补全属性
     * =========================
     */

    /**
     * 远端归档文件完整路径
     */
    private String remoteArchivePath;

    /**
     * 远端归档文件 SHA-256
     */
    private String remoteArchiveSha256;

    /**
     * 实际上传大小
     */
    private long transferredSize;


    /**
     * =========================
     * 解包 / 部署阶段补全属性
     * =========================
     */

    /**
     * 最终部署目录
     */
    private String finalDeployPath;

    /**
     * 备份目录路径
     */
    private String backupPath;

    /**
     * 远端归档校验是否成功
     */
    private boolean remoteArchiveVerifySuccess;

    /**
     * 解包是否成功
     */
    private boolean unpackSuccess;

    /**
     * 替换目标目录是否成功
     */
    private boolean replaceSuccess;

    public APackageInfo() {
    }

    public Path getLocalSourceDirectory() {
        return localSourceDirectory;
    }

    public void setLocalSourceDirectory(Path localSourceDirectory) {
        this.localSourceDirectory = localSourceDirectory;
    }

    public Path getLocalTempDirectory() {
        return localTempDirectory;
    }

    public void setLocalTempDirectory(Path localTempDirectory) {
        this.localTempDirectory = localTempDirectory;
    }

    public String getArchiveBaseName() {
        return archiveBaseName;
    }

    public void setArchiveBaseName(String archiveBaseName) {
        this.archiveBaseName = archiveBaseName;
    }

    public List<String> getExcludeRules() {
        return excludeRules;
    }

    public void setExcludeRules(List<String> excludeRules) {
        this.excludeRules = (excludeRules == null) ? new ArrayList<>() : excludeRules;
    }

    public String getRemoteTempDirectory() {
        return remoteTempDirectory;
    }

    public void setRemoteTempDirectory(String remoteTempDirectory) {
        this.remoteTempDirectory = remoteTempDirectory;
    }

    public String getRemoteTargetDirectory() {
        return remoteTargetDirectory;
    }

    public void setRemoteTargetDirectory(String remoteTargetDirectory) {
        this.remoteTargetDirectory = remoteTargetDirectory;
    }

    public String getExpectedRootDirectoryName() {
        return expectedRootDirectoryName;
    }

    public void setExpectedRootDirectoryName(String expectedRootDirectoryName) {
        this.expectedRootDirectoryName = expectedRootDirectoryName;
    }

    public boolean isOverwriteRemoteIfExists() {
        return overwriteRemoteIfExists;
    }

    public void setOverwriteRemoteIfExists(boolean overwriteRemoteIfExists) {
        this.overwriteRemoteIfExists = overwriteRemoteIfExists;
    }

    public boolean isBackupTargetBeforeReplace() {
        return backupTargetBeforeReplace;
    }

    public void setBackupTargetBeforeReplace(boolean backupTargetBeforeReplace) {
        this.backupTargetBeforeReplace = backupTargetBeforeReplace;
    }

    public boolean isCleanTempFilesAfterSuccess() {
        return cleanTempFilesAfterSuccess;
    }

    public void setCleanTempFilesAfterSuccess(boolean cleanTempFilesAfterSuccess) {
        this.cleanTempFilesAfterSuccess = cleanTempFilesAfterSuccess;
    }

    public Path getLocalArchivePath() {
        return localArchivePath;
    }

    public void setLocalArchivePath(Path localArchivePath) {
        this.localArchivePath = localArchivePath;
    }

    public String getLocalArchiveFileName() {
        return localArchiveFileName;
    }

    public void setLocalArchiveFileName(String localArchiveFileName) {
        this.localArchiveFileName = localArchiveFileName;
    }

    public long getLocalArchiveSize() {
        return localArchiveSize;
    }

    public void setLocalArchiveSize(long localArchiveSize) {
        this.localArchiveSize = localArchiveSize;
    }

    public String getLocalArchiveSha256() {
        return localArchiveSha256;
    }

    public void setLocalArchiveSha256(String localArchiveSha256) {
        this.localArchiveSha256 = localArchiveSha256;
    }

    public int getPackedFileCount() {
        return packedFileCount;
    }

    public void setPackedFileCount(int packedFileCount) {
        this.packedFileCount = packedFileCount;
    }

    public String getRemoteArchivePath() {
        return remoteArchivePath;
    }

    public void setRemoteArchivePath(String remoteArchivePath) {
        this.remoteArchivePath = remoteArchivePath;
    }

    public String getRemoteArchiveSha256() {
        return remoteArchiveSha256;
    }

    public void setRemoteArchiveSha256(String remoteArchiveSha256) {
        this.remoteArchiveSha256 = remoteArchiveSha256;
    }

    public long getTransferredSize() {
        return transferredSize;
    }

    public void setTransferredSize(long transferredSize) {
        this.transferredSize = transferredSize;
    }

    public String getFinalDeployPath() {
        return finalDeployPath;
    }

    public void setFinalDeployPath(String finalDeployPath) {
        this.finalDeployPath = finalDeployPath;
    }

    public String getBackupPath() {
        return backupPath;
    }

    public void setBackupPath(String backupPath) {
        this.backupPath = backupPath;
    }

    public boolean isRemoteArchiveVerifySuccess() {
        return remoteArchiveVerifySuccess;
    }

    public void setRemoteArchiveVerifySuccess(boolean remoteArchiveVerifySuccess) {
        this.remoteArchiveVerifySuccess = remoteArchiveVerifySuccess;
    }

    public boolean isUnpackSuccess() {
        return unpackSuccess;
    }

    public void setUnpackSuccess(boolean unpackSuccess) {
        this.unpackSuccess = unpackSuccess;
    }

    public boolean isReplaceSuccess() {
        return replaceSuccess;
    }

    public void setReplaceSuccess(boolean replaceSuccess) {
        this.replaceSuccess = replaceSuccess;
    }
}