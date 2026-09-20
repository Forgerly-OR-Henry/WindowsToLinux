"""Task-owned process groups, including descendants on Windows and POSIX."""

import ctypes
import os
import signal
import subprocess
import time

if os.name == "nt":
    from ctypes import wintypes as w

    class Basic(ctypes.Structure):
        _fields_ = [
            ("processTime", ctypes.c_int64),
            ("jobTime", ctypes.c_int64),
            ("flags", w.DWORD),
            ("minWorking", ctypes.c_size_t),
            ("maxWorking", ctypes.c_size_t),
            ("active", w.DWORD),
            ("affinity", ctypes.c_size_t),
            ("priority", w.DWORD),
            ("scheduling", w.DWORD),
        ]

    class IO(ctypes.Structure):
        _fields_ = [
            (n, ctypes.c_uint64)
            for n in [
                "readCount",
                "writeCount",
                "otherCount",
                "readBytes",
                "writeBytes",
                "otherBytes",
            ]
        ]

    class Limits(ctypes.Structure):
        _fields_ = [
            ("basic", Basic),
            ("io", IO),
            ("processMemory", ctypes.c_size_t),
            ("jobMemory", ctypes.c_size_t),
            ("peakProcess", ctypes.c_size_t),
            ("peakJob", ctypes.c_size_t),
        ]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, w.LPCWSTR]
    kernel.CreateJobObjectW.restype = w.HANDLE
    kernel.SetInformationJobObject.argtypes = [
        w.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        w.DWORD,
    ]
    kernel.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
    kernel.CloseHandle.argtypes = [w.HANDLE]


class OwnedProcess:
    def __init__(self, command, **kwargs):
        self.job = None
        if os.name == "nt":
            self.job = kernel.CreateJobObjectW(None, None)
            limits = Limits()
            limits.basic.flags = 0x2000
            if not self.job or not kernel.SetInformationJobObject(
                self.job, 9, ctypes.byref(limits), ctypes.sizeof(limits)
            ):
                raise ctypes.WinError(ctypes.get_last_error())
            kwargs["creationflags"] = (
                subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
            )
        else:
            kwargs["start_new_session"] = True
        try:
            self.process = subprocess.Popen(command, **kwargs)
            if self.job and not kernel.AssignProcessToJobObject(
                self.job, int(self.process._handle)
            ):
                self.process.kill()
                self.process.wait()
                raise ctypes.WinError(ctypes.get_last_error())
        except BaseException:
            if self.job:
                kernel.CloseHandle(self.job)
                self.job = None
            raise

    def kill_member(self, pid):
        """Crash the exact instrumented process, after proving it belongs to this task job."""
        if os.name == "nt":
            kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
            kernel.OpenProcess.restype = w.HANDLE
            kernel.IsProcessInJob.argtypes = [
                w.HANDLE,
                w.HANDLE,
                ctypes.POINTER(w.BOOL),
            ]
            kernel.TerminateProcess.argtypes = [w.HANDLE, w.UINT]
            kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
            handle = kernel.OpenProcess(0x101001, False, pid)
            if not handle:
                raise ctypes.WinError(ctypes.get_last_error())
            try:
                belongs = w.BOOL()
                if (
                    not self.job
                    or not kernel.IsProcessInJob(
                        handle, self.job, ctypes.byref(belongs)
                    )
                    or not belongs.value
                ):
                    raise RuntimeError("Fault marker PID is not in the task-owned job")
                if not kernel.TerminateProcess(handle, 99):
                    raise ctypes.WinError(ctypes.get_last_error())
                if kernel.WaitForSingleObject(handle, 5000) != 0:
                    raise TimeoutError("Instrumented process did not exit")
            finally:
                kernel.CloseHandle(handle)
        else:
            if os.getpgid(pid) != self.process.pid:
                raise RuntimeError(
                    "Fault marker PID is not in the task-owned process group"
                )
            os.kill(pid, signal.SIGKILL)
            if pid == self.process.pid:
                self.process.wait(timeout=5)
            else:
                deadline = time.monotonic() + 5
                while True:
                    try:
                        os.kill(pid, 0)
                    except ProcessLookupError:
                        break
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Instrumented child did not exit")
                    time.sleep(0.02)

    def metrics(self):
        result = {"pid": self.process.pid}
        if os.name == "nt" and self.job:
            limits = Limits()
            kernel.QueryInformationJobObject.argtypes = [
                w.HANDLE,
                ctypes.c_int,
                ctypes.c_void_p,
                w.DWORD,
                ctypes.c_void_p,
            ]
            if kernel.QueryInformationJobObject(
                self.job, 9, ctypes.byref(limits), ctypes.sizeof(limits), None
            ):
                result.update(
                    peakProcessBytes=limits.peakProcess, peakJobBytes=limits.peakJob
                )
        return result

    def close(self):
        if self.job:
            kernel.CloseHandle(self.job)
            self.job = None
        elif os.name != "nt":
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            if os.name != "nt":
                try:
                    os.killpg(self.process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            else:
                self.process.kill()
            self.process.wait(timeout=5)

    def __enter__(self):
        return self.process

    def __exit__(self, *args):
        self.close()
