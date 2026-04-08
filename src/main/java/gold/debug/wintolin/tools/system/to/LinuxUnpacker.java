package gold.debug.wintolin.tools.system.to;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.attribute.system.APackageInfo;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

public final class LinuxUnpacker {

    private static final String ERROR_UNPACK_PACKAGE_INFO_NULL = "ERROR_UNPACK_PACKAGE_INFO_NULL";
    private static final String ERROR_UNPACK_LINUX_INFO_NULL = "ERROR_UNPACK_LINUX_INFO_NULL";
    private static final String ERROR_UNPACK_SESSION_NULL = "ERROR_UNPACK_SESSION_NULL";
    private static final String ERROR_UNPACK_SESSION_NOT_CONNECTED = "ERROR_UNPACK_SESSION_NOT_CONNECTED";
    private static final String ERROR_UNPACK_REMOTE_ARCHIVE_PATH_NULL = "ERROR_UNPACK_REMOTE_ARCHIVE_PATH_NULL";
    private static final String ERROR_UNPACK_REMOTE_TARGET_DIRECTORY_NULL = "ERROR_UNPACK_REMOTE_TARGET_DIRECTORY_NULL";
    private static final String ERROR_UNPACK_REMOTE_TEMP_DIRECTORY_NULL = "ERROR_UNPACK_REMOTE_TEMP_DIRECTORY_NULL";
    private static final String ERROR_UNPACK_EXPECTED_ROOT_DIRECTORY_NAME_NULL = "ERROR_UNPACK_EXPECTED_ROOT_DIRECTORY_NAME_NULL";
    private static final String ERROR_UNPACK_REMOTE_HASH_VERIFY_FAILED = "ERROR_UNPACK_REMOTE_HASH_VERIFY_FAILED";
    private static final String ERROR_UNPACK_EXEC_COMMAND_FAILED = "ERROR_UNPACK_EXEC_COMMAND_FAILED";
    private static final String ERROR_UNPACK_ARCHIVE_ROOT_NOT_EXISTS = "ERROR_UNPACK_ARCHIVE_ROOT_NOT_EXISTS";
    private static final String ERROR_UNPACK_REPLACE_FAILED = "ERROR_UNPACK_REPLACE_FAILED";

