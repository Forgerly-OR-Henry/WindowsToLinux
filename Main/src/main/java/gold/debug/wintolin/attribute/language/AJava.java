package gold.debug.wintolin.attribute.language;

public class AJava {
    // 是否为Maven项目
    private boolean maven;

    // 项目要求的Java版本（优先来自pom.xml）
    private String projectJavaVersion;

    // 项目Java版本来源：pom.xml / compiler-plugin / unknown
    private String projectJavaVersionSource;

    // 本机运行时Java版本（java -version）
    private String localJavaVersion;

    // 本机编译器Java版本（javac -version）
    private String localJavacVersion;

    // 本机Maven版本
    private String localMavenVersion;

    // Maven版本来源：mvnw / PATH / MAVEN_HOME / M2_HOME
    private String localMavenVersionSource;

    // Java版本来源：PATH / JAVA_HOME
    private String localJavaVersionSource;

    // 是否存在Maven Wrapper
    private boolean hasMavenWrapper;

    // 是否兼容
    private boolean versionCompatible;

    // 兼容性说明
    private String compatibilityMessage;

    public boolean isMaven() {
        return maven;
    }

    public void setMaven(boolean maven) {
        this.maven = maven;
    }

    public String getProjectJavaVersion() {
        return projectJavaVersion;
    }

    public void setProjectJavaVersion(String projectJavaVersion) {
        this.projectJavaVersion = projectJavaVersion;
    }

    public String getProjectJavaVersionSource() {
        return projectJavaVersionSource;
    }

    public void setProjectJavaVersionSource(String projectJavaVersionSource) {
        this.projectJavaVersionSource = projectJavaVersionSource;
    }

    public String getLocalJavaVersion() {
        return localJavaVersion;
    }

    public void setLocalJavaVersion(String localJavaVersion) {
        this.localJavaVersion = localJavaVersion;
    }

    public String getLocalJavacVersion() {
        return localJavacVersion;
    }

    public void setLocalJavacVersion(String localJavacVersion) {
        this.localJavacVersion = localJavacVersion;
    }

    public String getLocalMavenVersion() {
        return localMavenVersion;
    }

    public void setLocalMavenVersion(String localMavenVersion) {
        this.localMavenVersion = localMavenVersion;
    }

    public String getLocalMavenVersionSource() {
        return localMavenVersionSource;
    }

    public void setLocalMavenVersionSource(String localMavenVersionSource) {
        this.localMavenVersionSource = localMavenVersionSource;
    }

    public String getLocalJavaVersionSource() {
        return localJavaVersionSource;
    }

    public void setLocalJavaVersionSource(String localJavaVersionSource) {
        this.localJavaVersionSource = localJavaVersionSource;
    }

    public boolean isHasMavenWrapper() {
        return hasMavenWrapper;
    }

    public void setHasMavenWrapper(boolean hasMavenWrapper) {
        this.hasMavenWrapper = hasMavenWrapper;
    }

    public boolean isVersionCompatible() {
        return versionCompatible;
    }

    public void setVersionCompatible(boolean versionCompatible) {
        this.versionCompatible = versionCompatible;
    }

    public String getCompatibilityMessage() {
        return compatibilityMessage;
    }

    public void setCompatibilityMessage(String compatibilityMessage) {
        this.compatibilityMessage = compatibilityMessage;
    }
}
