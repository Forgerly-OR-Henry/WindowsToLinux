"""Generate deterministic file bytes without holding the full file in memory."""

import argparse, hashlib, json, random
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--size-mib", type=int, default=64)
parser.add_argument("--seed", type=int, default=20260919)
args = parser.parse_args()
if not 0 <= args.size_mib <= 256:
    parser.error("size-mib must be 0..256")
args.output.parent.mkdir(parents=True, exist_ok=True)
block = random.Random(args.seed).randbytes(1048576)
digest = hashlib.sha256()
with args.output.open("wb") as f:
    for _ in range(args.size_mib):
        f.write(block)
        digest.update(block)
print(
    json.dumps(
        {
            "seed": args.seed,
            "bytes": args.size_mib * 1048576,
            "sha256": digest.hexdigest(),
            "file": str(args.output),
        }
    )
)
