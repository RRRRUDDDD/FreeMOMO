"""Append-style APK repacking for the rootless build.

The original base APK is kept byte-for-byte at every existing offset. New
entries are appended and the central directory is rebuilt around them:

    [original local entries, bytes 0 .. cd_off)   verbatim
    [appended local entries]                      new, self-aligned
    [appended CD records]                         new, in front of ...
    [original central directory block]            verbatim (MANIFEST.MF last)
    [EOCD]                                        rewritten cd_off/cd_size/count

AndroidManifest.xml is the one entry whose bytes change (the component factory
is renamed to the split-provided class). It is appended as a STORED entry and
only its central-directory record is rewritten in place, padded through the CD
extra field so every record keeps its exact byte length.

No zipalign rewrite is performed because it would move original entries. The
original is checked as already aligned, appended STORED entries are aligned
here, and apksigner signs with v1/v2/v3 before the layout is checked again.

The module never installs, uninstalls, pushes or publishes anything.
"""
import argparse
import io
import json
import re
import struct
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "rootless"))
from verify_artifacts import ORIGINAL_SHA256, require, sha256  # noqa: E402

ORIGINAL_DEFAULT = ROOT / "墨墨背单词_5.6.00.apk"
MANIFEST_ENTRY = b"AndroidManifest.xml"
V1_MARKER_NAME = b"META-INF/MANIFEST.MF"
# apksigner v1 signing replaces the original v1 trio with its own files named
# after the key alias ("freemomo" -> FREEMOMO.*). MANIFEST.MF keeps both its
# name and its position: it is re-emitted LAST, so the shell's size-0x2a
# v1-marker window reads byte-identical to the original.
V1_ORIGINAL_FILES = [V1_MARKER_NAME, b"META-INF/_____.SF", b"META-INF/_____.RSA"]


def exe_suffix() -> str:
    return ".exe" if sys.platform == "win32" else ""


def pick_build_tools(sdk: Path) -> Path:
    """Return the newest installed build-tools directory that has the tools we use."""
    root = sdk / "build-tools"
    require(root.is_dir(), f"Android build-tools not found under {root}")

    def version_key(path: Path):
        return tuple(int(part) if part.isdigit() else 0
                     for part in re.split(r"[.-]", path.name))

    for candidate in sorted((p for p in root.iterdir() if p.is_dir()),
                            key=version_key, reverse=True):
        if (candidate / "lib/apksigner.jar").is_file() and \
                (candidate / f"zipalign{exe_suffix()}").is_file():
            return candidate
    require(False, f"No usable build-tools found under {root}")
    return root


# ---------------------------------------------------------------- zip parsing

def parse_eocd(data: bytes) -> dict:
    require(len(data) > 22, "APK too small")
    require(data[-22:-18] == b"PK\x05\x06",
            f"EOCD not at size-22 (tail: {data[-40:].hex()})")
    disk, cd_disk, count_disk, count, cd_size, cd_off, comment_len = \
        struct.unpack_from("<HHHHIIH", data, len(data) - 18)
    require(disk == 0 and cd_disk == 0, "multi-disk zip unsupported")
    require(comment_len == 0, "unexpected EOCD comment")
    require(cd_off + cd_size + 22 == len(data),
            f"CD does not close the file: cd_off={cd_off:#x} cd_size={cd_size:#x} "
            f"size={len(data):#x}")
    return dict(count=count, cd_size=cd_size, cd_off=cd_off)


def iter_cd_records(data: bytes, cd_off: int, cd_size: int):
    """Yield (index, name, info) for every central-directory record."""
    pos, end = cd_off, cd_off + cd_size
    index = 0
    while pos < end:
        require(data[pos:pos + 4] == b"PK\x01\x02", f"bad CD record at {pos:#x}")
        (_vm, _vn, _flags, method, _mt, _md, crc, csize, usize, name_len,
         extra_len, comment_len, _disk, _iattr, _eattr,
         header_offset) = struct.unpack_from("<HHHHHHIIIHHHHHII", data, pos + 4)
        name = data[pos + 46:pos + 46 + name_len]
        total = 46 + name_len + extra_len + comment_len
        yield index, name, dict(header_offset=header_offset, method=method, crc=crc,
                                csize=csize, usize=usize, cd_pos=pos, cd_total=total,
                                cd_extra=data[pos + 46 + name_len:pos + 46 + name_len + extra_len],
                                cd_comment_len=comment_len)
        pos += total
        index += 1
    require(pos == end, f"CD walk overran: {pos:#x} != {end:#x}")


