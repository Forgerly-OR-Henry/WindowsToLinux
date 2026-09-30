def operation_id(value):
    try: parsed = uuid.UUID(value)
    except (TypeError, ValueError): raise Failure('STATE_CHANGED')
    if str(parsed) != value: raise Failure('STATE_CHANGED')
    return value

def server_id(value):
    if not isinstance(value, str) or not value or len(value) > 512 or any(ord(ch) < 32 for ch in value):
        raise Failure('STATE_CHANGED')
    return value

def operation_target_digest(operation, request):
    try: fields = [request[key] for key in TARGET_FIELDS[operation]]
    except (KeyError, TypeError): raise Failure('STATE_CHANGED')
    if any(not field or '\x00' in field for field in fields): raise Failure('STATE_CHANGED')
    if operation == 'install':
        fields += [request.get(key,'') for key in ('instanceId','fingerprint','replacementApproved','approvedServer')]
    if operation == 'initialize':
        fields += [request.get(key,'') for key in ('sourceSha256','existingApproved')]
    if any('\x00' in field for field in fields): raise Failure('STATE_CHANGED')
    return hashlib.sha256('\0'.join(fields).encode('utf-8')).hexdigest()

def operation_path(directory, identifier):
    return directory/('operation-'+operation_id(identifier)+'.json')

def existing_state_directory():
    directory = pathlib.Path('/var/lib/windowstolinux/db')
    for parent in list(reversed(directory.parents)) + [directory]:
        if parent.is_symlink(): raise Failure('STATE_CHANGED')
        if parent.exists() and (parent.stat().st_uid != 0 or parent.stat().st_mode & 0o022):
            raise Failure('STATE_CHANGED')
    if not directory.exists(): return None
    if not directory.is_dir(): raise Failure('STATE_CHANGED')
    return directory

def operation_receipt(record):
    output = {'receipt.formatVersion':str(record['formatVersion']), 'receipt.operationId':record['operationId'],
              'receipt.taskId':record['taskId'], 'receipt.serverId':record['serverId'],
              'receipt.action':record['action'], 'receipt.targetDigest':record['targetDigest'],
              'receipt.requestDigest':record['requestDigest'], 'receipt.phase':record['phase'],
              'receipt.effectBoundary':record['effectBoundary']}
    if record.get('failureCode'): output['receipt.failureCode'] = record['failureCode']
    for key,value in record.get('result',{}).items(): output['result.'+key] = str(value)
    return output

def operation_read(path):
    if not path.exists(): return {}
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
    try:
        with os.fdopen(os.open(path, flags), 'rb') as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 \
                    or info.st_size > 131072: raise Failure('STATE_CHANGED')
            raw = stream.read(131073)
    except OSError: raise Failure('STATE_CHANGED')
    if len(raw) > 131072: raise Failure('STATE_CHANGED')
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value: raise Failure('STATE_CHANGED')
            value[key] = item
        return value
    try: return json.loads(raw, object_pairs_hook=unique)
    except (ValueError, UnicodeDecodeError): raise Failure('STATE_CHANGED')

def operation_status(request):
    identifier = operation_id(request.get('operationId'))
    task = operation_id(request.get('taskId'))
    server = server_id(request.get('serverId'))
    directory = existing_state_directory()
    if directory is None: return {'receipt.phase':'MISSING'}
    path = operation_path(directory, identifier)
    if path.is_symlink(): raise Failure('STATE_CHANGED')
    record = operation_read(path)
    if not record: return {'receipt.phase':'MISSING'}
    if record.get('operationId') != identifier or record.get('taskId') != task or record.get('serverId') != server:
        raise Failure('STATE_CHANGED')
    if set(record) not in ({'formatVersion','operationId','taskId','serverId','action','targetDigest',
                           'requestDigest','phase','effectBoundary','result'},
                          {'formatVersion','operationId','taskId','serverId','action','targetDigest',
                           'requestDigest','phase','effectBoundary','result','failureCode'}) \
            or record.get('formatVersion') != 1 or record.get('action') not in MUTATING_OPERATIONS \
            or record.get('phase') not in ('STARTED','SUCCEEDED','FAILED') \
            or record.get('effectBoundary') not in ('NONE','MAY_HAVE_RUN','CONFIRMED') \
            or not re.fullmatch(r'[0-9a-f]{64}',record.get('targetDigest','')) \
            or not re.fullmatch(r'[0-9a-f]{64}',record.get('requestDigest','')) \
            or not isinstance(record.get('result'),dict): raise Failure('STATE_CHANGED')
    if record['phase'] == 'SUCCEEDED' and (record['effectBoundary'] != 'CONFIRMED' or 'failureCode' in record) \
            or record['phase'] == 'STARTED' and ('failureCode' in record or record['effectBoundary'] == 'CONFIRMED') \
            or record['phase'] == 'FAILED' and (record['effectBoundary'] == 'CONFIRMED' or 'failureCode' not in record) \
            or any(not isinstance(key,str) or not isinstance(value,str) or len(key) > 64 or len(value) > 4096
                   for key,value in record['result'].items()): raise Failure('STATE_CHANGED')
    for key in ('action','targetDigest','requestDigest'):
        if key in request and request[key] != record[key]: raise Failure('STATE_CHANGED')
    return operation_receipt(record)