    private static final Method METHOD_UNPACK =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "unpack", APackageInfo.class, ALinux.class);

    private static final Method METHOD_VALIDATE_INFO =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "validateInfo", APackageInfo.class, ALinux.class);

    private static final Method METHOD_VERIFY_REMOTE_ARCHIVE =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "verifyRemoteArchive", APackageInfo.class, ALinux.class);

    private static final Method METHOD_BUILD_UNPACK_ROOT_DIRECTORY =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "buildUnpackRootDirectory", APackageInfo.class);

    private static final Method METHOD_BUILD_UNPACK_WORK_DIRECTORY =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "buildUnpackWorkDirectory", APackageInfo.class);

    private static final Method METHOD_UNPACK_ARCHIVE =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "unpackArchive", APackageInfo.class, ALinux.class, String.class);

    private static final Method METHOD_VERIFY_EXPECTED_ROOT_DIRECTORY =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "verifyExpectedRootDirectory", APackageInfo.class, ALinux.class, String.class);

    private static final Method METHOD_REPLACE_TARGET_DIRECTORY =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "replaceTargetDirectory", APackageInfo.class, ALinux.class, String.class);

    private static final Method METHOD_BUILD_BACKUP_PATH =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "buildBackupPath", APackageInfo.class);

    private static final Method METHOD_EXECUTE_COMMAND =
            MethodUtils.getCurrentMethod(LinuxUnpacker.class, "executeCommand", ALinux.class, String.class);

    public void unpack(APackageInfo aPackageInfo, ALinux aLinux) {
        validateInfo(aPackageInfo, aLinux);

        verifyRemoteArchive(aPackageInfo, aLinux);
        aPackageInfo.setRemoteArchiveVerifySuccess(true);

        String unpackWorkDirectory = buildUnpackWorkDirectory(aPackageInfo);
        String unpackRootDirectory = buildUnpackRootDirectory(aPackageInfo);

        unpackArchive(aPackageInfo, aLinux, unpackWorkDirectory);
        aPackageInfo.setUnpackSuccess(true);

        verifyExpectedRootDirectory(aPackageInfo, aLinux, unpackRootDirectory);

        replaceTargetDirectory(aPackageInfo, aLinux, unpackRootDirectory);
        aPackageInfo.setReplaceSuccess(true);
        aPackageInfo.setFinalDeployPath(normalizeRemoteDirectory(aPackageInfo.getRemoteTargetDirectory()));
    }

    private void validateInfo(APackageInfo aPackageInfo, ALinux aLinux) {
        if (aPackageInfo == null) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "APackageInfo 不能为空。",
                    ERROR_UNPACK_PACKAGE_INFO_NULL
            );
        }

        if (aLinux == null) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "ALinux 不能为空。",
                    ERROR_UNPACK_LINUX_INFO_NULL
            );
        }

        if (aLinux.getSession() == null) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "Linux Session 为空，当前未建立 SSH 连接。",
                    ERROR_UNPACK_SESSION_NULL
            );
        }

        if (!aLinux.getSession().isConnected()) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "Linux Session 未连接，无法执行解包。",
                    ERROR_UNPACK_SESSION_NOT_CONNECTED
            );
        }

        if (isBlank(aPackageInfo.getRemoteArchivePath())) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "远端归档文件路径为空，请先完成上传。",
                    ERROR_UNPACK_REMOTE_ARCHIVE_PATH_NULL
            );
        }

        if (isBlank(aPackageInfo.getRemoteTargetDirectory())) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "远端目标部署目录不能为空。",
                    ERROR_UNPACK_REMOTE_TARGET_DIRECTORY_NULL
            );
        }

        if (isBlank(aPackageInfo.getRemoteTempDirectory())) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "远端临时目录不能为空。",
                    ERROR_UNPACK_REMOTE_TEMP_DIRECTORY_NULL
            );
        }

        if (isBlank(aPackageInfo.getExpectedRootDirectoryName())) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VALIDATE_INFO,
                    "期望的解包根目录名称不能为空。",
                    ERROR_UNPACK_EXPECTED_ROOT_DIRECTORY_NAME_NULL
            );
        }
    }

    private void verifyRemoteArchive(APackageInfo aPackageInfo, ALinux aLinux) {
        String expectedSha256 = aPackageInfo.getRemoteArchiveSha256();
        if (isBlank(expectedSha256)) {
            expectedSha256 = aPackageInfo.getLocalArchiveSha256();
        }

        if (isBlank(expectedSha256)) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VERIFY_REMOTE_ARCHIVE,
                    "缺少用于校验的归档 SHA-256。",
                    ERROR_UNPACK_REMOTE_HASH_VERIFY_FAILED
            );
        }

        String command = "sha256sum '" + escapeSingleQuotes(aPackageInfo.getRemoteArchivePath()) + "'";
        String output = executeCommand(aLinux, command).trim();

        if (output.isEmpty()) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VERIFY_REMOTE_ARCHIVE,
                    "远端 SHA-256 校验输出为空：" + aPackageInfo.getRemoteArchivePath(),
                    ERROR_UNPACK_REMOTE_HASH_VERIFY_FAILED
            );
        }

        String[] parts = output.split("\\s+");
        if (parts.length < 1 || parts[0].isBlank()) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VERIFY_REMOTE_ARCHIVE,
                    "远端 SHA-256 输出格式无效：" + output,
                    ERROR_UNPACK_REMOTE_HASH_VERIFY_FAILED
            );
        }

        String actualSha256 = parts[0];
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VERIFY_REMOTE_ARCHIVE,
                    "远端归档校验失败，expected=" + expectedSha256 + "，actual=" + actualSha256,
                    ERROR_UNPACK_REMOTE_HASH_VERIFY_FAILED
            );
        }
    }

    private String buildUnpackWorkDirectory(APackageInfo aPackageInfo) {
        String remoteTempDirectory = normalizeRemoteDirectory(aPackageInfo.getRemoteTempDirectory());
        String expectedRootDirectoryName = aPackageInfo.getExpectedRootDirectoryName();
        return remoteTempDirectory + "/unpack_" + expectedRootDirectoryName;
    }

    private String buildUnpackRootDirectory(APackageInfo aPackageInfo) {
        return buildUnpackWorkDirectory(aPackageInfo) + "/" + aPackageInfo.getExpectedRootDirectoryName();
    }

    private void unpackArchive(APackageInfo aPackageInfo, ALinux aLinux, String unpackWorkDirectory) {
        String remoteArchivePath = aPackageInfo.getRemoteArchivePath();
        String expectedRootDirectoryName = aPackageInfo.getExpectedRootDirectoryName();

        String command =
                "rm -rf '" + escapeSingleQuotes(unpackWorkDirectory) + "' && " +
                        "mkdir -p '" + escapeSingleQuotes(unpackWorkDirectory) + "' && " +
                        "tar -xzf '" + escapeSingleQuotes(remoteArchivePath) + "' -C '" + escapeSingleQuotes(unpackWorkDirectory) + "' && " +
                        "test -d '" + escapeSingleQuotes(unpackWorkDirectory + "/" + expectedRootDirectoryName) + "'";

        executeCommand(aLinux, command);
    }

    private void verifyExpectedRootDirectory(APackageInfo aPackageInfo, ALinux aLinux, String unpackRootDirectory) {
        String command = "test -d '" + escapeSingleQuotes(unpackRootDirectory) + "'";
        try {
            executeCommand(aLinux, command);
        } catch (MyException e) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_VERIFY_EXPECTED_ROOT_DIRECTORY,
                    "解包后未找到期望根目录：" + unpackRootDirectory,
                    ERROR_UNPACK_ARCHIVE_ROOT_NOT_EXISTS,
                    e
            );
        }
    }

    private void replaceTargetDirectory(APackageInfo aPackageInfo, ALinux aLinux, String unpackRootDirectory) {
        String remoteTargetDirectory = normalizeRemoteDirectory(aPackageInfo.getRemoteTargetDirectory());
        String remoteTargetParentDirectory = extractParentDirectory(remoteTargetDirectory);
        String backupPath = buildBackupPath(aPackageInfo);

        StringBuilder commandBuilder = new StringBuilder();
        commandBuilder.append("mkdir -p '").append(escapeSingleQuotes(remoteTargetParentDirectory)).append("' && ");

        if (aPackageInfo.isBackupTargetBeforeReplace()) {
            commandBuilder
                    .append("if [ -e '").append(escapeSingleQuotes(remoteTargetDirectory)).append("' ]; then ")
                    .append("rm -rf '").append(escapeSingleQuotes(backupPath)).append("' && ")
                    .append("mv '").append(escapeSingleQuotes(remoteTargetDirectory)).append("' '").append(escapeSingleQuotes(backupPath)).append("'; ")
                    .append("fi && ");
            aPackageInfo.setBackupPath(backupPath);
        } else {
            commandBuilder
                    .append("rm -rf '").append(escapeSingleQuotes(remoteTargetDirectory)).append("' && ");
            aPackageInfo.setBackupPath(null);
        }

        commandBuilder
                .append("mv '").append(escapeSingleQuotes(unpackRootDirectory)).append("' '")
                .append(escapeSingleQuotes(remoteTargetDirectory)).append("'");

        try {
            executeCommand(aLinux, commandBuilder.toString());
        } catch (MyException e) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_REPLACE_TARGET_DIRECTORY,
                    "替换目标目录失败：" + remoteTargetDirectory,
                    ERROR_UNPACK_REPLACE_FAILED,
                    e
            );
        }
    }

    private String buildBackupPath(APackageInfo aPackageInfo) {
        return normalizeRemoteDirectory(aPackageInfo.getRemoteTargetDirectory()) + "_backup";
    }

    private String executeCommand(ALinux aLinux, String command) {
        ChannelExec channelExec = null;
        InputStream inputStream = null;
        ByteArrayOutputStream errorStream = null;

        try {
            channelExec = (ChannelExec) aLinux.getSession().openChannel("exec");
            channelExec.setCommand(command);
            channelExec.setInputStream(null);

            errorStream = new ByteArrayOutputStream();
            channelExec.setErrStream(errorStream);

            inputStream = channelExec.getInputStream();
            channelExec.connect();

            String standardOutput = readAll(inputStream);

            while (!channelExec.isClosed()) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    MyException.fail(
                            LinuxUnpacker.class,
                            METHOD_EXECUTE_COMMAND,
                            "执行远端命令时线程被中断：" + command,
                            ERROR_UNPACK_EXEC_COMMAND_FAILED,
                            e
                    );
                }
            }

            int exitStatus = channelExec.getExitStatus();
            String errorOutput = errorStream.toString().trim();

            if (exitStatus != 0) {
                MyException.fail(
                        LinuxUnpacker.class,
                        METHOD_EXECUTE_COMMAND,
                        "执行远端命令失败，exitStatus=" + exitStatus + "，command=" + command +
                                (errorOutput.isEmpty() ? "" : "，stderr=" + errorOutput),
                        ERROR_UNPACK_EXEC_COMMAND_FAILED
                );
            }

            return standardOutput;
        } catch (JSchException | IOException e) {
            MyException.fail(
                    LinuxUnpacker.class,
                    METHOD_EXECUTE_COMMAND,
                    "执行远端命令失败：" + command,
                    ERROR_UNPACK_EXEC_COMMAND_FAILED,
                    e
            );
            return null;
        } finally {
            disconnectChannel(channelExec);
            closeQuietly(inputStream);
            closeQuietly(errorStream);
        }
    }

    private String extractParentDirectory(String remotePath) {
        int lastSlashIndex = remotePath.lastIndexOf('/');
        if (lastSlashIndex <= 0) {
            return "/";
        }
        return remotePath.substring(0, lastSlashIndex);
    }

    private String normalizeRemoteDirectory(String remoteDirectory) {
        String normalized = remoteDirectory.trim().replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String escapeSingleQuotes(String text) {
        return text.replace("'", "'\"'\"'");
    }

    private String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;

        while ((length = inputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, length);
        }

        return byteArrayOutputStream.toString();
    }

    private void disconnectChannel(Channel channel) {
        if (channel != null) {
            channel.disconnect();
        }
    }

    private void closeQuietly(AutoCloseable autoCloseable) {
        if (autoCloseable == null) {
            return;
        }
        try {
            autoCloseable.close();
        } catch (Exception ignored) {
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