def cd_records(data: bytes) -> list:
    eocd = parse_eocd(data)
    recs = list(iter_cd_records(data, eocd["cd_off"], eocd["cd_size"]))
    require(len(recs) == eocd["count"], f"CD count mismatch: {len(recs)} vs {eocd['count']}")
    return recs


# ------------------------------------------------------------- entry building

def dos_datetime() -> tuple:
    return 0, 0x21  # fixed 1980-1-1 stamp, same convention as rewrite_zip


def build_local_entry(name: bytes, payload: bytes, *, stored: bool, align: int,
                      offset: int) -> tuple:
    """Local header + data placed at absolute [offset]; (bytes, cd-info)."""
    crc = zlib.crc32(payload) & 0xFFFFFFFF
    if stored:
        csize = usize = len(payload)
        body = payload
        method = 0
    else:
        compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
        body = compressor.compress(payload) + compressor.flush()
        csize, usize, method = len(body), len(payload), 8

    name_len = len(name)
    base = offset + 30 + name_len  # data offset without extra padding
    extra = b""
    if align and base % align:
        pad = align - (base % align)
        # Extra field is id(2)+size(2)+body: the total extra length must be
        # >= 4 AND congruent to pad (mod align) for data to land aligned. The
        # old pad<4 shortcut (len = pad+4) only held when align divided 4;
        # with align=16 it silently misaligned. Borrow whole align steps.
        extra_len = pad
        while extra_len < 4:
            extra_len += align
        extra = struct.pack("<HH", 0, extra_len - 4) + b"\x00" * (extra_len - 4)
    mtime, mdate = dos_datetime()
    header = struct.pack("<IHHHHHIIIHH", 0x04034b50, 20, 0, method, mtime, mdate,
                         crc, csize, usize, name_len, len(extra)) + name + extra
    cd = dict(header_offset=offset, method=method, crc=crc, csize=csize, usize=usize,
              name=name, extra_len=len(extra))
    return header + body, cd


def build_cd_record(cd: dict, *, cd_extra: bytes = b"") -> bytes:
    mtime, mdate = dos_datetime()
    name = cd["name"]
    return struct.pack("<IHHHHHHIIIHHHHHII", 0x02014b50, (3 << 8) | 20, 20, 0,
                       cd["method"], mtime, mdate, cd["crc"], cd["csize"],
                       cd["usize"], len(name), len(cd_extra), 0, 0, 0,
                       0o100644 << 16, cd["header_offset"]) + name + cd_extra


# -------------------------------------------------------------- layout build

