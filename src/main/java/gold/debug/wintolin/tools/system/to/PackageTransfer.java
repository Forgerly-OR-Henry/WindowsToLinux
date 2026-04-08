package gold.debug.wintolin.tools.system.to;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.attribute.system.APackageInfo;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Vector;

public final class PackageTransfer {

    private static final String ERROR_TRANSFER_PACKAGE_INFO_NULL = "ERROR_TRANSFER_PACKAGE_INFO_NULL";
    private static final String ERROR_TRANSFER_LINUX_INFO_NULL = "ERROR_TRANSFER_LINUX_INFO_NULL";
    private static final String ERROR_TRANSFER_SESSION_NULL = "ERROR_TRANSFER_SESSION_NULL";
    private static final String ERROR_TRANSFER_SESSION_NOT_CONNECTED = "ERROR_TRANSFER_SESSION_NOT_CONNECTED";
    private static final String ERROR_TRANSFER_LOCAL_ARCHIVE_PATH_NULL = "ERROR_TRANSFER_LOCAL_ARCHIVE_PATH_NULL";
    private static final String ERROR_TRANSFER_LOCAL_ARCHIVE_NOT_EXISTS = "ERROR_TRANSFER_LOCAL_ARCHIVE_NOT_EXISTS";
    private static final String ERROR_TRANSFER_REMOTE_TEMP_DIRECTORY_INVALID = "ERROR_TRANSFER_REMOTE_TEMP_DIRECTORY_INVALID";
    private static final String ERROR_TRANSFER_REMOTE_ARCHIVE_FILE_NAME_INVALID = "ERROR_TRANSFER_REMOTE_ARCHIVE_FILE_NAME_INVALID";
    private static final String ERROR_TRANSFER_OPEN_SFTP_FAILED = "ERROR_TRANSFER_OPEN_SFTP_FAILED";
    private static final String ERROR_TRANSFER_CREATE_REMOTE_DIRECTORY_FAILED = "ERROR_TRANSFER_CREATE_REMOTE_DIRECTORY_FAILED";
    private static final String ERROR_TRANSFER_UPLOAD_FAILED = "ERROR_TRANSFER_UPLOAD_FAILED";
    private static final String ERROR_TRANSFER_REMOTE_HASH_FAILED = "ERROR_TRANSFER_REMOTE_HASH_FAILED";
    private static final String ERROR_TRANSFER_READ_LOCAL_FILE_SIZE_FAILED = "ERROR_TRANSFER_READ_LOCAL_FILE_SIZE_FAILED";
    private static final String ERROR_TRANSFER_EXEC_COMMAND_FAILED = "ERROR_TRANSFER_EXEC_COMMAND_FAILED";

