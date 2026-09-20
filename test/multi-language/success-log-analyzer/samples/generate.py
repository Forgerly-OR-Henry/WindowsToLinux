"""Generate deterministic mixed-format files, including gzip; no large committed assets."""

import argparse
from datetime import datetime, timedelta, timezone
import gzip
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--records", type=int, default=100000)
args = parser.parse_args()
if not 1 <= args.records <= 1000000:
    parser.error("--records must be 1..1000000")
args.directory.mkdir(parents=True, exist_ok=True)
files = [
    (args.directory / "api.log").open("w", encoding="utf-8"),
    (args.directory / "worker.jsonl").open("w", encoding="utf-8"),
    gzip.open(args.directory / "archive.gz", "wt", encoding="utf-8"),
]
try:
    for n in range(args.records):
        stamp = (
            datetime(2026, 9, 19, 10, tzinfo=timezone.utc) + timedelta(seconds=n % 3600)
        ).strftime("%Y-%m-%dT%H:%M:%SZ")
        level = ["INFO", "WARN", "ERROR", "DEBUG"][n % 4]
        service = "worker" if n % 3 == 0 else "api"
        message = f"request {n} timeout" if level == "ERROR" else f"processed {n}"
        files[n % 3].write(
            (
                json.dumps(
                    {
                        "time": stamp,
                        "level": level,
                        "service": service,
                        "message": message,
                    }
                )
                if n % 3 == 1
                else f"{stamp} {level} [{service}] {message}"
            )
            + "\n"
        )
finally:
    for file in files:
        file.close()
print(f"Generated {args.records} records in {args.directory}")
