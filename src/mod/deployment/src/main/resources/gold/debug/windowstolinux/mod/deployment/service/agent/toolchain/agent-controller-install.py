import ctypes
import fcntl
import os
import sys

# @WTL_CONTROLLER_INSTALL_SOURCES@

store = {'__name__': 'wtl_agent_controller_installer'}
exec(compile(LOADER_SOURCE, '<wtl-controller-loader>', 'exec'), store)


def install_write(parent, name, body):
    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600, dir_fd=parent)
    try:
        view = memoryview(body)
        while view:
            written = os.write(fd, view)
            store['store_check'](written > 0)
            view = view[written:]
        os.fchmod(fd, 0o444)
        os.fsync(fd)
    finally:
        os.close(fd)
    store['store_check'](store['store_read'](parent, name, len(body)) == body)


def install_root(path):
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        store['store_directory'](fd)
        current = ''
        allowed = (store['STORE_ROOT'].rsplit('/', 1)[0], store['STORE_ROOT'])
        for part in path.split('/')[1:]:
            current += '/' + part
            try:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
            except FileNotFoundError:
                store['store_check'](current in allowed)
                os.mkdir(part, 0o755, dir_fd=fd)
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
            try:
                store['store_directory'](child)
            except BaseException:
                os.close(child)
                raise
            os.close(fd)
            fd = child
        result, fd = fd, None
        return result
    finally:
        if fd is not None:
            os.close(fd)


def install_publish(parent, stage, version):
    # Linux renameat2(RENAME_NOREPLACE): no overwrite, including an empty or partially installed version.
    libc = ctypes.CDLL(None, use_errno=True)
    rename = libc.renameat2
    rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(parent, os.fsencode(stage), parent, os.fsencode(version), 1) != 0:
        raise OSError(ctypes.get_errno(), 'CONTROLLER_PUBLISH_UNCONFIRMED')
    os.fsync(parent)


def install_main(arguments):
    check = store['store_check']
    check(len(arguments) == 2)
    task, installation = arguments
    check(store['re'].fullmatch('[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}', task) is not None
          and store['re'].fullmatch('[a-f0-9]{32}', installation) is not None and os.geteuid() == 0)
    source = CONTROLLER_SOURCE.encode('utf-8')
    check(len(source) == store['SOURCE_BYTES'] and store['hashlib'].sha256(source).hexdigest() == store['SOURCE_SHA256'])
    parent = install_root(store['STORE_ROOT'])
    lock = None
    try:
        lock = os.open('.install.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK, 0o600, dir_fd=parent)
        info = os.fstat(lock)
        check(store['stat'].S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_gid == 0
              and store['stat'].S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        ready = store['store_ready'](locked=True)
        if ready is None:
            stage = '.install-' + store['VERSION_SHA256'] + '-' + installation
            os.mkdir(stage, 0o700, dir_fd=parent)
            stage_fd = os.open(stage, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent)
            try:
                store['store_directory'](stage_fd)
                install_write(stage_fd, 'controller.py', source)
                receipt = store['store_expected'](installation, task)
                install_write(stage_fd, 'install-receipt.json', store['json'].dumps(receipt, sort_keys=True, separators=(',', ':')).encode('utf-8'))
                os.fchmod(stage_fd, 0o555)
                os.fsync(stage_fd)
            finally:
                os.close(stage_fd)
            install_publish(parent, stage, store['VERSION_SHA256'])
            ready = store['store_ready'](locked=True)
            check(ready is not None and ready[0]['installOperation'] == installation)
        value = store['store_observation'](ready[0], task, installation)
        value['requestedInstallOperation'] = installation
        store['store_emit'](value)
    finally:
        if lock is not None:
            os.close(lock)
        os.close(parent)


if __name__ == '__main__':
    try:
        install_main(sys.argv[1:])
    except Exception:
        print('TOOLCHAIN_FAILURE=unknown:AGENT_CONTROLLER_INSTALL_UNCONFIRMED')
        sys.exit(70)
