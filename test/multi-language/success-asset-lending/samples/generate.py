"""Register deterministic assets through the real Node/C# business API."""

import argparse, json, urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18120")
parser.add_argument("--count", type=int, default=10000)
parser.add_argument("--seed", type=int, default=20260919)
args = parser.parse_args()
if not 1 <= args.count <= 100000:
    parser.error("count must be 1..100000")
for i in range(args.count):
    body = json.dumps(
        {
            "name": f"规模资产 {i}",
            "categoryId": 1 + i % 2,
            "serial": f"SAMPLE-{args.seed}-{i:06}",
        }
    ).encode()
    request = urllib.request.Request(args.url + "/api/assets", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        json.load(response)
print(json.dumps({"seed": args.seed, "registered": args.count}))