def build_append_layout(original: bytes, appends: list, manifest_payload: bytes = None) -> bytes:
    """appends: (name=bytes, payload=bytes, stored=bool, align=int) tuples.

    manifest_payload (E2): patched AndroidManifest.xml bytes, stored and
    appended; its ORIGINAL CD record inside the original CD block is rewritten
    in place (same record length via CD extra padding) to point at it.
    """
    appends = [s if isinstance(s, dict) else
               dict(name=s[0], payload=s[1], stored=s[2], align=s[3]) for s in appends]
    eocd = parse_eocd(original)
    out = bytearray(original[:eocd["cd_off"]])
    new_records, extra_count = [], 0

    for spec in appends:
        entry, cd = build_local_entry(spec["name"], spec["payload"],
                                      stored=spec["stored"], align=spec.get("align", 4),
                                      offset=len(out))
        out += entry
        new_records.append(build_cd_record(cd))
        extra_count += 1

    patched_record = None
    if manifest_payload is not None:
        entry, cd = build_local_entry(MANIFEST_ENTRY, manifest_payload, stored=True,
                                      align=4, offset=len(out))
        out += entry
        # Its CD record overwrites the original manifest record slot in place
        # (same byte length), so the total entry count is NOT reduced here.
        # Match the original manifest record's total byte length via its CD
        # extra field so the in-place overwrite below is length-preserving.
        for _, name, info in iter_cd_records(original, eocd["cd_off"], eocd["cd_size"]):
            if name == MANIFEST_ENTRY:
                target_total = info["cd_total"]
                pad_needed = target_total - 46 - len(MANIFEST_ENTRY)
                require(pad_needed >= 0, "manifest CD record cannot fit")
                new_extra = info["cd_extra"].ljust(pad_needed, b"\x00")[:pad_needed]
                patched_record = build_cd_record(cd, cd_extra=new_extra)
                require(len(patched_record) == target_total,
                        f"patched manifest CD record {len(patched_record)} != {target_total}")
                break
        require(patched_record is not None, "original manifest CD record not found")

    cd_insert_at = len(out)
    out += b"".join(new_records)
    out += original[eocd["cd_off"]:eocd["cd_off"] + eocd["cd_size"]]
    if patched_record is not None:
        pos = cd_insert_at + sum(len(r) for r in new_records)
        # Locate the original manifest record inside the copied CD block and
        # overwrite it in place (byte-length identical).
        for _, name, info in iter_cd_records(bytes(out), pos, eocd["cd_size"]):
            if name == MANIFEST_ENTRY:
                out[info["cd_pos"]:info["cd_pos"] + info["cd_total"]] = patched_record
                break
        else:
            raise AssertionError("manifest record missing from copied CD block")
    total_count = eocd["count"] + extra_count
    out += struct.pack("<IHHHHIIH", 0x06054b50, 0, 0, total_count, total_count,
                       eocd["cd_size"] + sum(len(r) for r in new_records), cd_insert_at, 0)
    return bytes(out)


def verify_layout(final: bytes, original: bytes, appended: dict, label: str,
                  manifest_payload: bytes = None, v1_research: bool = False) -> dict:
    """The structural contract, checked the way the shell reads the file.

    v1_research (E1/E2): apksigner re-signed with v1 enabled -- the original
    v1 trio is replaced by RESEARCH.* files at the archive tail, MANIFEST.MF
    stays the last CD record; every other original entry must be untouched.
    Without it (E3/E4 baseline pipeline) the v1 trio is stripped entirely and
    the last CD record is whatever preceded it -- the proven 01-resigned
    treatment."""
    fe, oe = parse_eocd(final), parse_eocd(original)
    frecs, orecs = cd_records(final), cd_records(original)
    replaced = 1 if manifest_payload is not None else 0
    if v1_research:
        # Signed final: apksigner replaced the original v1 trio with files
        # named after the key alias; MANIFEST.MF is re-emitted last.
        require(frecs[-1][1] == V1_MARKER_NAME,
                f"last CD record is {frecs[-1][1]!r}, must be {V1_MARKER_NAME!r}")
        require(final[-42:-22] == original[-42:-22],
                "size-0x2a v1-marker window changed")
        expected_extra = {V1_MARKER_NAME}
        expected_extra |= {name for _, name, _ in frecs
                           if name.startswith(b"META-INF/")
                           and name.endswith((b".SF", b".RSA"))}
        require(len(expected_extra) > 1, "apksigner v1 signature files are missing")
        dropped = set(V1_ORIGINAL_FILES)
    else:
        # Constructed stage: original entries (v1 trio included) untouched;
        # the original CD block ends the archive so MANIFEST.MF is still last
        # and the v1-marker window still reads original bytes.
        require(frecs[-1][1] == V1_MARKER_NAME,
                f"last CD record is {frecs[-1][1]!r}, must be {V1_MARKER_NAME!r}")
        require(final[-42:-22] == original[-42:-22],
                "size-0x2a v1-marker window changed")
        expected_extra, dropped = set(), set()
    require(final[:64] == original[:64], "head 64B window changed")

    omap = {name: info for _, name, info in orecs}
    fbyname = {}
    for _, name, info in frecs:
        fbyname[name] = info
        keep = (name in omap and name not in dropped
                and not (manifest_payload is not None and name == MANIFEST_ENTRY))
        if keep:
            o = omap[name]
            for field in ("header_offset", "method", "crc", "csize", "usize"):
                require(info[field] == o[field],
                        f"{name}: CD field {field} moved: {o[field]} -> {info[field]}")
    if manifest_payload is not None:
        m = fbyname[MANIFEST_ENTRY]
        require(m["method"] == 0 and m["usize"] == len(manifest_payload),
                "manifest entry not the patched STORED payload")
    expected_names = (set(omap) - dropped) | expected_extra | \
        {n.encode() for n in appended}
    if manifest_payload is not None:
        expected_names.add(MANIFEST_ENTRY)
    extra_detected = set(fbyname) - expected_names
    require(not (extra_detected - {n.encode() for n in appended}),
            f"unexpected entries: {sorted(extra_detected)}")
    require(set(fbyname) == expected_names | extra_detected, "entry name set mismatch")

    for name, o in omap.items():
        if name in dropped or (manifest_payload is not None and name == MANIFEST_ENTRY):
            continue
        off = o["header_offset"]
        require(final[off:off + 4] == b"PK\x03\x04", f"{name}: local header gone at {off:#x}")
        nlen = struct.unpack_from("<H", final, off + 26)[0]
        require(final[off + 30:off + 30 + nlen] == name, f"{name}: local name mismatch")

    with zipfile.ZipFile(io.BytesIO(final)) as zf, zipfile.ZipFile(io.BytesIO(original)) as zo:
        require(zf.testzip() is None, "final zip CRC broken")
        for idx, name, info in orecs:
            sname = name.decode("utf-8")
            if name in dropped:
                continue
            if manifest_payload is not None and name == MANIFEST_ENTRY:
                require(zf.read(sname) == manifest_payload, "manifest payload mismatch")
            else:
                require(zf.read(sname) == zo.read(sname), f"{sname}: content changed")
        for name, payload in appended.items():
            require(zf.read(name) == payload, f"{name}: appended content mismatch")
    return dict(label=label, size=len(final), cd_off=fe["cd_off"], cd_size=fe["cd_size"],
                entries=fe["count"], last_cd_record=frecs[-1][1].decode(),
                appended=sorted(appended))


