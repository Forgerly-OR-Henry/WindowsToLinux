<?php
$path = parse_url($_SERVER["REQUEST_URI"], PHP_URL_PATH);
if (
    $path === "/" ||
    (!str_starts_with($path, "/api/") && $path != "/healthz")
) {
    return false;
}
require __DIR__ . "/vendor/autoload.php";
header("Content-Type: application/json; charset=utf-8");
header("X-Sample-Protocol: 2");
try {
    $store = new CsvInspector\JobStore();
    $method = $_SERVER["REQUEST_METHOD"];
    $input = function () {
        if ((int) ($_SERVER["CONTENT_LENGTH"] ?? 0) > 65536) {
            throw new CsvInspector\ApiError("JSON 请求过大", 400);
        }
        $value = json_decode(
            file_get_contents("php://input"),
            false,
            64,
            JSON_THROW_ON_ERROR,
        );
        if (!is_object($value)) {
            throw new CsvInspector\ApiError("需要 JSON 对象", 400);
        }
        return $value;
    };
    $offset = filter_var($_GET["offset"] ?? 0, FILTER_VALIDATE_INT);
    $limit = filter_var($_GET["limit"] ?? 25, FILTER_VALIDATE_INT);
    if ($offset === false || $limit === false) {
        throw new CsvInspector\ApiError("分页参数必须为整数", 400);
    }
    if ($path === "/healthz") {
        $result = [
            "status" => "ok",
            "component" => "php-queue",
            "version" => 2,
        ];
    } elseif ($path === "/api/worker" && $method === "GET") {
        $result = $store->one(
            "SELECT pid,heartbeat FROM worker WHERE id=1",
        ) ?: ["pid" => null, "heartbeat" => null];
        $result["responsive"] =
            $result["heartbeat"] !== null && time() - $result["heartbeat"] < 10;
    } elseif ($path === "/api/datasets" && $method === "GET") {
        $result = $store->page("datasets", $offset, $limit);
    } elseif ($path === "/api/datasets" && $method === "POST") {
        $result = $store->upload(
            CsvInspector\Rules::name($_GET["name"] ?? null),
            fopen("php://input", "rb"),
            (int) ($_SERVER["CONTENT_LENGTH"] ?? 0),
        );
    } elseif ($path === "/api/templates" && $method === "GET") {
        $result = [
            "items" => $store
                ->execute("SELECT * FROM templates ORDER BY created,id")
                ->fetchAll(),
        ];
        foreach ($result["items"] as &$row) {
            $row["rules"] = json_decode($row["rules"]);
        }
        unset($row);
    } elseif ($path === "/api/templates" && $method === "POST") {
        $result = $store->createTemplate($input());
    } elseif ($path === "/api/jobs" && $method === "GET") {
        $result = $store->page("jobs", $offset, $limit);
    } elseif ($path === "/api/jobs" && $method === "POST") {
        $result = $store->create($input());
    } elseif ($path === "/api/compare" && $method === "GET") {
        $result = $store->comparison($_GET["left"] ?? "", $_GET["right"] ?? "");
    } elseif (
        preg_match(
            '#^/api/jobs/([a-f0-9]{24})(?:/(issues|cancel|retry|export))?$#',
            $path,
            $match,
        )
    ) {
        $id = $match[1];
        $action = $match[2] ?? "";
        if ($method === "GET" && $action === "") {
            $result = $store->job($id);
            $result["events"] = $store
                ->execute(
                    "SELECT attempt,action,detail,created FROM events WHERE job_id=? ORDER BY id",
                    [$id],
                )
                ->fetchAll();
        } elseif ($method === "GET" && $action === "issues") {
            $result = $store->page("issues", $offset, $limit, $id);
        } elseif (
            $method === "POST" &&
            in_array($action, ["retry", "cancel"], true)
        ) {
            $result = $store->action($id, $action, $input());
        } elseif ($method === "GET" && $action === "export") {
            $job = $store->job($id);
            if ($job["status"] !== "completed") {
                throw new CsvInspector\ApiError("仅成功报告可以导出", 409);
            }
            $file = $store
                ->execute("SELECT report FROM jobs WHERE id=?", [$id])
                ->fetchColumn();
            if (!$file || !is_file($file)) {
                throw new \RuntimeException("报告文件缺失");
            }
            header(
                'Content-Disposition: attachment; filename="report-' .
                    $id .
                    '.json"',
            );
            header("Content-Length: " . filesize($file));
            readfile($file);
            exit();
        } else {
            throw new CsvInspector\ApiError("接口不存在", 404);
        }
    } else {
        throw new CsvInspector\ApiError("接口不存在", 404);
    }
    $body = CsvInspector\JobStore::json($result);
} catch (CsvInspector\ApiError $e) {
    http_response_code($e->getCode());
    $body = json_encode(["error" => $e->getMessage()], JSON_UNESCAPED_UNICODE);
} catch (JsonException $e) {
    http_response_code(400);
    $body = json_encode(["error" => "JSON 格式错误"]);
} catch (Throwable $e) {
    error_log((string) $e);
    http_response_code(500);
    $body = json_encode(["error" => "存储或内部处理失败"]);
}
header("Content-Length: " . strlen($body));
echo $body;
