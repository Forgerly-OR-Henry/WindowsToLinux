package gold.debug.wintolin.attribute.language;

public class ACpp {
    private boolean isGCC; // 是否使用 GCC 编译
    private String gccVersion; // GCC 版本

    public boolean isGCC() {
        return isGCC;
    }

    public void setGCC(boolean GCC) {
        isGCC = GCC;
    }

    public String getGccVersion() {
        return gccVersion;
    }

    public void setGccVersion(String gccVersion) {
        this.gccVersion = gccVersion;
    }
}