# ------------------------------------------------------ neutralization tools

def rename_manifest(data: bytes, old: bytes, new: bytes) -> bytes:
    """Equal-length rename inside the binary manifest string pool (either
    encoding). Returns the patched bytes; callers re-store them."""
    require(len(old) == len(new), "manifest rename must be equal length")
    utf8 = data.count(old)
    utf16 = data.count(old.decode().encode("utf-16-le"))
    require(utf8 + utf16 == 1,
            f"manifest: expected exactly 1 occurrence of {old!r}, utf8={utf8} utf16={utf16}")
    if utf8:
        return data.replace(old, new)
    return data.replace(old.decode().encode("utf-16-le"), new.decode().encode("utf-16-le"))


# ------------------------------------------------------------------- signing

def align_and_sign(source: Path, final: Path, sdk: Path, jdk: Path, keystore: Path,
                   password_file: Path | None, key_alias: str, logs: Path, name: str,
                   *, zipalign_rewrite: bool, v1: bool = False,
                   build_tools: Path | None = None,
                   password_env: str | None = None) -> dict:
    """zipalign (rewrite only where layout allows) + apksigner + checks.

    v1=True re-emits the JAR signature trio (MANIFEST.MF last) so the shell's
    size-0x2a marker window stays original. v1=False signs with v2/v3 only.
    """
    require(password_file is not None or password_env,
            "a keystore password file or environment variable is required")
    build_tools = build_tools or pick_build_tools(sdk)
    exe = exe_suffix()
    password_arg = (f"file:{password_file}" if password_file is not None
                    else f"env:{password_env}")
    java = jdk / f"bin/java{exe}"
    signer = [str(java), "-jar", str(build_tools / "lib/apksigner.jar")]
    aligner = str(build_tools / f"zipalign{exe}")
    logs.mkdir(parents=True, exist_ok=True)
    stage = final.with_name(final.stem + "-stage.apk")

    def run(cmd, log):
        result = subprocess.run([str(c) for c in cmd], capture_output=True, text=True,
                                encoding="utf-8", errors="replace")
        (logs / log).write_text(" ".join(map(str, cmd)) + "\n\n" + result.stdout + "\n" + result.stderr,
                                encoding="utf-8")
        require(result.returncode == 0, f"{log} failed:\n{result.stdout}\n{result.stderr}")
        return result.stdout

    if zipalign_rewrite:
        run([aligner, "-P", "16", "-f", "4", source, stage], f"{name}-align.log")
    else:
        run([aligner, "-c", "-P", "16", "4", source], f"{name}-align-check.log")
        stage.write_bytes(source.read_bytes())
    run([*signer, "sign", "--ks", keystore, "--ks-key-alias", key_alias,
         "--ks-pass", password_arg,
         "--v1-signing-enabled", "true" if v1 else "false",
         "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
         "--out", final, stage], f"{name}-sign.log")
    run([aligner, "-c", "-P", "16", "4", final], f"{name}-alignment-check.log")
    sig = run([*signer, "verify", "--min-sdk-version", "24", "--verbose", "--print-certs", final],
              f"{name}-signature-check.log")
    stage.unlink()
    return dict(signature=sig.strip().splitlines()[:4], signature_text=sig, sha256=sha256(final))


