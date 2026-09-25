<?php
declare(strict_types=1);
foreach (['Configuration', 'Summary', 'SummaryService', 'Router'] as $module) {
    require_once dirname(__DIR__) . '/src/' . $module . '.php';
}

$response = (new Fixture\Router(Fixture\Configuration::load()))->route($_SERVER['REQUEST_URI'] ?? '/');
http_response_code($response['status']);
header('Content-Type: ' . $response['type']);
header('Content-Length: ' . strlen($response['body']));
echo $response['body'];