    private static final Method METHOD_TRANSFER =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "transfer", APackageInfo.class, ALinux.class);

    private static final Method METHOD_VALIDATE_INFO =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "validateInfo", APackageInfo.class, ALinux.class);

    private static final Method METHOD_BUILD_REMOTE_ARCHIVE_PATH =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "buildRemoteArchivePath", APackageInfo.class);

    private static final Method METHOD_OPEN_SFTP_CHANNEL =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "openSftpChannel", ALinux.class);

    private static final Method METHOD_ENSURE_REMOTE_DIRECTORY_EXISTS =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "ensureRemoteDirectoryExists", ChannelSftp.class, String.class);

    private static final Method METHOD_UPLOAD_ARCHIVE =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "uploadArchive", ChannelSftp.class, Path.class, String.class);

    private static final Method METHOD_QUERY_REMOTE_SHA256 =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "queryRemoteSha256", ALinux.class, String.class);

    private static final Method METHOD_EXECUTE_COMMAND =
            MethodUtils.getCurrentMethod(PackageTransfer.class, "executeCommand", ALinux.class, String.class);

    public void transfer(APackageInfo aPackageInfo, ALinux aLinux) {
        validateInfo(aPackageInfo, aLinux);

        String remoteArchivePath = buildRemoteArchivePath(aPackageInfo);
        String remoteDirectory = extractParentDirectory(remoteArchivePath);

        ChannelSftp channelSftp = null;
        try {
            channelSftp = openSftpChannel(aLinux);
            ensureRemoteDirectoryExists(channelSftp, remoteDirectory);
            uploadArchive(channelSftp, aPackageInfo.getLocalArchivePath(), remoteArchivePath);
        } finally {
            disconnectChannel(channelSftp);
        }

        aPackageInfo.setRemoteArchivePath(remoteArchivePath);
        aPackageInfo.setTransferredSize(readLocalFileSize(aPackageInfo.getLocalArchivePath()));
        aPackageInfo.setRemoteArchiveSha256(queryRemoteSha256(aLinux, remoteArchivePath));
    }

    private void validateInfo(APackageInfo aPackageInfo, ALinux aLinux) {
        if (aPackageInfo == null) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "APackageInfo 不能为空。",
                    ERROR_TRANSFER_PACKAGE_INFO_NULL
            );
        }

        if (aLinux == null) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "ALinux 不能为空。",
                    ERROR_TRANSFER_LINUX_INFO_NULL
            );
        }

        if (aLinux.getSession() == null) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "Linux Session 为空，当前未建立 SSH 连接。",
                    ERROR_TRANSFER_SESSION_NULL
            );
        }

        if (!aLinux.getSession().isConnected()) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "Linux Session 未连接，无法执行传输。",
                    ERROR_TRANSFER_SESSION_NOT_CONNECTED
            );
        }

        if (aPackageInfo.getLocalArchivePath() == null) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "本地归档文件路径为空，请先完成打包。",
                    ERROR_TRANSFER_LOCAL_ARCHIVE_PATH_NULL
            );
        }

        if (!Files.exists(aPackageInfo.getLocalArchivePath()) || !Files.isRegularFile(aPackageInfo.getLocalArchivePath())) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "本地归档文件不存在或无效：" + aPackageInfo.getLocalArchivePath(),
                    ERROR_TRANSFER_LOCAL_ARCHIVE_NOT_EXISTS
            );
        }

        if (isBlank(aPackageInfo.getRemoteTempDirectory())) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "远端临时目录不能为空。",
                    ERROR_TRANSFER_REMOTE_TEMP_DIRECTORY_INVALID
            );
        }

        if (isBlank(aPackageInfo.getLocalArchiveFileName())) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_VALIDATE_INFO,
                    "本地归档文件名为空，请先完成打包。",
                    ERROR_TRANSFER_REMOTE_ARCHIVE_FILE_NAME_INVALID
            );
        }
    }

    private String buildRemoteArchivePath(APackageInfo aPackageInfo) {
        String remoteTempDirectory = normalizeRemoteDirectory(aPackageInfo.getRemoteTempDirectory());
        return remoteTempDirectory + "/" + aPackageInfo.getLocalArchiveFileName();
    }

    private ChannelSftp openSftpChannel(ALinux aLinux) {
        try {
            Channel channel = aLinux.getSession().openChannel("sftp");
            channel.connect();
            return (ChannelSftp) channel;
        } catch (JSchException e) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_OPEN_SFTP_CHANNEL,
                    "打开 SFTP 通道失败。",
                    ERROR_TRANSFER_OPEN_SFTP_FAILED,
                    e
            );
            return null;
        }
    }

    private void ensureRemoteDirectoryExists(ChannelSftp channelSftp, String remoteDirectory) {
        try {
            if (isBlank(remoteDirectory)) {
                MyException.fail(
                        PackageTransfer.class,
                        METHOD_ENSURE_REMOTE_DIRECTORY_EXISTS,
                        "远端目录为空，无法创建。",
                        ERROR_TRANSFER_CREATE_REMOTE_DIRECTORY_FAILED
                );
            }

            String normalizedDirectory = normalizeRemoteDirectory(remoteDirectory);
            String[] parts = normalizedDirectory.split("/");

            StringBuilder currentPath = new StringBuilder();
            if (normalizedDirectory.startsWith("/")) {
                currentPath.append("/");
            }

            for (String part : parts) {
                if (part == null || part.isBlank()) {
                    continue;
                }

                if (currentPath.length() > 1 || (currentPath.length() == 1 && currentPath.charAt(0) != '/')) {
                    currentPath.append("/");
                }
                currentPath.append(part);

                String pathToCheck = currentPath.toString();
                if (!remoteDirectoryExists(channelSftp, pathToCheck)) {
                    channelSftp.mkdir(pathToCheck);
                }
            }
        } catch (SftpException e) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_ENSURE_REMOTE_DIRECTORY_EXISTS,
                    "创建远端目录失败：" + remoteDirectory,
                    ERROR_TRANSFER_CREATE_REMOTE_DIRECTORY_FAILED,
                    e
            );
        }
    }

    private void uploadArchive(ChannelSftp channelSftp, Path localArchivePath, String remoteArchivePath) {
        try {
            channelSftp.put(localArchivePath.toString(), remoteArchivePath);
        } catch (SftpException e) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_UPLOAD_ARCHIVE,
                    "上传归档文件失败：" + localArchivePath + " -> " + remoteArchivePath,
                    ERROR_TRANSFER_UPLOAD_FAILED,
                    e
            );
        }
    }

    private String queryRemoteSha256(ALinux aLinux, String remoteArchivePath) {
        String command = "sha256sum '" + escapeSingleQuotes(remoteArchivePath) + "'";
        String output = executeCommand(aLinux, command).trim();

        if (output.isEmpty()) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_QUERY_REMOTE_SHA256,
                    "远端 SHA-256 输出为空：" + remoteArchivePath,
                    ERROR_TRANSFER_REMOTE_HASH_FAILED
            );
        }

        String[] parts = output.split("\\s+");
        if (parts.length < 1 || parts[0].isBlank()) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_QUERY_REMOTE_SHA256,
                    "远端 SHA-256 输出格式无效：" + output,
                    ERROR_TRANSFER_REMOTE_HASH_FAILED
            );
        }

        return parts[0];
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
                            PackageTransfer.class,
                            METHOD_EXECUTE_COMMAND,
                            "执行远端命令时线程被中断：" + command,
                            ERROR_TRANSFER_EXEC_COMMAND_FAILED,
                            e
                    );
                }
            }

            int exitStatus = channelExec.getExitStatus();
            String errorOutput = errorStream.toString().trim();

            if (exitStatus != 0) {
                MyException.fail(
                        PackageTransfer.class,
                        METHOD_EXECUTE_COMMAND,
                        "执行远端命令失败，exitStatus=" + exitStatus + "，command=" + command +
                                (errorOutput.isEmpty() ? "" : "，stderr=" + errorOutput),
                        ERROR_TRANSFER_EXEC_COMMAND_FAILED
                );
            }

            return standardOutput;
        } catch (JSchException | IOException e) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_EXECUTE_COMMAND,
                    "执行远端命令失败：" + command,
                    ERROR_TRANSFER_EXEC_COMMAND_FAILED,
                    e
            );
            return null;
        } finally {
            disconnectChannel(channelExec);
            closeQuietly(inputStream);
            closeQuietly(errorStream);
        }
    }

    private boolean remoteDirectoryExists(ChannelSftp channelSftp, String remoteDirectory) {
        try {
            Vector<?> result = channelSftp.ls(remoteDirectory);
            return result != null;
        } catch (SftpException e) {
            return false;
        }
    }

    private long readLocalFileSize(Path localArchivePath) {
        try {
            return Files.size(localArchivePath);
        } catch (IOException e) {
            MyException.fail(
                    PackageTransfer.class,
                    METHOD_TRANSFER,
                    "读取本地归档文件大小失败：" + localArchivePath,
                    ERROR_TRANSFER_READ_LOCAL_FILE_SIZE_FAILED,
                    e
            );
            return 0L;
        }
    }

    private String extractParentDirectory(String remoteFilePath) {
        int lastSlashIndex = remoteFilePath.lastIndexOf('/');
        if (lastSlashIndex <= 0) {
            return "/";
        }
        return remoteFilePath.substring(0, lastSlashIndex);
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
