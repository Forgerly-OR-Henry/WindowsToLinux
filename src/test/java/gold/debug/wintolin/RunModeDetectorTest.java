package gold.debug.wintolin;

import gold.debug.wintolin.tools.system.windows.RunModeDetector;

public class RunModeDetectorTest {
    public static void main(String[] args) {
        RunModeDetector.RunMode mode = RunModeDetector.detect(RunModeDetectorTest.class);
        System.out.println("RunMode = " + mode);

        switch (mode) {
            case RUN_CLASS -> System.out.println("当前以 class 方式运行（IDE/编译输出目录）");
            case RUN_JAR -> System.out.println("当前以 java -jar 方式运行");
            case RUN_APP -> System.out.println("当前以 jpackage 打包后的 app-image 方式运行");
        }
    }
}
