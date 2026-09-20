<?php
namespace CsvInspector;

final class Rules
{
    public static function validate(mixed $rules): object
    {
        $schema = json_decode(
            file_get_contents(__DIR__ . "/../resources/rules.schema.json"),
        );
        if (
            !(new \Opis\JsonSchema\Validator())
                ->validate($rules, $schema)
                ->isValid()
        ) {
            throw new ApiError(
                "规则结构错误，请检查必填、类型、范围、枚举和组合去重配置",
                400,
            );
        }
        foreach ($rules->ranges as $bounds) {
            if ($bounds->min > $bounds->max) {
                throw new ApiError("范围下限大于上限", 400);
            }
        }
        return $rules;
    }

    public static function requestId(mixed $value): string
    {
        if (
            !is_string($value) ||
            !preg_match('/^[A-Za-z0-9_-]{8,80}$/D', $value)
        ) {
            throw new ApiError(
                "requestId 需要 8..80 位字母、数字、下划线或连字符",
                400,
            );
        }
        return $value;
    }

    public static function name(mixed $value): string
    {
        if (!is_string($value) || trim($value) === "" || strlen($value) > 240) {
            throw new ApiError("名称不能为空或超过 240 字节", 400);
        }
        return trim($value);
    }
}
