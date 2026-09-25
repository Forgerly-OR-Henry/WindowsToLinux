<?php
namespace CsvInspector;

final class FaultGate
{
    public static function wait(string $point): void
    {
        if (getenv("SAMPLE_FAULT_POINT") !== $point) {
            return;
        }
        $directory = getenv("SAMPLE_FAULT_DIR");
        if (!$directory) {
            throw new \RuntimeException("缺少故障检查目录");
        }
        if (!is_dir($directory)) {
            mkdir($directory, 0777, true);
        }
        file_put_contents(
            $directory . "/" . $point . ".ready",
            (string) getmypid(),
        );
        $deadline = microtime(true) + 60;
        while (!file_exists($directory . "/" . $point . ".release")) {
            if (microtime(true) > $deadline) {
                throw new \RuntimeException("故障检查等待超时");
            }
            usleep(20000);
        }
    }
}