# ----------------------------------------------------------------- E1 helper

def build_e1_like(original_path: Path, output: Path, appends: list,
                  manifest_old_new: tuple = None, *, keystore: Path,
                  password_file: Path | None, sdk: Path, jdk: Path, label: str,
                  logs: Path, key_alias: str = "freemomo",
                  build_tools: Path | None = None,
                  password_env: str | None = None) -> dict:
    """Append-style repack + optional manifest class rename + v1-inclusive
    v2/v3 signing + full structural verification (pre- and post-signature)."""
    require(sha256(original_path) == ORIGINAL_SHA256, "Unexpected original APK")
    original = original_path.read_bytes()
    appended = {name.decode(): payload for name, payload, _s, _a in appends}
    manifest_payload = None
    if manifest_old_new:
        old, new = manifest_old_new
        with zipfile.ZipFile(io.BytesIO(original)) as zo:
            manifest = zo.read("AndroidManifest.xml")
        manifest_payload = rename_manifest(manifest, old.encode(), new.encode())
    constructed = build_append_layout(original, appends, manifest_payload)
    staged = output.with_name(output.stem + "-constructed.apk")
    staged.write_bytes(constructed)
    struct_report = verify_layout(constructed, original, appended, label,
                                  manifest_payload=manifest_payload,
                                  v1_research=False)
    signed = align_and_sign(staged, output, sdk, jdk, keystore, password_file,
                            key_alias, logs, label, zipalign_rewrite=False, v1=True,
                            build_tools=build_tools, password_env=password_env)
    # Post-signing structural re-check (apksigner replaces the v1 trio and
    # inserts the v2/v3 block; original entries must survive untouched).
    final_bytes = output.read_bytes()
    post = verify_layout(final_bytes, original, appended, label + "-signed",
                         manifest_payload=manifest_payload, v1_research=True)
    staged.unlink()
    return dict(**post, signing=signed, size_delta=len(final_bytes) - len(original))


# ----------------------------------------------------------------------- CLI

def cmd_verify(args):
    final = Path(args.final).read_bytes()
    original = Path(args.original).resolve()
    require(sha256(original) == ORIGINAL_SHA256, "Unexpected original APK")
    original_bytes = original.read_bytes()
    appended = {}
    for spec in args.append:
        name, _, path = spec.partition("=")
        appended[name] = Path(path).read_bytes()
    frecs = cd_records(final)
    names = {name for _, name, _ in frecs}
    v1_research = any(name.startswith(b"META-INF/") and name.endswith(b".SF")
                      and name not in V1_ORIGINAL_FILES for name in names)
    manifest_payload = None
    with zipfile.ZipFile(io.BytesIO(final)) as zf, zipfile.ZipFile(io.BytesIO(original_bytes)) as zo:
        if zf.read("AndroidManifest.xml") != zo.read("AndroidManifest.xml"):
            manifest_payload = zf.read("AndroidManifest.xml")
    report = verify_layout(final, original_bytes, appended, "cli-verify",
                           manifest_payload=manifest_payload, v1_research=v1_research)
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("verify", help="structural verify of a built APK against the original")
    p.add_argument("--final", required=True)
    p.add_argument("--original", type=Path, default=ORIGINAL_DEFAULT)
    p.add_argument("--append", action="append", default=[],
                   help="name=payload path for entries appended by the build (repeatable)")
    p.set_defaults(func=cmd_verify)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
