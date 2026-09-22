"""Bounded CSV analysis yielding protocol records, never a materialized issue collection."""

import csv
from datetime import date
from decimal import Decimal, InvalidOperation
import os
import time
from jsonschema import Draft202012Validator

RULE_SCHEMA = {
    "type": "object",
    "required": ["required", "types", "ranges", "enums", "unique"],
    "additionalProperties": False,
    "properties": {
        "required": {
            "type": "array",
            "maxItems": 32,
            "uniqueItems": True,
            "items": {"type": "string", "minLength": 1, "maxLength": 80},
        },
        "types": {
            "type": "object",
            "maxProperties": 32,
            "additionalProperties": {"enum": ["number", "date"]},
        },
        "ranges": {
            "type": "object",
            "maxProperties": 32,
            "additionalProperties": {
                "type": "object",
                "required": ["min", "max"],
                "additionalProperties": False,
                "properties": {"min": {"type": "number"}, "max": {"type": "number"}},
            },
        },
        "enums": {
            "type": "object",
            "maxProperties": 32,
            "additionalProperties": {
                "type": "array",
                "minItems": 1,
                "maxItems": 30,
                "uniqueItems": True,
                "items": {"type": "string", "maxLength": 100},
            },
        },
        "unique": {
            "type": "array",
            "maxItems": 8,
            "uniqueItems": True,
            "items": {
                "type": "array",
                "minItems": 1,
                "maxItems": 8,
                "uniqueItems": True,
                "items": {"type": "string", "minLength": 1, "maxLength": 80},
            },
        },
    },
}
VALIDATOR = Draft202012Validator(RULE_SCHEMA)


def analyze(stream, rules):
    VALIDATOR.validate(rules)
    if any(bounds["min"] > bounds["max"] for bounds in rules["ranges"].values()):
        raise ValueError("规则范围下限大于上限")
    csv.field_size_limit(int(os.getenv("MAX_FIELD_CHARS", "65536")))
    reader = csv.DictReader(stream, strict=True)
    fields = reader.fieldnames
    if (
        not fields
        or len(fields) > 32
        or len(set(fields)) != len(fields)
        or any(not f.strip() or len(f) > 80 for f in fields)
    ):
        raise ValueError("CSV 表头缺失、重复、为空或超过限制")
    referenced = (
        set(rules["required"])
        | set(rules["types"])
        | set(rules["ranges"])
        | set(rules["enums"])
        | {f for group in rules["unique"] for f in group}
    )
    if not referenced.issubset(fields):
        raise ValueError("检查规则引用了不存在的列")
    counts = {code: 0 for code in ["required", "number", "date", "range", "enum", "duplicate"]}
    seen = [{} for _ in rules["unique"]]
    rows = bad_rows = issues = 0
    maximum = int(os.getenv("MAX_ROWS", "500000"))
    key_limit = int(os.getenv("MAX_UNIQUE_KEYS", "500000"))
    delay = float(os.getenv("SAMPLE_BATCH_DELAY_MS", "0")) / 1000
    yield {"type": "start", "columns": fields}
    for number, row in enumerate(reader, 2):
        rows += 1
        if rows > maximum:
            raise ValueError("CSV 行数超过 MAX_ROWS")
        if None in row or any(v is None for v in row.values()):
            raise ValueError(f"CSV 记录 {number} 列数不匹配")
        current = []

        def issue(column, code, value, detail):
            current.append(
                {
                    "type": "issue",
                    "row": number,
                    "column": column,
                    "code": code,
                    "value": value[:200],
                    "detail": detail,
                }
            )
            counts[code] += 1

        for field in rules["required"]:
            if not row[field].strip():
                issue(field, "required", row[field], "必填值缺失")
        numeric = {}
        for field in set(rules["ranges"]) | {k for k, v in rules["types"].items() if v == "number"}:
            value = row[field].strip()
            if not value:
                continue
            try:
                parsed = Decimal(value)
                if not parsed.is_finite():
                    raise InvalidOperation()
                numeric[field] = parsed
            except InvalidOperation:
                issue(field, "number", value, "不是有限数字")
        for field, kind in rules["types"].items():
            value = row[field].strip()
            if kind == "date" and value:
                try:
                    if date.fromisoformat(value).isoformat() != value:
                        raise ValueError()
                except ValueError:
                    issue(field, "date", value, "日期必须为有效 YYYY-MM-DD")
        for field, bounds in rules["ranges"].items():
            if field in numeric and not Decimal(str(bounds["min"])) <= numeric[field] <= Decimal(str(bounds["max"])):
                issue(field, "range", row[field], f"范围 {bounds['min']}..{bounds['max']}")
        for field, values in rules["enums"].items():
            if row[field].strip() and row[field] not in values:
                issue(field, "enum", row[field], "不在枚举选项中")
        for index, columns in enumerate(rules["unique"]):
            key = tuple(row[field] for field in columns)
            if key in seen[index]:
                issue(
                    ",".join(columns),
                    "duplicate",
                    " | ".join(key),
                    f"首次出现在记录 {seen[index][key]}",
                )
            else:
                if len(seen[index]) >= key_limit:
                    raise ValueError("组合去重键超过 MAX_UNIQUE_KEYS")
                seen[index][key] = number
        if current:
            bad_rows += 1
        issues += len(current)
        yield from current
        if rows % 1000 == 0:
            yield {"type": "progress", "rows": rows, "issues": issues}
            if delay:
                time.sleep(delay)
    yield {
        "type": "end",
        "rows": rows,
        "validRows": rows - bad_rows,
        "issueCount": issues,
        "statistics": counts,
        "columns": fields,
    }