def operation_dispatch(operation, request, raw, directory):
    identifier = operation_id(request.get('operationId'))
    task = operation_id(request.get('taskId'))
    server = server_id(request.get('serverId'))
    target = operation_target_digest(operation, request)
    path = operation_path(directory, identifier)
    if path.exists() or path.is_symlink(): raise Failure('STATE_CHANGED')
    record = {'formatVersion':1, 'operationId':identifier, 'taskId':task, 'serverId':server,
              'action':operation, 'targetDigest':target, 'requestDigest':hashlib.sha256(raw).hexdigest(),
              'phase':'STARTED', 'effectBoundary':'NONE', 'result':{}}
    write_state(path,record)
    if operation == 'start':
        try: select(request)  # Read-only preflight: a failure here has not started this operation.
        except Failure as failure:
            record.update({'phase':'FAILED','failureCode':failure.code})
            write_state(path,record)
            return dict(operation_receipt(record), status=failure.code)
    record['effectBoundary'] = 'MAY_HAVE_RUN'
    write_state(path,record)
    try: output = dispatch(operation,request)
    except Failure as failure:
        record.update({'phase':'FAILED','failureCode':failure.code})
        write_state(path,record)
        return dict(operation_receipt(record), status=failure.code)
    if len(json.dumps(output,sort_keys=True).encode()) > 131072: raise Failure('ACTION_FAILED')
    record.update({'phase':'SUCCEEDED','effectBoundary':'CONFIRMED','result':output})
    write_state(path,record)
    return dict(output, **operation_receipt(record))

def dispatch(operation, request):
    if request.get('engine') not in PACKAGES['apt']: raise Failure()
    if operation == 'inspect': return wire_inventory(inspect(request['engine']))
    if operation == 'install': return wire_inventory(install(request))
    if operation in ('start','resume'):
        output = {}; wire_instance(output,start(request) if operation == 'start' else resume(request),'instance.'); return output
    if operation == 'target': return inspect_target(request)
    if operation == 'prepare': return prepare_target(request)
    if operation == 'initialize': return initialize(request)
    raise Failure()

def main():
    output = {}
    try:
        with os.fdopen(3,'rb',closefd=False) as stream: payload = stream.read(3 * 1024 * 1024 + 1)
        if len(payload) > 3 * 1024 * 1024: raise Failure()
        request = {}
        for line in payload.decode('ascii').splitlines():
            key, value = line.split('=',1)
            if key in request or not re.fullmatch(r'[a-zA-Z0-9.]{1,64}',key): raise Failure()
            request[key] = base64.b64decode(value,validate=True).decode('utf-8')
        operation = sys.argv[1]
        if operation == 'status':
            output = operation_status(request)
        elif operation in MUTATING_OPERATIONS or operation in ('inspect','target'):
            directory = state_directory()
            with open(directory/'operation.lock','a') as lock:
                fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
                output = (operation_dispatch(operation,request,payload,directory)
                          if operation in MUTATING_OPERATIONS else dispatch(operation,request))
        else: raise Failure('STATE_CHANGED')
        output.setdefault('status','OK')
    except Failure as failure: output = {'status':failure.code}
    except Exception: output = {'status':'ACTION_FAILED'}
    for key,value in output.items(): print(key+'='+base64.b64encode(str(value).encode()).decode('ascii'))

if __name__ == '__main__': main()
WTL_NATIVE_DB_PY
}
