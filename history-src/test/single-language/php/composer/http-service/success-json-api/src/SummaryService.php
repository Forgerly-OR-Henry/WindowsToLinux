<?php
declare(strict_types=1);
namespace Fixture;

final class SummaryService
{
    public function summarize(?string $raw): Summary
    {
        $tokens = $raw === null ? ['1', '2', '3'] : explode(',', $raw);
        if (count($tokens) > 20) throw new \InvalidArgumentException('invalid-values');
        $items = [];
        foreach ($tokens as $token) {
            if (!preg_match('/^[0-9]{1,10}$/D', $token)) throw new \InvalidArgumentException('invalid-values');
            $items[] = (int) $token;
        }

        $schema = json_decode('{"type":"array","minItems":1,"maxItems":20,"items":{"type":"integer","minimum":0,"maximum":10000}}', false, 512, JSON_THROW_ON_ERROR);
        if (!(new \Opis\JsonSchema\Validator())->validate($items, $schema)->isValid())
            throw new \InvalidArgumentException('invalid-values');

        return new Summary($items);
    }
}
