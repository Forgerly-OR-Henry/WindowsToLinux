GNU_MAKE_SOURCE_VERSION = '4.4.1'
GNU_MAKE_SOURCE_NAME = 'make-' + GNU_MAKE_SOURCE_VERSION + '.tar.gz'
GNU_MAKE_SOURCE_URL = 'https://ftp.gnu.org/gnu/make/' + GNU_MAKE_SOURCE_NAME
GNU_MAKE_SOURCE_BYTES = 2348200
GNU_MAKE_SOURCE_SHA256 = 'dd16fb1d67bfab79a72f5e8390735c49e3e8e70b4945a15ab1f81ddb78658fb3'
GNU_MAKE_FAILURES = {
    210: ('source-gnu-make-archive', 'python3'),
    211: ('source-gnu-make-extract', 'tar'),
    212: ('source-gnu-make-configure', 'configure'),
    213: ('source-gnu-make-posix-spawn', 'python3'),
    214: ('source-gnu-make-compile', 'build.sh'),
    215: ('source-gnu-make-identity', 'make'),
    216: ('source-gnu-make-recipe', 'make'),
}


GNU_MAKE_BOOTSTRAP = r'''
import hashlib
import io
import stat
import tarfile

GNU_MAKE_ARCHIVE = "/tmp/wtl-gnu-make-source"
GNU_MAKE_BUILD = pathlib.Path("/tmp/wtl-gnu-make-build")
GNU_MAKE_RELEASE = "make-" + GNU_MAKE_SOURCE_VERSION
GNU_MAKE_PROGRAM = str(GNU_MAKE_BUILD / GNU_MAKE_RELEASE / "make")
GNU_MAKE_OVERRIDE = "MAKE=" + GNU_MAKE_PROGRAM

def read_gnu_make_archive():
    descriptor = os.open(GNU_MAKE_ARCHIVE, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1
                or info.st_size != GNU_MAKE_SOURCE_BYTES or info.st_mode & 0o222):
            sys.exit(210)
        content = source.read(GNU_MAKE_SOURCE_BYTES + 1)
    if len(content) != GNU_MAKE_SOURCE_BYTES or hashlib.sha256(content).hexdigest() != GNU_MAKE_SOURCE_SHA256:
        sys.exit(210)
    names, expanded, directories = set(), 0, 0
    with tarfile.open(fileobj=io.BytesIO(content), mode="r:gz") as archive:
        for member in archive:
            parts = member.name.split("/")
            if (len(names) >= 416 or parts[0] != GNU_MAKE_RELEASE or any(p in ("", ".", "..") for p in parts)
                    or "\\" in member.name or member.name in names or member.mode & 0o7000
                    or member.type not in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE)):
                sys.exit(210)
            names.add(member.name)
            expanded += member.size
            directories += member.isdir()
            if expanded > 8_780_526:
                sys.exit(210)
    required = {GNU_MAKE_RELEASE + path for path in ("", "/configure", "/build.sh", "/src/job.c")}
    if len(names) != 416 or directories != 21 or expanded != 8_780_526 or not required <= names:
        sys.exit(210)
    return content

def gnu_make_binary_identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid, info.st_nlink,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns)

def gnu_make_binary_digest():
    descriptor = os.open(GNU_MAKE_PROGRAM, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1
                or not info.st_mode & stat.S_IXUSR or info.st_mode & 0o7022
                or not 4 < info.st_size <= 8 * 1024 * 1024):
            sys.exit(failure)
        binary = stream.read(info.st_size + 1)
        if (gnu_make_binary_identity(info) != gnu_make_binary_identity(os.fstat(descriptor))
                or gnu_make_binary_identity(info) != gnu_make_binary_identity(os.lstat(GNU_MAKE_PROGRAM))
                or len(binary) != info.st_size or not binary.startswith(b"\x7fELF")):
            sys.exit(failure)
    return hashlib.sha256(binary).hexdigest()

def bootstrap_gnu_make(unit):
    global failure
    failure = 210
    content = read_gnu_make_archive()
    failure = 211
    GNU_MAKE_BUILD.mkdir(mode=0o700)
    subprocess.run(("/usr/bin/tar", "--extract", "--gzip", "--no-same-owner", "--no-same-permissions",
                    "--file", "-", "--directory", str(GNU_MAKE_BUILD)),
                   input=content, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   env=environment, check=True, timeout=20)
    release = GNU_MAKE_BUILD / GNU_MAKE_RELEASE
    if list(GNU_MAKE_BUILD.iterdir()) != [release] or release.is_symlink() or not release.is_dir():
        sys.exit(failure)
    build_environment = dict(environment, CC="/usr/bin/cc", AR="/usr/bin/ar", RANLIB="/usr/bin/ranlib",
                             CFLAGS="-O2 -g0", CONFIG_SITE="/dev/null", MAKE="/usr/bin/false",
                             SOURCE_DATE_EPOCH="1677369600")
    failure = 212
    subprocess.run(("/bin/sh", "./configure", "--enable-posix-spawn", "--disable-nls", "--without-guile",
                    "--disable-load", "--disable-dependency-tracking", "--prefix=/tmp/wtl-make-unused-prefix"),
                   cwd=release, env=build_environment, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, check=True, timeout=60)
    failure = 213
    with (release / "src/config.h").open("rb") as settings:
        config = settings.read(65537)
    if len(config) > 65536 or config.splitlines().count(b"#define USE_POSIX_SPAWN 1") != 1:
        sys.exit(failure)
    failure = 214
    subprocess.run(("/bin/sh", "./build.sh"), cwd=release, env=build_environment,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   check=True, timeout=75)
    failure = 215
    digest = gnu_make_binary_digest()
    with tempfile.TemporaryDirectory(prefix=".wtl-gnu-make-check-", dir="/tmp") as directory:
        with tempfile.TemporaryFile(dir=directory) as output:
            subprocess.run((GNU_MAKE_PROGRAM, "--version"), cwd=directory, env=environment,
                           stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=5)
            output.seek(0)
            version = output.read(2049)
        if (len(version) > 2048
                or version.partition(b"\n")[:2] != (b"GNU Make " + GNU_MAKE_SOURCE_VERSION.encode("ascii"), b"\n")):
            sys.exit(failure)
        failure = 216
        with (pathlib.Path(directory) / "probe.mk").open("xb") as makefile:
            makefile.write(b".PHONY: probe\nprobe:\n\t@/usr/bin/true\n")
        with tempfile.TemporaryFile(dir=directory) as output:
            subprocess.run((GNU_MAKE_PROGRAM, "--no-print-directory", "-rR", "-j2", "-f", "probe.mk", "probe"),
                           cwd=directory, env=environment, stdin=subprocess.DEVNULL,
                           stdout=output, stderr=subprocess.STDOUT, check=True, timeout=5)
            output.seek(0)
            if output.read(1):
                sys.exit(failure)
    if not only_source_worker_in_cgroup(unit) or gnu_make_binary_digest() != digest:
        sys.exit(failure)
    environment.update(MAKE=GNU_MAKE_PROGRAM, PATH=str(release) + ":" + environment["PATH"])
    return digest
'''


