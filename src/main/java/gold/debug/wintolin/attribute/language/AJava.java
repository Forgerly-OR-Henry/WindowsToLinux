package gold.debug.wintolin.attribute.language;

public class AJava {
    private String jdkVersion; // JDK 版本
    private boolean isMaven; // 是否使用 Maven 管理包
    private String mavenVersion; // Maven 版本

    public String getJdkVersion() {
        return jdkVersion;
    }

    public void setJdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
    }

    public boolean isMaven() {
        return isMaven;
    }

    public void setMaven(boolean maven) {
        isMaven = maven;
    }

    public String getMavenVersion() {
        return mavenVersion;
    }

    public void setMavenVersion(String mavenVersion) {
        this.mavenVersion = mavenVersion;
    }
}
