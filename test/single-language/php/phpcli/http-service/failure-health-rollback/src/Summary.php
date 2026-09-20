<?php
declare(strict_types=1);
namespace Fixture;
final class Summary implements \JsonSerializable {
    public function __construct(private readonly array $items) {}
    public function jsonSerialize(): array {
        return ['status' => 'ok', 'items' => $this->items, 'total' => array_sum($this->items)];
    }
    public function toJson(): string {
        return json_encode($this, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);
    }
}
