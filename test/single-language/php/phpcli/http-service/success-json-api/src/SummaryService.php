<?php
declare(strict_types=1);
namespace Fixture;
final class SummaryService {
    public function summarize(?string $raw): Summary {
        $tokens = $raw === null ? ['1', '2', '3'] : explode(',', $raw);
        if (count($tokens) > 20) throw new \InvalidArgumentException('invalid-values');
        $items = [];
        foreach ($tokens as $token) {
            if (!preg_match('/^[0-9]{1,10}$/D', $token)) throw new \InvalidArgumentException('invalid-values');
            $items[] = (int)$token;
        }

        if (count($items) < 1 || count($items) > 20 || min($items) < 0 || max($items) > 10000)
            throw new \InvalidArgumentException('invalid-values');

        return new Summary($items);
    }
}
