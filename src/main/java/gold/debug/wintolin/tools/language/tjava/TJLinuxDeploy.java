package gold.debug.wintolin.tools.language.tjava;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import gold.debug.wintolin.attribute.language.AJava;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Linux 环境部署
 */
final class TJLinuxDeploy {

    private static final String ERROR_NULL_JAVA = "ERROR_NULL_JAVA";
    private static final String ERROR_NULL_LINUX = "ERROR_NULL_LINUX";
    private static final String ERROR_NULL_SESSION = "ERROR_NULL_SESSION";
    private static final String ERROR_SESSION_DISCONNECTED = "ERROR_SESSION_DISCONNECTED";
    private static final String ERROR_UNSUPPORTED_DISTRO = "ERROR_UNSUPPORTED_DISTRO";
    private static final String ERROR_REMOTE_COMMAND_FAILED = "ERROR_REMOTE_COMMAND_FAILED";
    private static final String ERROR_REMOTE_IO = "ERROR_REMOTE_IO";
    private static final String ERROR_JAVA_VERSION_EMPTY = "ERROR_JAVA_VERSION_EMPTY";
    private static final String ERROR_DEPLOY_FAILED = "ERROR_DEPLOY_FAILED";
    private static final String ERROR_ROOT_PASSWORD_EMPTY = "ERROR_ROOT_PASSWORD_EMPTY";

