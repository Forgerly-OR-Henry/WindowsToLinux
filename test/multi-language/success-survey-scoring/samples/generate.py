"""Create real versioned survey results, using stable idempotency keys."""
import argparse,json,urllib.request
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url',default='http://127.0.0.1:18141')
parser.add_argument('--revision',type=int,default=1)
parser.add_argument('--count',type=int,default=10000)
parser.add_argument('--seed',type=int,default=20260919)
args=parser.parse_args()
if not 1<=args.count<=100000 or args.revision<1:parser.error('count/revision out of range')
ids=set()
for i in range(args.count):
 answers={'participated':'yes' if i%2==0 else 'no','quality':1+i%5,'support':['docs','peer'] if i%3==0 else ['docs'],'friction':1+i%5}
 body=json.dumps({'revisionId':args.revision,'respondent':'林同学' if i%2==0 else '周同学','requestId':f'scale-{args.revision}-{args.seed}-{i}','answers':answers}).encode()
 request=urllib.request.Request(args.url+'/api/submissions',body,{'Content-Type':'application/json'})
 with urllib.request.urlopen(request,timeout=30) as response:ids.add(json.load(response)['id'])
print(json.dumps({'seed':args.seed,'uniqueResults':len(ids),'revision':args.revision}))