def gnu_make_worker(program):
    return SOURCE_WORKER + (
        f"\nGNU_MAKE_SOURCE_VERSION = {GNU_MAKE_SOURCE_VERSION!r}\n"
        f"GNU_MAKE_SOURCE_BYTES = {GNU_MAKE_SOURCE_BYTES}\nGNU_MAKE_SOURCE_SHA256 = {GNU_MAKE_SOURCE_SHA256!r}\n"
    ) + GNU_MAKE_BOOTSTRAP + program


def gnu_make_dependency():
    return {'version': GNU_MAKE_SOURCE_VERSION, 'source': GNU_MAKE_SOURCE_URL, 'sha256': GNU_MAKE_SOURCE_SHA256}


def prepare_gnu_make_source(work):
    dependency = gnu_make_dependency()
    archive = work / 'gnu-make-source.tar.gz'
    url = source_url('GNU_MAKE_SOURCE', GNU_MAKE_SOURCE_NAME)
    digest = download(url, archive, 'sha256', dependency['sha256'],
                      point='GNU_MAKE_SOURCE', maximum=GNU_MAKE_SOURCE_BYTES)
    info = archive.lstat()
    if digest != dependency['sha256'] or not stat.S_ISREG(info.st_mode) or info.st_size != GNU_MAKE_SOURCE_BYTES:
        fail('integrity', 'GNU Make source archive identity differs')
    archive.chmod(0o444)
    return archive, dependency
