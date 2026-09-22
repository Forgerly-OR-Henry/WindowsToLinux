"""Create deterministic scale tasks through the real Java API."""

import argparse, json, urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', default='http://127.0.0.1:18101')
parser.add_argument('--count', type=int, default=10000)
parser.add_argument('--seed', type=int, default=20260919)
args = parser.parse_args()
if not 1 <= args.count <= 100000:
    parser.error('count must be 1..100000')
for i in range(args.count):
    project = 1 + i % 2
    member = 1 if project == 1 else 3
    body = json.dumps(
        {
            'projectId': project,
            'title': f'规模任务 {args.seed}-{i}',
            'description': '确定性样本',
            'ownerId': member,
            'priority': 1 + i % 3,
            'labels': ['scale'],
            'dependsOn': [],
            'dueDate': '2030-10-01',
            'version': 0,
            'actorId': member,
        }
    ).encode()
    request = urllib.request.Request(args.url + '/api/tasks', body, {'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        json.load(response)
print(json.dumps({'seed': args.seed, 'createdTasks': args.count}))
