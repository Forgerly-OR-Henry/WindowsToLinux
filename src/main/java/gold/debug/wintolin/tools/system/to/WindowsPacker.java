package gold.debug.wintolin.tools.system.to;

import gold.debug.wintolin.attribute.system.APackageInfo;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class WindowsPacker {

    private static final String ERROR_PACK_INFO_NULL = "ERROR_PACK_INFO_NULL";
    private static final String ERROR_PACK_SOURCE_DIRECTORY_NULL = "ERROR_PACK_SOURCE_DIRECTORY_NULL";
    private static final String ERROR_PACK_SOURCE_DIRECTORY_NOT_EXISTS = "ERROR_PACK_SOURCE_DIRECTORY_NOT_EXISTS";
    private static final String ERROR_PACK_SOURCE_DIRECTORY_INVALID = "ERROR_PACK_SOURCE_DIRECTORY_INVALID";
    private static final String ERROR_PACK_TEMP_DIRECTORY_NULL = "ERROR_PACK_TEMP_DIRECTORY_NULL";
    private static final String ERROR_PACK_ARCHIVE_BASE_NAME_INVALID = "ERROR_PACK_ARCHIVE_BASE_NAME_INVALID";
    private static final String ERROR_PACK_CREATE_DIRECTORY_FAILED = "ERROR_PACK_CREATE_DIRECTORY_FAILED";
    private static final String ERROR_PACK_CREATE_ARCHIVE_FAILED = "ERROR_PACK_CREATE_ARCHIVE_FAILED";
    private static final String ERROR_PACK_HASH_FAILED = "ERROR_PACK_HASH_FAILED";
    private static final String ERROR_PACK_COUNT_FAILED = "ERROR_PACK_COUNT_FAILED";

    private static final Method METHOD_PACK =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "pack", APackageInfo.class);

    private static final Method METHOD_VALIDATE_INFO =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "validateInfo", APackageInfo.class);

    private static final Method METHOD_PREPARE_DEFAULT_VALUES =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "prepareDefaultValues", APackageInfo.class);

    private static final Method METHOD_BUILD_ARCHIVE_FILE_NAME =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "buildArchiveFileName", APackageInfo.class);

    private static final Method METHOD_CREATE_ARCHIVE =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "createArchive", APackageInfo.class, Path.class);

    private static final Method METHOD_WRITE_SOURCE_TO_ARCHIVE =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "writeSourceToArchive", APackageInfo.class, TarArchiveOutputStream.class);

    private static final Method METHOD_ADD_PATH_TO_ARCHIVE =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "addPathToArchive", APackageInfo.class, Path.class, Path.class, String.class, TarArchiveOutputStream.class);

    private static final Method METHOD_SHOULD_EXCLUDE =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "shouldExclude", APackageInfo.class, Path.class, String.class);

    private static final Method METHOD_BUILD_ENTRY_NAME =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "buildEntryName", Path.class, Path.class, String.class);

    private static final Method METHOD_CALCULATE_SHA256 =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "calculateSha256", Path.class);

    private static final Method METHOD_COUNT_PACKED_FILES =
            MethodUtils.getCurrentMethod(WindowsPacker.class, "countPackedFiles", APackageInfo.class);

    public void pack(APackageInfo aPackageInfo) {
        validateInfo(aPackageInfo);
        prepareDefaultValues(aPackageInfo);

        String archiveFileName = buildArchiveFileName(aPackageInfo);
        Path archivePath = aPackageInfo.getLocalTempDirectory().resolve(archiveFileName);

        createArchive(aPackageInfo, archivePath);

        aPackageInfo.setLocalArchivePath(archivePath);
        aPackageInfo.setLocalArchiveFileName(archiveFileName);
        aPackageInfo.setLocalArchiveSize(readFileSize(archivePath));
        aPackageInfo.setLocalArchiveSha256(calculateSha256(archivePath));
        aPackageInfo.setPackedFileCount(countPackedFiles(aPackageInfo));
    }

    private void validateInfo(APackageInfo aPackageInfo) {
        if (aPackageInfo == null) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_VALIDATE_INFO,
                    "APackageInfo 不能为空。",
                    ERROR_PACK_INFO_NULL
            );
        }

        if (aPackageInfo.getLocalSourceDirectory() == null) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_VALIDATE_INFO,
                    "本地源码目录不能为空。",
                    ERROR_PACK_SOURCE_DIRECTORY_NULL
            );
        }

        if (!Files.exists(aPackageInfo.getLocalSourceDirectory())) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_VALIDATE_INFO,
                    "本地源码目录不存在：" + aPackageInfo.getLocalSourceDirectory(),
                    ERROR_PACK_SOURCE_DIRECTORY_NOT_EXISTS
            );
        }

        if (!Files.isDirectory(aPackageInfo.getLocalSourceDirectory())) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_VALIDATE_INFO,
                    "本地源码目录不是有效目录：" + aPackageInfo.getLocalSourceDirectory(),
                    ERROR_PACK_SOURCE_DIRECTORY_INVALID
            );
        }

        if (aPackageInfo.getLocalTempDirectory() == null) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_VALIDATE_INFO,
                    "本地临时目录不能为空。",
                    ERROR_PACK_TEMP_DIRECTORY_NULL
            );
        }
    }

    private void prepareDefaultValues(APackageInfo aPackageInfo) {
        Path localTempDirectory = aPackageInfo.getLocalTempDirectory();
        try {
            Files.createDirectories(localTempDirectory);
        } catch (IOException e) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_PREPARE_DEFAULT_VALUES,
                    "创建本地临时目录失败：" + localTempDirectory,
                    ERROR_PACK_CREATE_DIRECTORY_FAILED,
                    e
            );
        }

        String sourceDirectoryName = aPackageInfo.getLocalSourceDirectory().getFileName().toString();

        if (isBlank(aPackageInfo.getExpectedRootDirectoryName())) {
            aPackageInfo.setExpectedRootDirectoryName(sourceDirectoryName);
        }

        if (isBlank(aPackageInfo.getArchiveBaseName())) {
            aPackageInfo.setArchiveBaseName(sourceDirectoryName);
        }
    }

    private String buildArchiveFileName(APackageInfo aPackageInfo) {
        String archiveBaseName = aPackageInfo.getArchiveBaseName();
        if (isBlank(archiveBaseName)) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_BUILD_ARCHIVE_FILE_NAME,
                    "归档基础名称不能为空。",
                    ERROR_PACK_ARCHIVE_BASE_NAME_INVALID
            );
        }
        return archiveBaseName + ".tar.gz";
    }

    private void createArchive(APackageInfo aPackageInfo, Path archivePath) {
        try {
            if (Files.exists(archivePath)) {
                Files.delete(archivePath);
            }

            try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(
                    Files.newOutputStream(
                            archivePath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                    )
            );
                 GzipCompressorOutputStream gzipOutputStream = new GzipCompressorOutputStream(bufferedOutputStream);
                 TarArchiveOutputStream tarOutputStream = new TarArchiveOutputStream(gzipOutputStream)) {

                tarOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tarOutputStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

                writeSourceToArchive(aPackageInfo, tarOutputStream);
                tarOutputStream.finish();
            }

        } catch (IOException e) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_CREATE_ARCHIVE,
                    "生成归档文件失败：" + archivePath,
                    ERROR_PACK_CREATE_ARCHIVE_FAILED,
                    e
            );
        }
    }

    private void writeSourceToArchive(APackageInfo aPackageInfo, TarArchiveOutputStream tarOutputStream) {
        Path sourceRoot = aPackageInfo.getLocalSourceDirectory();
        String rootDirectoryName = aPackageInfo.getExpectedRootDirectoryName();

        try (Stream<Path> pathStream = Files.walk(sourceRoot).sorted(Comparator.naturalOrder())) {
            pathStream.forEach(path -> addPathToArchive(aPackageInfo, sourceRoot, path, rootDirectoryName, tarOutputStream));
        } catch (IOException e) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_WRITE_SOURCE_TO_ARCHIVE,
                    "遍历源码目录失败：" + sourceRoot,
                    ERROR_PACK_CREATE_ARCHIVE_FAILED,
                    e
            );
        }
    }

    private void addPathToArchive(
            APackageInfo aPackageInfo,
            Path sourceRoot,
            Path currentPath,
            String rootDirectoryName,
            TarArchiveOutputStream tarOutputStream
    ) {
        String entryName = buildEntryName(sourceRoot, currentPath, rootDirectoryName);

        if (shouldExclude(aPackageInfo, currentPath, entryName)) {
            return;
        }

        try {
            if (Files.isDirectory(currentPath)) {
                String directoryEntryName = entryName.endsWith("/") ? entryName : entryName + "/";
                TarArchiveEntry directoryEntry = new TarArchiveEntry(currentPath.toFile(), directoryEntryName);
                tarOutputStream.putArchiveEntry(directoryEntry);
                tarOutputStream.closeArchiveEntry();
                return;
            }

            TarArchiveEntry fileEntry = new TarArchiveEntry(currentPath.toFile(), entryName);
            tarOutputStream.putArchiveEntry(fileEntry);

            try (InputStream inputStream = Files.newInputStream(currentPath)) {
                inputStream.transferTo(tarOutputStream);
            }

            tarOutputStream.closeArchiveEntry();
        } catch (IOException e) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_ADD_PATH_TO_ARCHIVE,
                    "写入归档条目失败：" + currentPath,
                    ERROR_PACK_CREATE_ARCHIVE_FAILED,
                    e
            );
        }
    }

    private boolean shouldExclude(APackageInfo aPackageInfo, Path currentPath, String entryName) {
        List<String> excludeRules = aPackageInfo.getExcludeRules();
        if (excludeRules == null || excludeRules.isEmpty()) {
            return false;
        }

        String normalizedEntryName = normalizeRule(entryName);
        String fileName = currentPath.getFileName() == null ? "" : currentPath.getFileName().toString();

        for (String rawRule : excludeRules) {
            String rule = normalizeRule(rawRule);
            if (rule.isEmpty()) {
                continue;
            }

            if (normalizedEntryName.equals(rule)
                    || normalizedEntryName.startsWith(rule + "/")
                    || fileName.equals(rule)) {
                return true;
            }
        }

        return false;
    }

    private String buildEntryName(Path sourceRoot, Path currentPath, String rootDirectoryName) {
        if (sourceRoot.equals(currentPath)) {
            return rootDirectoryName;
        }

        String relativePath = sourceRoot.relativize(currentPath).toString().replace('\\', '/');
        return rootDirectoryName + "/" + relativePath;
    }

    private String calculateSha256(Path filePath) {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int length;

            while ((length = inputStream.read(buffer)) != -1) {
                messageDigest.update(buffer, 0, length);
            }

            return toHex(messageDigest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_CALCULATE_SHA256,
                    "计算 SHA-256 失败：" + filePath,
                    ERROR_PACK_HASH_FAILED,
                    e
            );
            return null;
        }
    }

    private int countPackedFiles(APackageInfo aPackageInfo) {
        Path sourceRoot = aPackageInfo.getLocalSourceDirectory();

        try (Stream<Path> pathStream = Files.walk(sourceRoot)) {
            return (int) pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String entryName = buildEntryName(
                                sourceRoot,
                                path,
                                aPackageInfo.getExpectedRootDirectoryName()
                        );
                        return !shouldExclude(aPackageInfo, path, entryName);
                    })
                    .count();
        } catch (IOException e) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_COUNT_PACKED_FILES,
                    "统计被打包文件数量失败：" + sourceRoot,
                    ERROR_PACK_COUNT_FAILED,
                    e
            );
            return 0;
        }
    }

    private long readFileSize(Path filePath) {
        try {
            return Files.size(filePath);
        } catch (IOException e) {
            MyException.fail(
                    WindowsPacker.class,
                    METHOD_PACK,
                    "读取归档文件大小失败：" + filePath,
                    ERROR_PACK_CREATE_ARCHIVE_FAILED,
                    e
            );
            return 0L;
        }
    }

    private String normalizeRule(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte currentByte : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", currentByte));
        }
        return builder.toString();
    }
}
