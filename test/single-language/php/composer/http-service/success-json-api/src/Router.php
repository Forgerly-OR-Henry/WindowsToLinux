<?php
declare(strict_types=1);
namespace Fixture;
final class Router {
    public function __construct(private readonly Configuration $configuration) {}
    public function route(string $target): array {
        $status = $this->configuration->status;
        $body = $this->configuration->label;
        $type = 'text/plain; charset=utf-8';
        $path = parse_url($target, PHP_URL_PATH);
        if ($status !== 503 && ($path === '/api/summary' || $this->configuration->mode === 'json')) {
            parse_str(parse_url($target, PHP_URL_QUERY) ?? '', $query);
            try {
                $raw = $path === '/api/summary' ? ($query['values'] ?? null) : null;
                if ($raw !== null && !is_string($raw)) throw new \InvalidArgumentException('invalid-values');
                $body = (new SummaryService())->summarize($raw)->toJson();
                $type = 'application/json; charset=utf-8';
            } catch (\InvalidArgumentException $error) {
                $status = 400; $body = 'invalid-values';
            }
        }
        return ['status' => $status, 'type' => $type, 'body' => $body];
    }
}
