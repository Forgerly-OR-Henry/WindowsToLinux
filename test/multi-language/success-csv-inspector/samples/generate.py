"""Generate deterministic large CSV input; outputs are not committed."""

import argparse
import csv
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
parser.add_argument("--rows", type=int, default=100000)
args = parser.parse_args()
if not 1 <= args.rows <= 500000:
    parser.error("--rows must be 1..500000")
with args.output.open("w", encoding="utf-8", newline="") as file:
    writer = csv.writer(file)
    writer.writerow(["id", "name", "age", "date", "team"])
    for n in range(1, args.rows + 1):
        writer.writerow(
            [
                n,
                "" if n % 17 == 0 else f"人员 {n}",
                "bad" if n % 19 == 0 else 999 if n % 23 == 0 else 20,
                "2026-02-30" if n % 29 == 0 else "2026-09-19",
                "green" if n % 31 == 0 else "red",
            ]
        )
print(f"Generated {args.rows} rows: {args.output}")
