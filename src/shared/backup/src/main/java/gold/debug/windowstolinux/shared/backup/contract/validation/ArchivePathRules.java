package gold.debug.windowstolinux.shared.backup.contract.validation;

final class ArchivePathRules {
    private ArchivePathRules() {
    }

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
