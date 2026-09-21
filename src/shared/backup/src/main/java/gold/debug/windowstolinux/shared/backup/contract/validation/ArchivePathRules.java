package gold.debug.windowstolinux.shared.backup.contract.validation;

/**
 * Validates portable member paths and rejects archive traversal aliases.
 * <p>验证可移植成员路径并拒绝归档路径穿越别名。
 */
final class ArchivePathRules {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ArchivePathRules() {
    }

    /**
     * Validates archive path rules.
     * <p>校验归档路径规则集合。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param maximumLength maximum length / 最大长度
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    static void validate(String path, int maximumLength) throws BackupException {
        if (path == null || path.isEmpty() || path.length() > maximumLength || path.indexOf('\\') >= 0
                || path.startsWith("/") || path.endsWith("/") || path.matches("^[A-Za-z]:.*")
                || path.chars().anyMatch(Character::isISOControl)) {
            throw BackupException.create(BackupFailureType.MEMBER_REJECTED, "archive member path is not canonical");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(" ") || segment.endsWith(".")) {
                throw BackupException.create(BackupFailureType.MEMBER_REJECTED,
                        "archive member path contains an unsafe segment");
            }
        }
    }
}
