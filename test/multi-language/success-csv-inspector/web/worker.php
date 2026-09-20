<?php
require __DIR__ . "/vendor/autoload.php";
$store = new CsvInspector\JobStore();
$lock = fopen($store->directory . "/worker.lock", "c+b");
if (!$lock || !flock($lock, LOCK_EX | LOCK_NB)) {
    fwrite(STDERR, "此数据目录已有工作进程\n");
    exit(2);
}
$store->recover();
$poll = (int) (getenv("WORKER_POLL_MS") ?: 200);
try {
    while (true) {
        $store->heartbeat();
        $job = $store->claim();
        if (!$job) {
            usleep(max(20, $poll) * 1000);
            continue;
        }
        try {
            CsvInspector\FaultGate::wait("job-running");
            $summary = (new CsvInspector\AnalyzerClient())->run($store, $job);
            $store->publish($job, $summary);
        } catch (Throwable $e) {
            $store->fail($job["id"], $e->getMessage());
            fwrite(STDERR, $job["id"] . ": " . $e->getMessage() . "\n");
            foreach (
                glob($store->directory . "/reports/" . $job["id"] . ".*")
                as $file
            ) {
                if (
                    !$store->one(
                        "SELECT id FROM jobs WHERE report=? AND status='completed'",
                        [$file],
                    )
                ) {
                    unlink($file);
                }
            }
        }
    }
} finally {
    flock($lock, LOCK_UN);
    fclose($lock);
}
