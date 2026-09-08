"""Bounded HTTPS Range fallback for slow Hub transfers; final SHA-256 is mandatory."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import tempfile
import time
import urllib.request

from artifacts import digest


def download_ranges(file, target, workers=8, chunk_size=8 * 1024 * 1024, retries=5):
    if not 1 <= workers <= 16 or chunk_size <= 0 or retries <= 0:
        raise ValueError("Invalid download limits")
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=target.name + ".", suffix=".download", dir=target.parent)
    temporary = Path(name)
    try:
        os.ftruncate(fd, file["size"])

        def transfer(start):
            end = min(start + chunk_size, file["size"]) - 1
            for attempt in range(retries):
                try:
                    request = urllib.request.Request(file["url"], headers={"Range": f"bytes={start}-{end}"})
                    with urllib.request.urlopen(request, timeout=120) as response:
                        if response.status != 206 or response.headers.get("Content-Range") != f"bytes {start}-{end}/{file['size']}":
                            raise ValueError("Range response does not match the requested immutable source")
                        position = start
                        while block := response.read(1024 * 1024):
                            if position + len(block) > end + 1:
                                raise ValueError("Range response overflow")
                            pending = memoryview(block)
                            while pending:
                                written = os.pwrite(fd, pending, position)
                                if written <= 0:
                                    raise OSError("Range output made no progress")
                                position += written
                                pending = pending[written:]
                        if position != end + 1:
                            raise ValueError("Range response is truncated")
                    return
                except (OSError, ValueError):
                    if attempt == retries - 1:
                        raise
                    time.sleep(2 ** attempt)

        with ThreadPoolExecutor(max_workers=workers) as pool:
            list(pool.map(transfer, range(0, file["size"], chunk_size)))
        os.fsync(fd)
        if digest(temporary) != file["sha256"]:
            raise ValueError("Source SHA-256 mismatch after Range download")
        temporary.replace(target)
    finally:
        os.close(fd)
        temporary.unlink(missing_ok=True)
