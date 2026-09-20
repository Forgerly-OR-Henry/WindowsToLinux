<?php
declare(strict_types=1);
namespace Fixture;
final class Configuration {
    public function __construct(public readonly string $mode, public readonly int $status, public readonly string $label) {}
    public static function load(): self {
        $mode = 'json';
        $label = getenv('FIXTURE_LABEL');
        return new self($mode, 200, $mode === 'config'
            ? ($label === false ? 'runtime-config-default' : $label) : 'deployment-smoke-ok');
    }
}
