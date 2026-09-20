"""Hash only regular files; compare identity/metadata before, during and after each read."""

import hashlib
import os
from pathlib import Path
import stat
import time


def linked(info):
    return stat.S_ISLNK(info.st_mode) or bool(
        getattr(info, "st_file_attributes", 0) & 0x400
    )


def identity(info):
    # Python 3.12 stat/fstat disagree on Windows st_ctime semantics; birth time is consistent.
    return (
        info.st_dev,
        info.st_ino,
        info.st_size,
        info.st_mtime_ns,
        getattr(info, "st_birthtime_ns", info.st_ctime_ns),
    )


def change_time(path=None, fd=None):
    if os.name != "nt":
        return (
            os.stat(path).st_ctime_ns if path is not None else os.fstat(fd).st_ctime_ns
        )
    import ctypes
    from ctypes import wintypes as w
    import msvcrt

    class Basic(ctypes.Structure):
        _fields_ = [
            ("created", ctypes.c_int64),
            ("accessed", ctypes.c_int64),
            ("written", ctypes.c_int64),
            ("changed", ctypes.c_int64),
            ("attributes", w.DWORD),
        ]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = [
        w.LPCWSTR,
        w.DWORD,
        w.DWORD,
        ctypes.c_void_p,
        w.DWORD,
        w.DWORD,
        w.HANDLE,
    ]
    kernel.CreateFileW.restype = w.HANDLE
    kernel.GetFileInformationByHandleEx.argtypes = [
        w.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        w.DWORD,
    ]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    handle = (
        kernel.CreateFileW(str(path), 0x80, 7, None, 3, 0x00200000, None)
        if path is not None
        else msvcrt.get_osfhandle(fd)
    )
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        info = Basic()
        if not kernel.GetFileInformationByHandleEx(
            handle, 0, ctypes.byref(info), ctypes.sizeof(info)
        ):
            raise ctypes.WinError(ctypes.get_last_error())
        if info.attributes & 0x400:
            raise OSError("文件变为重解析点")
        return info.changed
    finally:
        if path is not None:
            kernel.CloseHandle(handle)


def checked_path(root, relative):
    path = root
    for part in Path(relative).parts:
        path = path / part
        if linked(path.lstat()):
            raise OSError("路径已变为符号链接或目录联接: " + relative)
    if not path.resolve().is_relative_to(root):
        raise OSError("路径离开扫描根目录")
    return path


def gate(point):
    if os.getenv("SAMPLE_FAULT_POINT") != point:
        return
    directory = Path(os.environ["SAMPLE_FAULT_DIR"])
    directory.mkdir(parents=True, exist_ok=True)
    (directory / (point + ".ready")).write_text(str(os.getpid()), encoding="ascii")
    deadline = time.monotonic() + 60
    while not (directory / (point + ".release")).exists():
        if time.monotonic() > deadline:
            raise OSError("故障检查等待超时")
        time.sleep(0.02)


def digest(root, item, max_bytes):
    path = checked_path(root, item["path"])
    before = path.stat(follow_symlinks=False)
    if (
        not stat.S_ISREG(before.st_mode)
        or before.st_size != item["size"]
        or before.st_mtime_ns != item["modifiedNs"]
    ):
        raise OSError("扫描后文件变化: " + item["path"])
    if before.st_size > max_bytes:
        raise OSError("文件超过 --max-file-bytes: " + item["path"])
    changed = change_time(path=path)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(os.open(path, flags), "rb") as file:
        opened = os.fstat(file.fileno())
        if (
            identity(opened) != identity(before)
            or change_time(fd=file.fileno()) != changed
        ):
            raise OSError("打开期间文件变化: " + item["path"])
        hasher = hashlib.sha256()
        total = 0
        while chunk := file.read(65536):
            total += len(chunk)
            if total > max_bytes:
                raise OSError("读取期间文件超出大小限制")
            hasher.update(chunk)
        if (
            identity(os.fstat(file.fileno())) != identity(before)
            or change_time(fd=file.fileno()) != changed
        ):
            raise OSError("摘要期间文件变化: " + item["path"])
    if (
        identity(checked_path(root, item["path"]).stat(follow_symlinks=False))
        != identity(before)
        or change_time(path=path) != changed
    ):
        raise OSError("摘要后文件变化: " + item["path"])
    return {
        "path": item["path"],
        "size": total,
        "sha256": hasher.hexdigest(),
        "identity": identity(before),
        "changeTime": changed,
    }