    private static final Method METHOD_DEPLOY =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "deploy", AJava.class, ALinux.class);

    private static final Method METHOD_VALIDATE_INPUT =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "validateInput", AJava.class, ALinux.class);

    private static final Method METHOD_ENSURE_DISTRIBUTION =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "ensureDistribution", ALinux.class);

    private static final Method METHOD_EXECUTE =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "execute", Session.class, String.class, boolean.class, String.class);

    private static final Method METHOD_BUILD_INSTALL_COMMAND =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "buildInstallCommand", AJava.class, ALinux.class);

    private static final Method METHOD_BUILD_ENV_COMMAND =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "buildEnvCommand", ALinux.class);

    private static final Method METHOD_RESOLVE_REMOTE_JAVA_HOME =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "resolveRemoteJavaHome", Session.class, String.class);

    private static final Method METHOD_RESOLVE_REMOTE_MAVEN_HOME =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "resolveRemoteMavenHome", Session.class);

    private static final Method METHOD_NORMALIZE_JAVA_MAJOR =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "normalizeJavaMajor", String.class);

    private static final Method METHOD_DETECT_DISTRIBUTION_FROM_REMOTE =
            MethodUtils.getCurrentMethod(TJLinuxDeploy.class, "detectDistributionFromRemote", Session.class);

    private TJLinuxDeploy() {
        throw new UnsupportedOperationException("TJLinuxDeploy is a utility class.");
    }

    static void deploy(AJava aJava, ALinux linux) {
        try {
            validateInput(aJava, linux);
            ensureDistribution(linux);

            Session session = linux.getSession();

            String installCommand = buildInstallCommand(aJava, linux);
            execute(session, installCommand, true, linux.getPassword());

            String javaHome = resolveRemoteJavaHome(session, aJava.getProjectJavaVersion());
            String mavenHome = resolveRemoteMavenHome(session);

            String envCommand = buildEnvCommand(javaHome, mavenHome);
            execute(session, envCommand, true, linux.getPassword());

        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            MyException.fail(
                    TJLinuxDeploy.class,
                    METHOD_DEPLOY,
                    "Linux Java/Maven 环境部署失败。",
                    ERROR_DEPLOY_FAILED,
                    e
            );
        }
    }

    private static void validateInput(AJava aJava, ALinux linux) {
        if (aJava == null) {
            MyException.fail(TJLinuxDeploy.class, METHOD_VALIDATE_INPUT, "AJava is null", ERROR_NULL_JAVA);
        }

        if (linux == null) {
            MyException.fail(TJLinuxDeploy.class, METHOD_VALIDATE_INPUT, "ALinux is null", ERROR_NULL_LINUX);
        }

        if (linux.getSession() == null) {
            MyException.fail(TJLinuxDeploy.class, METHOD_VALIDATE_INPUT, "Linux session is null", ERROR_NULL_SESSION);
        }

        if (!linux.getSession().isConnected()) {
            MyException.fail(TJLinuxDeploy.class, METHOD_VALIDATE_INPUT, "Linux session is disconnected", ERROR_SESSION_DISCONNECTED);
        }

        if (isBlank(aJava.getProjectJavaVersion()) && isBlank(aJava.getLocalJavaVersion()) && isBlank(aJava.getLocalJavacVersion())) {
            MyException.fail(TJLinuxDeploy.class, METHOD_VALIDATE_INPUT, "Java version is empty", ERROR_JAVA_VERSION_EMPTY);
        }
    }

    private static void ensureDistribution(ALinux linux) {
        if (linux.getLinuxDistribution() != null && linux.getLinuxDistribution() != ALinux.LinuxDistribution.UNKNOWN) {
            return;
        }

        ALinux.LinuxDistribution detected = detectDistributionFromRemote(linux.getSession());
        linux.setLinuxDistribution(detected);

        if (detected == ALinux.LinuxDistribution.UNKNOWN) {
            MyException.fail(
                    TJLinuxDeploy.class,
                    METHOD_ENSURE_DISTRIBUTION,
                    "Unsupported or unknown Linux distribution",
                    ERROR_UNSUPPORTED_DISTRO
            );
        }
    }

    private static String buildInstallCommand(AJava aJava, ALinux linux) {
        String major = normalizeJavaMajor(firstNonBlank(
                aJava.getProjectJavaVersion(),
                aJava.getLocalJavacVersion(),
                aJava.getLocalJavaVersion()
        ));

        ALinux.LinuxDistribution distro = linux.getLinuxDistribution();
        if (distro == null) {
            distro = ALinux.LinuxDistribution.UNKNOWN;
        }

        return switch (distro) {
            case UBUNTU, DEBIAN, KALI, MINT -> buildAptInstallCommand(major);
            case CENTOS, RHEL, ROCKY, ALMALINUX, ORACLE, AMAZON_LINUX -> buildYumInstallCommand(major);
            case FEDORA -> buildDnfInstallCommand(major);
            case ARCH, MANJARO -> buildPacmanInstallCommand();
            case OPENSUSE -> buildZypperInstallCommand(major);
            case ALPINE -> buildApkInstallCommand(major);
            default -> {
                MyException.fail(
                        TJLinuxDeploy.class,
                        METHOD_BUILD_INSTALL_COMMAND,
                        "Unsupported Linux distribution: " + distro,
                        ERROR_UNSUPPORTED_DISTRO
                );
                yield null;
            }
        };
    }

    private static String buildAptInstallCommand(String major) {
        String javaPackage = "openjdk-" + major + "-jdk";
        return """
                export DEBIAN_FRONTEND=noninteractive
                if [ -f /etc/apt/sources.list ]; then
                  sed -i 's|http://archive.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list 2>/dev/null || true
                  sed -i 's|http://security.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list 2>/dev/null || true
                  sed -i 's|http://deb.debian.org|https://mirrors.aliyun.com|g' /etc/apt/sources.list 2>/dev/null || true
                fi
                apt-get update -y
                apt-get install -y %s maven
                java -version
                javac -version
                mvn -version
                """.formatted(javaPackage);
    }

    private static String buildYumInstallCommand(String major) {
        String javaPackage = "java-" + major + "-openjdk-devel";
        return """
                if command -v dnf >/dev/null 2>&1; then
                  PM=dnf
                else
                  PM=yum
                fi
                $PM -y install curl
                $PM -y install epel-release || true
                $PM -y install %s maven
                java -version
                javac -version
                mvn -version
                """.formatted(javaPackage);
    }

    private static String buildDnfInstallCommand(String major) {
        String javaPackage = "java-" + major + "-openjdk-devel";
        return """
                dnf -y install %s maven
                java -version
                javac -version
                mvn -version
                """.formatted(javaPackage);
    }

    private static String buildPacmanInstallCommand() {
        return """
                pacman -Sy --noconfirm archlinux-keyring
                pacman -Sy --noconfirm jdk-openjdk maven
                java -version
                javac -version
                mvn -version
                """;
    }

    private static String buildZypperInstallCommand(String major) {
        String javaPackage = "java-" + major + "-openjdk-devel";
        return """
                zypper --gpg-auto-import-keys refresh
                zypper -n install %s maven
                java -version
                javac -version
                mvn -version
                """.formatted(javaPackage);
    }

    private static String buildApkInstallCommand(String major) {
        String javaPackage = "openjdk" + major;
        return """
                sed -i 's|https://dl-cdn.alpinelinux.org|https://mirrors.aliyun.com|g' /etc/apk/repositories 2>/dev/null || true
                apk update
                apk add %s maven
                java -version
                javac -version
                mvn -version
                """.formatted(javaPackage);
    }

    private static String buildEnvCommand(String javaHome, String mavenHome) {
        String safeJavaHome = isBlank(javaHome) ? "" : javaHome.trim();
        String safeMavenHome = isBlank(mavenHome) ? "" : mavenHome.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("cat > /etc/profile.d/wintolin_env.sh <<'EOF'\n");
        sb.append("#!/bin/sh\n");

        if (!safeJavaHome.isEmpty()) {
            sb.append("export JAVA_HOME=").append(safeJavaHome).append("\n");
            sb.append("export PATH=$JAVA_HOME/bin:$PATH\n");
        }

        if (!safeMavenHome.isEmpty()) {
            sb.append("export MAVEN_HOME=").append(safeMavenHome).append("\n");
            sb.append("export PATH=$MAVEN_HOME/bin:$PATH\n");
        }

        sb.append("EOF\n");
        sb.append("chmod +x /etc/profile.d/wintolin_env.sh\n");
        sb.append(". /etc/profile.d/wintolin_env.sh || true\n");

        return sb.toString();
    }

    private static String resolveRemoteJavaHome(Session session, String javaVersion) {
        String major = normalizeJavaMajor(javaVersion);
        String command = """
                if command -v javac >/dev/null 2>&1; then
                  readlink -f "$(command -v javac)" | sed 's|/bin/javac$||'
                elif command -v java >/dev/null 2>&1; then
                  readlink -f "$(command -v java)" | sed 's|/bin/java$||'
                else
                  find /usr/lib/jvm -maxdepth 2 -type d 2>/dev/null | grep -E '%s|java-%s|jdk-%s' | head -n 1
                fi
                """.formatted(major, major, major);

        return trimToNull(execute(session, command, false, null));
    }

    private static String resolveRemoteMAVEN_HOMEFallback(Session session) {
        String command = """
                if command -v mvn >/dev/null 2>&1; then
                  readlink -f "$(command -v mvn)" | sed 's|/bin/mvn$||'
                else
                  find /usr/share /opt /usr/local -maxdepth 3 -type d 2>/dev/null | grep -Ei 'maven' | head -n 1
                fi
                """;
        return trimToNull(execute(session, command, false, null));
    }

    private static String resolveRemoteMavenHome(Session session) {
        return resolveRemoteMAVEN_HOMEFallback(session);
    }

    private static ALinux.LinuxDistribution detectDistributionFromRemote(Session session) {
        String output = execute(session, "cat /etc/os-release 2>/dev/null", false, null);
        if (isBlank(output)) {
            return ALinux.LinuxDistribution.UNKNOWN;
        }

        String lower = output.toLowerCase(Locale.ROOT);

        if (lower.contains("ubuntu")) return ALinux.LinuxDistribution.UBUNTU;
        if (lower.contains("debian")) return ALinux.LinuxDistribution.DEBIAN;
        if (lower.contains("kali")) return ALinux.LinuxDistribution.KALI;
        if (lower.contains("linux mint") || lower.contains("mint")) return ALinux.LinuxDistribution.MINT;
        if (lower.contains("fedora")) return ALinux.LinuxDistribution.FEDORA;
        if (lower.contains("rocky")) return ALinux.LinuxDistribution.ROCKY;
        if (lower.contains("almalinux")) return ALinux.LinuxDistribution.ALMALINUX;
        if (lower.contains("oracle linux")) return ALinux.LinuxDistribution.ORACLE;
        if (lower.contains("centos")) return ALinux.LinuxDistribution.CENTOS;
        if (lower.contains("rhel") || lower.contains("red hat")) return ALinux.LinuxDistribution.RHEL;
        if (lower.contains("arch")) return ALinux.LinuxDistribution.ARCH;
        if (lower.contains("manjaro")) return ALinux.LinuxDistribution.MANJARO;
        if (lower.contains("opensuse")) return ALinux.LinuxDistribution.OPENSUSE;
        if (lower.contains("alpine")) return ALinux.LinuxDistribution.ALPINE;
        if (lower.contains("amzn") || lower.contains("amazon linux")) return ALinux.LinuxDistribution.AMAZON_LINUX;

        return ALinux.LinuxDistribution.UNKNOWN;
    }

    private static String execute(Session session, String command, boolean useSudo, String password) {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");

            String finalCommand = command;
            if (useSudo) {
                if (isBlank(password)) {
                    MyException.fail(
                            TJLinuxDeploy.class,
                            METHOD_EXECUTE,
                            "Linux password is empty, cannot run sudo command",
                            ERROR_ROOT_PASSWORD_EMPTY
                    );
                }
                finalCommand = "sudo -S -p '' bash -c " + quoteForBash(command);
            } else {
                finalCommand = "bash -c " + quoteForBash(command);
            }

            channel.setCommand(finalCommand);
            channel.setInputStream(null);

            ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
            channel.setErrStream(errorStream);

            InputStream inputStream = channel.getInputStream();
            OutputStream outputStream = channel.getOutputStream();

            channel.connect();

            if (useSudo) {
                outputStream.write((password + "\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

            ByteArrayOutputStream resultStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];

            while (true) {
                while (inputStream.available() > 0) {
                    int read = inputStream.read(buffer, 0, buffer.length);
                    if (read < 0) {
                        break;
                    }
                    resultStream.write(buffer, 0, read);
                }

                if (channel.isClosed()) {
                    if (inputStream.available() > 0) {
                        continue;
                    }
                    break;
                }

                Thread.sleep(100);
            }

            int exitStatus = channel.getExitStatus();
            String stdout = resultStream.toString(StandardCharsets.UTF_8);
            String stderr = errorStream.toString(StandardCharsets.UTF_8);
            String all = (stdout + "\n" + stderr).trim();

            if (exitStatus != 0) {
                MyException.fail(
                        TJLinuxDeploy.class,
                        METHOD_EXECUTE,
                        "Remote command failed. command=[" + command + "], output=[" + all + "]",
                        ERROR_REMOTE_COMMAND_FAILED
                );
            }

            return all;
        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            MyException.fail(
                    TJLinuxDeploy.class,
                    METHOD_EXECUTE,
                    "Remote command execute error. command=[" + command + "]",
                    ERROR_REMOTE_IO,
                    e
            );
            return null;
        } finally {
            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String normalizeJavaMajor(String version) {
        if (isBlank(version)) {
            return "17";
        }

        String v = version.trim();

        if (v.startsWith("1.")) {
            int secondDot = v.indexOf('.', 2);
            if (secondDot > 2) {
                return v.substring(2, secondDot);
            }
            return v.substring(2);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else if (c == '.') {
                break;
            } else {
                break;
            }
        }

        return sb.isEmpty() ? "17" : sb.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String quoteForBash(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
