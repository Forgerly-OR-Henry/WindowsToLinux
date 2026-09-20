"""Create a deterministic tree to snapshot; never write into the fixed baseline."""

import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
parser.add_argument("--files", type=int, default=10000)
args = parser.parse_args()
if not 1 <= args.files <= 100000:
    parser.error("--files must be 1..100000")
args.directory.mkdir(parents=True, exist_ok=True)
if any(args.directory.iterdir()):
    parser.error("choose an empty output directory")
for n in range(args.files):
    path = args.directory / f"层 {n%7}" / f"子 {n%5}" / f"文件 {n}.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"" if n % 101 == 0 else f"content-{n%997:04}".encode())
print(f"Generated {args.files} files in {args.directory}")
