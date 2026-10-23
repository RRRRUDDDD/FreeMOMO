"""Shared constants and small helpers for the rootless build."""
import hashlib
from pathlib import Path

ORIGINAL_SHA256 = "6c1fcb95aa9a3e12b73af589b0efdcb2fb7d81cd4516451bdf5b031c2b9d46ae"
PACKAGE = "com.maimemo.android.momo"
VERSION_CODE = "900"
VERSION_NAME = "5.6.00"
ORIGINAL_FACTORY = "com.secneo.apkwrapper.AP"
SPLIT_FACTORY = "com.repack.appfactory.AP"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def stream_sha256(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def sha256(path):
    with Path(path).open("rb") as stream:
        return stream_sha256(stream)
