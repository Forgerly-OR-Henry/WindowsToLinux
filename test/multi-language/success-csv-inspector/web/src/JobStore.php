<?php
namespace CsvInspector;

use PDO;

final class JobStore
{
    public readonly PDO $db;
    public readonly string $directory;

    public function __construct()
    {
        $this->directory = getenv("DATA_DIR") ?: __DIR__ . "/../data";
        foreach (["", "/datasets", "/reports"] as $part) {
            if (
                !is_dir($this->directory . $part) &&
                !mkdir($this->directory . $part, 0777, true) &&
                !is_dir($this->directory . $part)
            ) {
                throw new \RuntimeException("无法创建数据目录");
            }
        }
        $this->db = new PDO(
            "sqlite:" . $this->directory . "/csv.sqlite",
            null,
            null,
            [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            ],
        );
        $this->db->exec(
            "PRAGMA busy_timeout=10000; PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL",
        );
        $version = (int) $this->db->query("PRAGMA user_version")->fetchColumn();
        if ($version !== 0 && $version !== 2) {
            throw new \RuntimeException("此样例需要新的 v2 数据目录");
        }
        $this->db
            ->exec("CREATE TABLE IF NOT EXISTS datasets(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL UNIQUE,size INTEGER NOT NULL CHECK(size>0),sha256 TEXT NOT NULL,created TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS templates(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE,rules TEXT NOT NULL,created TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL REFERENCES datasets(id),template_id TEXT NOT NULL REFERENCES templates(id),rules TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN('queued','running','completed','failed','cancelled','interrupted')),attempt INTEGER NOT NULL DEFAULT 1,progress_rows INTEGER NOT NULL DEFAULT 0,issue_count INTEGER NOT NULL DEFAULT 0,cancel_requested INTEGER NOT NULL DEFAULT 0,summary TEXT,report TEXT,error TEXT,created TEXT NOT NULL,updated TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS jobs_status ON jobs(status,created);
            CREATE TABLE IF NOT EXISTS issues(job_id TEXT NOT NULL REFERENCES jobs(id),attempt INTEGER NOT NULL,sequence INTEGER NOT NULL,row INTEGER NOT NULL,column_name TEXT NOT NULL,code TEXT NOT NULL,value TEXT NOT NULL,detail TEXT NOT NULL,PRIMARY KEY(job_id,attempt,sequence));
            CREATE INDEX IF NOT EXISTS issues_codes ON issues(job_id,attempt,code,row);
            CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,job_id TEXT NOT NULL REFERENCES jobs(id),attempt INTEGER NOT NULL,action TEXT NOT NULL,detail TEXT NOT NULL,created TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,job_id TEXT NOT NULL REFERENCES jobs(id));
            CREATE TABLE IF NOT EXISTS worker(id INTEGER PRIMARY KEY CHECK(id=1),pid INTEGER NOT NULL,heartbeat INTEGER NOT NULL);
            PRAGMA user_version=2;");
        $basic = [
            "required" => ["id", "name"],
            "types" => ["age" => "number", "date" => "date"],
            "ranges" => ["age" => ["min" => 0, "max" => 120]],
            "enums" => ["team" => ["red", "blue"]],
            "unique" => [["id", "team"]],
        ];
        $minimal = [
            "required" => ["id", "name"],
            "types" => (object) [],
            "ranges" => (object) [],
            "enums" => (object) [],
            "unique" => [],
        ];
        foreach (
            [
                "basic" => ["基础检查", $basic],
                "required" => ["仅必填", $minimal],
            ]
            as $id => $entry
        ) {
            $this->execute("INSERT OR IGNORE INTO templates VALUES(?,?,?,?)", [
                $id,
                $entry[0],
                self::json($entry[1]),
                self::now(),
            ]);
        }
        if (!$this->one("SELECT id FROM datasets WHERE id=?", ["demo"])) {
            $file = $this->directory . "/datasets/demo.csv";
            if (!file_exists($file)) {
                copy(__DIR__ . "/../../samples/demo.csv", $file);
            }
            $this->execute(
                "INSERT OR IGNORE INTO datasets VALUES(?,?,?,?,?,?)",
                [
                    "demo",
                    "演示人员.csv",
                    $file,
                    filesize($file),
                    hash_file("sha256", $file),
                    self::now(),
                ],
            );
        }
    }

