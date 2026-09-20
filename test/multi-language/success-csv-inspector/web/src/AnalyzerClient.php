<?php
namespace CsvInspector;

final class AnalyzerClient
{
    public function run(JobStore $store, array $job): array
    {
        $file = fopen($job["path"], "rb");
        if (!$file) {
            throw new \RuntimeException("数据集文件缺失");
        }
        $url =
            rtrim(getenv("ANALYZER_URL") ?: "http://127.0.0.1:18131", "/") .
            "/analyze";
        $curl = curl_init($url);
        $buffer = "";
        $sequence = 0;
        $started = false;
        $end = null;
        $issues = [];
        $seen = 0;
        $rows = 0;
        $columns = [];
        $failure = null;
        $lastHeartbeat = 0;
        $protocol = null;
        $consume = function (array $record) use (
            &$sequence,
            &$started,
            &$end,
            &$issues,
            &$seen,
            &$rows,
            &$columns,
            $store,
            $job,
        ) {
            if (
                ($record["protocolVersion"] ?? null) !== 2 ||
                ($record["sequence"] ?? null) !== $sequence++ ||
                !isset($record["type"]) ||
                $end !== null
            ) {
                throw new \RuntimeException("分析协议版本、序号或结束标记错误");
            }
            if ($sequence > (int) (getenv("MAX_PROTOCOL_RECORDS") ?: 2000000)) {
                throw new \RuntimeException(
                    "分析输出超过 MAX_PROTOCOL_RECORDS",
                );
            }
            $type = $record["type"];
            if (!$started) {
                if (
                    $type !== "start" ||
                    !isset($record["columns"]) ||
                    !is_array($record["columns"]) ||
                    !array_is_list($record["columns"]) ||
                    count($record["columns"]) < 1 ||
                    count($record["columns"]) > 32
                ) {
                    throw new \RuntimeException("缺少分析开始记录或列信息");
                }
                foreach ($record["columns"] as $column) {
                    if (
                        !is_string($column) ||
                        $column === "" ||
                        strlen($column) > 320
                    ) {
                        throw new \RuntimeException("列信息无效");
                    }
                }
                $columns = $record["columns"];
                $started = true;
                return;
            }
            if ($type === "issue") {
                foreach (["row", "column", "code", "value", "detail"] as $key) {
                    if (!array_key_exists($key, $record)) {
                        throw new \RuntimeException("问题记录缺少字段 " . $key);
                    }
                }
                if (
                    !is_int($record["row"]) ||
                    $record["row"] < 2 ||
                    !is_string($record["column"]) ||
                    !is_string($record["value"]) ||
                    !is_string($record["detail"]) ||
                    strlen($record["value"]) > 800 ||
                    strlen($record["detail"]) > 2000 ||
                    !in_array(
                        $record["code"],
                        [
                            "required",
                            "number",
                            "date",
                            "range",
                            "enum",
                            "duplicate",
                        ],
                        true,
                    )
                ) {
                    throw new \RuntimeException("问题记录字段无效");
                }
                $groups = array_map(
                    fn($group) => implode(",", $group),
                    json_decode($job["rules"], true)["unique"],
                );
                if (
                    !in_array($record["column"], $columns, true) &&
                    !in_array($record["column"], $groups, true)
                ) {
                    throw new \RuntimeException("问题引用未知列");
                }
                $seen++;
                $issues[] = $record;
                if (count($issues) >= 500) {
                    $store->append($job, $issues, $rows);
                    $issues = [];
                }
            } elseif ($type === "progress" || $type === "end") {
                $count =
                    $type === "progress"
                        ? $record["issues"] ?? null
                        : $record["issueCount"] ?? null;
                if (
                    !isset($record["rows"]) ||
                    !is_int($record["rows"]) ||
                    $record["rows"] < $rows ||
                    $record["rows"] > (int) (getenv("MAX_ROWS") ?: 500000) ||
                    $count !== $seen
                ) {
                    throw new \RuntimeException("分析进度或问题数量不一致");
                }
                $rows = $record["rows"];
                $store->append($job, $issues, $rows);
                $issues = [];
                if ($type === "end") {
                    if (
                        !isset(
                            $record["validRows"],
                            $record["statistics"],
                            $record["columns"],
                        ) ||
                        !is_int($record["validRows"]) ||
                        $record["validRows"] < 0 ||
                        $record["validRows"] > $rows ||
                        !is_array($record["statistics"]) ||
                        $record["columns"] !== $columns ||
                        count($record["statistics"]) !== 6
                    ) {
                        throw new \RuntimeException("结束记录缺少有效汇总");
                    }
                    foreach (
                        [
                            "required",
                            "number",
                            "date",
                            "range",
                            "enum",
                            "duplicate",
                        ]
                        as $code
                    ) {
                        if (
                            !isset($record["statistics"][$code]) ||
                            !is_int($record["statistics"][$code]) ||
                            $record["statistics"][$code] < 0
                        ) {
                            throw new \RuntimeException("问题分类汇总无效");
                        }
                    }
                    if (array_sum($record["statistics"]) !== $seen) {
                        throw new \RuntimeException("分类数量不一致");
                    }
                    unset(
                        $record["type"],
                        $record["sequence"],
                        $record["protocolVersion"],
                    );
                    $end = $record;
                }
            } elseif ($type === "error") {
                throw new \RuntimeException(
                    "Python 检查失败: " . ($record["error"] ?? "未知错误"),
                );
            } else {
                throw new \RuntimeException("未知分析消息类型");
            }
        };
        curl_setopt_array($curl, [
            CURLOPT_UPLOAD => true,
            CURLOPT_CUSTOMREQUEST => "POST",
            CURLOPT_INFILE => $file,
            CURLOPT_INFILESIZE => filesize($job["path"]),
            CURLOPT_HTTPHEADER => [
                "Content-Type: text/csv; charset=utf-8",
                "Expect:",
                "X-Sample-Protocol: 2",
                "X-CSV-Rules: " . base64_encode($job["rules"]),
            ],
            CURLOPT_CONNECTTIMEOUT_MS => 2000,
            CURLOPT_TIMEOUT_MS =>
                (int) (getenv("ANALYZER_TIMEOUT_MS") ?: 120000),
            CURLOPT_NOPROGRESS => false,
            CURLOPT_HEADERFUNCTION => function ($ch, $line) use (&$protocol) {
                if (stripos($line, "X-Sample-Protocol:") === 0) {
                    $protocol = trim(substr($line, 18));
                }
                return strlen($line);
            },
            CURLOPT_XFERINFOFUNCTION => function () use (
                $store,
                $job,
                &$lastHeartbeat,
            ) {
                if (microtime(true) - $lastHeartbeat > 0.2) {
                    $store->heartbeat();
                    $lastHeartbeat = microtime(true);
                    if ($store->cancelled($job["id"])) {
                        return 1;
                    }
                }
                return 0;
            },
            CURLOPT_WRITEFUNCTION => function ($ch, $chunk) use (
                &$buffer,
                &$failure,
                $consume,
                $store,
                $job,
            ) {
                try {
                    if ($store->cancelled($job["id"])) {
                        throw new \RuntimeException("检查已取消");
                    }
                    $buffer .= $chunk;
                    while (($newline = strpos($buffer, "\n")) !== false) {
                        if ($newline > 65536) {
                            throw new \RuntimeException("分析记录超过 64 KiB");
                        }
                        $line = substr($buffer, 0, $newline);
                        $buffer = substr($buffer, $newline + 1);
                        $record = json_decode(
                            $line,
                            true,
                            32,
                            JSON_THROW_ON_ERROR,
                        );
                        if (!is_array($record)) {
                            throw new \RuntimeException("分析记录不是对象");
                        }
                        $consume($record);
                    }
                    if (strlen($buffer) > 65536) {
                        throw new \RuntimeException("分析记录超过 64 KiB");
                    }
                    return strlen($chunk);
                } catch (\Throwable $e) {
                    $failure = $e;
                    return 0;
                }
            },
        ]);
        try {
            $ok = curl_exec($curl);
            $status = curl_getinfo($curl, CURLINFO_HTTP_CODE);
            if ($failure) {
                throw $failure;
            }
            if (!$ok) {
                throw new \RuntimeException(
                    "分析服务调用失败: " . curl_error($curl),
                );
            }
            if (
                $status !== 200 ||
                $protocol !== "2" ||
                $buffer !== "" ||
                $end === null
            ) {
                throw new \RuntimeException(
                    "分析服务响应缺少完整协议或结束标记",
                );
            }
            return $end;
        } finally {
            curl_close($curl);
            fclose($file);
        }
    }
}
