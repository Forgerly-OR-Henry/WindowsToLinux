package gold.debug.windowstolinux.web.file.quota;

/** Bounded storage and extraction limits, shared by directory and archive uploads. */
public record UploadQuota(long fileBytes, long projectBytes, long totalBytes, int members, int pathLength) {
    public UploadQuota {
        if (fileBytes < 1 || projectBytes < fileBytes || totalBytes < projectBytes || members < 1 || pathLength < 1)
            throw new IllegalArgumentException("Invalid upload quota");
    }
}