    public static function now(): string
    {
        return gmdate("Y-m-d\TH:i:s\Z");
    }
    public static function json(mixed $value): string
    {
        return json_encode(
            $value,
            JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR,
        );
    }
    public function execute(string $sql, array $args = []): \PDOStatement
    {
        $s = $this->db->prepare($sql);
        $s->execute($args);
        return $s;
    }
    public function one(string $sql, array $args = []): ?array
    {
        return $this->execute($sql, $args)->fetch() ?: null;
    }
    public function transaction(callable $fn): mixed
    {
        $this->db->exec("BEGIN IMMEDIATE");
        try {
            $value = $fn();
            $this->db->exec("COMMIT");
            return $value;
        } catch (\Throwable $e) {
            $this->db->exec("ROLLBACK");
            throw $e;
        }
    }
    public function event(string $id, string $action, string $detail = ""): void
    {
        $this->execute(
            "INSERT INTO events(job_id,attempt,action,detail,created) SELECT id,attempt,?,?,? FROM jobs WHERE id=?",
            [$action, $detail, self::now(), $id],
        );
    }
    public function job(string $id): array
    {
        $row = $this->one(
            "SELECT j.*,d.name datasetName,t.name templateName FROM jobs j JOIN datasets d ON j.dataset_id=d.id JOIN templates t ON j.template_id=t.id WHERE j.id=?",
            [$id],
        );
        if (!$row) {
            throw new ApiError("任务不存在", 404);
        }
        $row["rules"] = json_decode($row["rules"]);
        $row["summary"] = $row["summary"] ? json_decode($row["summary"]) : null;
        unset($row["report"]);
        return $row;
    }
    public function page(
        string $kind,
        int $offset,
        int $limit,
        string $id = "",
    ): array {
        if ($limit < 1 || $limit > 100 || $offset < 0) {
            throw new ApiError("分页参数超出范围", 400);
        }
        $args = [];
        if ($kind === "issues") {
            $job = $this->job($id);
            $sql = "FROM issues WHERE job_id=? AND attempt=?";
            $args = [$id, $job["attempt"]];
            $select = 'sequence,row,column_name AS "column",code,value,detail';
            $order = "sequence";
        } elseif ($kind === "jobs") {
            $sql = "FROM jobs";
            $select =
                "id,dataset_id,template_id,status,attempt,progress_rows,issue_count,error,created,updated";
            $order = "created DESC,id";
        } else {
            $sql = "FROM datasets";
            $select = "id,name,size,sha256,created";
            $order = "created DESC,id";
        }
        $total = (int) $this->execute(
            "SELECT count(*) " . $sql,
            $args,
        )->fetchColumn();
        return [
            "items" => $this->execute(
                "SELECT $select $sql ORDER BY $order LIMIT $limit OFFSET $offset",
                $args,
            )->fetchAll(),
            "total" => $total,
            "offset" => $offset,
            "limit" => $limit,
        ];
    }
    public function upload(string $name, $input, int $length): array
    {
        if (
            $length < 1 ||
            $length > (int) (getenv("MAX_FILE_BYTES") ?: 134217728)
        ) {
            throw new ApiError("CSV 大小超出限制", 400);
        }
        $id = bin2hex(random_bytes(12));
        $path = $this->directory . "/datasets/" . $id . ".csv";
        $temp = $path . ".tmp";
        $out = fopen($temp, "xb");
        $hash = hash_init("sha256");
        $written = 0;
        try {
            while (!feof($input)) {
                $chunk = fread($input, 65536);
                if ($chunk === false) {
                    throw new ApiError("读取上传失败", 400);
                }
                $written += strlen($chunk);
                if ($written > $length) {
                    throw new ApiError("上传长度不匹配", 400);
                }
                if (fwrite($out, $chunk) !== strlen($chunk)) {
                    throw new \RuntimeException("数据写入失败");
                }
                hash_update($hash, $chunk);
            }
            if ($written !== $length) {
                throw new ApiError("上传中断或长度不匹配", 400);
            }
            fflush($out);
            fsync($out);
            fclose($out);
            $out = null;
            if (!rename($temp, $path)) {
                throw new \RuntimeException("数据集发布失败");
            }
            $sha = hash_final($hash);
            $this->execute("INSERT INTO datasets VALUES(?,?,?,?,?,?)", [
                $id,
                $name,
                $path,
                $length,
                $sha,
                self::now(),
            ]);
            return [
                "id" => $id,
                "name" => $name,
                "size" => $length,
                "sha256" => $sha,
            ];
        } catch (\Throwable $e) {
            if (is_resource($out)) {
                fclose($out);
            }
            if (file_exists($temp)) {
                unlink($temp);
            }
            if (file_exists($path)) {
                unlink($path);
            }
            throw $e;
        }
    }
    public function createTemplate(object $input): array
    {
        $name = Rules::name($input->name ?? null);
        $rules = Rules::validate($input->rules ?? null);
        $id = bin2hex(random_bytes(12));
        if ($this->one("SELECT id FROM templates WHERE name=?", [$name])) {
            throw new ApiError("模板名称已存在", 409);
        }
        $this->execute("INSERT INTO templates VALUES(?,?,?,?)", [
            $id,
            $name,
            self::json($rules),
            self::now(),
        ]);
        return ["id" => $id, "name" => $name, "rules" => $rules];
    }
    public function create(object $input): array
    {
        $key = Rules::requestId($input->requestId ?? null);
        $dataset = $input->datasetId ?? "";
        $template = $input->templateId ?? "";
        if (!is_string($dataset) || !is_string($template)) {
            throw new ApiError("数据集和模板标识必须为字符串", 400);
        }
        $fingerprint = self::json(["create", $dataset, $template]);
        return $this->transaction(function () use (
            $key,
            $dataset,
            $template,
            $fingerprint,
        ) {
            if (
                $old = $this->one("SELECT * FROM requests WHERE id=?", [$key])
            ) {
                if ($old["fingerprint"] !== $fingerprint) {
                    throw new ApiError("幂等标识已用于不同操作", 409);
                }
                return $this->job($old["job_id"]);
            }
            $rules = $this->one("SELECT rules FROM templates WHERE id=?", [
                $template,
            ]);
            if (
                !$rules ||
                !$this->one("SELECT id FROM datasets WHERE id=?", [$dataset])
            ) {
                throw new ApiError("数据集或模板不存在", 404);
            }
            $id = bin2hex(random_bytes(12));
            $now = self::now();
            $this->execute(
                "INSERT INTO jobs(id,dataset_id,template_id,rules,status,created,updated) VALUES(?,?,?,?,'queued',?,?)",
                [$id, $dataset, $template, $rules["rules"], $now, $now],
            );
            $this->execute("INSERT INTO requests VALUES(?,?,?)", [
                $key,
                $fingerprint,
                $id,
            ]);
            $this->event($id, "queued");
            return $this->job($id);
        });
    }
    public function action(string $id, string $action, object $input): array
    {
        $key = Rules::requestId($input->requestId ?? null);
        $fp = self::json([$action, $id]);
        return $this->transaction(function () use ($id, $action, $key, $fp) {
            $job = $this->job($id);
            if (
                $old = $this->one("SELECT * FROM requests WHERE id=?", [$key])
            ) {
                if ($old["fingerprint"] !== $fp) {
                    throw new ApiError("幂等标识已用于不同操作", 409);
                }
                return $job;
            }
            if ($action === "retry") {
                if (
                    !in_array(
                        $job["status"],
                        ["failed", "interrupted", "cancelled"],
                        true,
                    )
                ) {
                    throw new ApiError("只有失败、取消或中断的任务可重试", 409);
                }
                $this->execute(
                    "UPDATE jobs SET status='queued',attempt=attempt+1,cancel_requested=0,progress_rows=0,issue_count=0,summary=NULL,report=NULL,error=NULL,updated=? WHERE id=?",
                    [self::now(), $id],
                );
                $this->execute("DELETE FROM issues WHERE job_id=?", [$id]);
                $this->event($id, "retry");
            } else {
                if (!in_array($job["status"], ["queued", "running"], true)) {
                    throw new ApiError("任务已结束，不能取消", 409);
                }
                $this->execute(
                    "UPDATE jobs SET cancel_requested=1,status=CASE WHEN status='queued' THEN 'cancelled' ELSE status END,updated=? WHERE id=?",
                    [self::now(), $id],
                );
                $this->event($id, "cancel-requested");
            }
            $this->execute("INSERT INTO requests VALUES(?,?,?)", [
                $key,
                $fp,
                $id,
            ]);
            return $this->job($id);
        });
    }
    public function heartbeat(): void
    {
        $this->execute(
            "INSERT INTO worker VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET pid=excluded.pid,heartbeat=excluded.heartbeat",
            [getmypid(), time()],
        );
    }
    public function recover(): void
    {
        $this->transaction(function () {
            foreach (
                $this->execute(
                    "SELECT id FROM jobs WHERE status='running'",
                )->fetchAll()
                as $row
            ) {
                $this->event(
                    $row["id"],
                    "interrupted",
                    "工作进程中断；需要显式重试",
                );
            }
            $this->execute(
                "UPDATE jobs SET status='interrupted',error='工作进程中断；需要显式重试',updated=? WHERE status='running'",
                [self::now()],
            );
        });
        foreach (glob($this->directory . "/reports/*") as $file) {
            if (
                !$this->one(
                    "SELECT id FROM jobs WHERE report=? AND status='completed'",
                    [$file],
                )
            ) {
                unlink($file);
            }
        }
        $this->heartbeat();
    }
    public function claim(): ?array
    {
        return $this->transaction(function () {
            $job = $this->one(
                "SELECT j.*,d.path FROM jobs j JOIN datasets d ON j.dataset_id=d.id WHERE status='queued' ORDER BY created,id LIMIT 1",
            );
            if (!$job) {
                return null;
            }
            $this->execute(
                "UPDATE jobs SET status='running',updated=? WHERE id=?",
                [self::now(), $job["id"]],
            );
            $this->event($job["id"], "running");
            return $job;
        });
    }
    public function cancelled(string $id): bool
    {
        return (bool) $this->execute(
            "SELECT cancel_requested FROM jobs WHERE id=?",
            [$id],
        )->fetchColumn();
    }
    public function append(array $job, array $issues, int $rows): void
    {
        $this->transaction(function () use ($job, $issues, $rows) {
            foreach ($issues as $i) {
                $this->execute("INSERT INTO issues VALUES(?,?,?,?,?,?,?,?)", [
                    $job["id"],
                    $job["attempt"],
                    $i["sequence"],
                    $i["row"],
                    $i["column"],
                    $i["code"],
                    $i["value"],
                    $i["detail"],
                ]);
            }
            $this->execute(
                "UPDATE jobs SET progress_rows=max(progress_rows,?),issue_count=issue_count+?,updated=? WHERE id=?",
                [$rows, count($issues), self::now(), $job["id"]],
            );
        });
        $this->heartbeat();
    }
    public function fail(string $id, string $message): void
    {
        $this->transaction(function () use ($id, $message) {
            $status = $this->cancelled($id) ? "cancelled" : "failed";
            $this->execute(
                "UPDATE jobs SET status=?,error=?,summary=NULL,report=NULL,updated=? WHERE id=?",
                [$status, mb_substr($message, 0, 500), self::now(), $id],
            );
            $this->event($id, $status, $message);
        });
    }
    public function publish(array $job, array $summary): void
    {
        $args = [$job["id"], $job["attempt"]];
        $counts = $this->execute(
            "SELECT count(*) total,count(DISTINCT row) bad,max(row) maxRow FROM issues WHERE job_id=? AND attempt=?",
            $args,
        )->fetch();
        if (
            $counts["total"] !== $summary["issueCount"] ||
            $summary["validRows"] !== $summary["rows"] - $counts["bad"] ||
            ($counts["maxRow"] ?? 0) > $summary["rows"] + 1
        ) {
            throw new \RuntimeException("Python 统计与已保存问题明细不一致");
        }
        $byCode = array_fill_keys(
            ["required", "number", "date", "range", "enum", "duplicate"],
            0,
        );
        foreach (
            $this->execute(
                "SELECT code,count(*) n FROM issues WHERE job_id=? AND attempt=? GROUP BY code",
                $args,
            )
            as $row
        ) {
            $byCode[$row["code"]] = $row["n"];
        }
        if ($byCode != $summary["statistics"]) {
            throw new \RuntimeException("Python 分类统计不一致");
        }
        $report =
            $this->directory .
            "/reports/" .
            $job["id"] .
            "." .
            $job["attempt"] .
            ".json";
        $temp = $report . ".tmp";
        $out = fopen($temp, "wb");
        try {
            fwrite(
                $out,
                '{"protocolVersion":2,"jobId":' .
                    self::json($job["id"]) .
                    ',"attempt":' .
                    $job["attempt"] .
                    ',"rules":' .
                    $job["rules"] .
                    ',"summary":' .
                    self::json($summary) .
                    ',"issues":[',
            );
            $first = true;
            foreach (
                $this->execute(
                    'SELECT row,column_name AS "column",code,value,detail FROM issues WHERE job_id=? AND attempt=? ORDER BY sequence',
                    $args,
                )
                as $issue
            ) {
                if (!$first) {
                    fwrite($out, ",");
                }
                $first = false;
                fwrite($out, self::json($issue));
            }
            fwrite($out, "]}");
            fflush($out);
            fsync($out);
        } finally {
            fclose($out);
        }
        FaultGate::wait("before-report");
        $this->transaction(function () use ($job, $summary, $temp, $report) {
            if ($this->cancelled($job["id"])) {
                throw new \RuntimeException("检查已取消");
            }
            if (!rename($temp, $report)) {
                throw new \RuntimeException("报告发布失败");
            }
            $this->execute(
                "UPDATE jobs SET status='completed',progress_rows=?,summary=?,report=?,error=NULL,updated=? WHERE id=?",
                [
                    $summary["rows"],
                    self::json($summary),
                    $report,
                    self::now(),
                    $job["id"],
                ],
            );
            $this->event($job["id"], "completed");
        });
    }
    public function comparison(string $left, string $right): array
    {
        $a = $this->job($left);
        $b = $this->job($right);
        if (
            $a["status"] !== "completed" ||
            $b["status"] !== "completed" ||
            $a["dataset_id"] !== $b["dataset_id"]
        ) {
            throw new ApiError("只能比较同一数据集的两个成功报告", 409);
        }
        $deltas = [];
        foreach ($a["summary"]->statistics as $code => $value) {
            $deltas[$code] = $b["summary"]->statistics->$code - $value;
        }
        $difference = function ($from, $to) {
            return (int) $this->execute(
                "SELECT count(*) FROM (SELECT row,column_name,code FROM issues WHERE job_id=? EXCEPT SELECT row,column_name,code FROM issues WHERE job_id=?)",
                [$from, $to],
            )->fetchColumn();
        };
        return [
            "left" => $a,
            "right" => $b,
            "statisticsDelta" => $deltas,
            "added" => $difference($right, $left),
            "resolved" => $difference($left, $right),
        ];
    }
}
