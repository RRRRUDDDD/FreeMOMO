"""Build the FreeMOMO rootless package (base + feature split).

This repository ships source only. The build needs three things the user
provides or generates locally:

  * the official 墨墨背单词 5.6.00 APK (versionCode 900, exact SHA-256 below),
  * the FreeMOMO module APK built from ``app/``,
  * a signing key. If none is given, one is generated under the output
    directory on first run. Keep it: updates must use the same key.

``hiddenapibypass-6.1.aar`` is downloaded from Maven Central on first use and
verified against the pinned digest in ``rootless/dependencies.json``.

Outputs (under ``build/rootless`` by default):
  base.apk                              original layout + renamed factory
  artifacts/freemomo-rootless-<ver>-split.apk
  artifacts/freemomo-rootless-<ver>.apks   base + split for SAI
  artifacts/freemomo-rootless-<ver>-probe.apk  optional self-test app
  artifacts/SHA256SUMS.txt
"""
import argparse
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "rootless"))

import custom_repack as cr  # noqa: E402
from verify_artifacts import (ORIGINAL_SHA256, PACKAGE, VERSION_CODE,  # noqa: E402
                              VERSION_NAME, SPLIT_FACTORY, require, sha256)

DEPS = json.loads((ROOT / "rootless/dependencies.json").read_text(encoding="utf-8"))
MANIFEST_OLD = "com.secneo.apkwrapper.AP"
MANIFEST_NEW = SPLIT_FACTORY

# The production table leaves out framework lifecycle targets that are not
# needed by the module and were the source of double-dispatch artifacts.
PRODUCTION_DROP = {"upd_page_destroy", "app_oncreate", "instr_callappcreate", "aw_attachbase"}

BRIDGE_RET = {"void": "void", "boolean": "boolean", "int": "int", "long": "long",
              "float": "float", "double": "double"}
DISPATCH = {"void": "dispatchV", "boolean": "dispatchZ", "int": "dispatchI"}


def app_version() -> str:
    text = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    match = re.search(r'def\s+appVersionName\s*=\s*"([^"]+)"', text)
    require(match is not None, "cannot read appVersionName from app/build.gradle")
    return match.group(1)


def exe(name: str) -> str:
    return name + (".exe" if os.name == "nt" else "")


def fresh(path: Path) -> Path:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)
    return path


def run(cmd, what, cwd=None, env=None):
    result = subprocess.run([str(c) for c in cmd], capture_output=True, cwd=cwd, env=env)
    stdout = result.stdout.decode("utf-8", "replace")
    stderr = result.stderr.decode("utf-8", "replace")
    require(result.returncode == 0, f"{what} failed:\n{stdout}\n{stderr}")
    return stdout


def resolve_sdk(value):
    if value:
        sdk = Path(value)
    else:
        env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if env:
            sdk = Path(env)
        elif os.name == "nt":
            sdk = Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk"
        else:
            sdk = Path.home() / "Android/Sdk"
    require(sdk.is_dir(), f"Android SDK not found: {sdk} (use --sdk or ANDROID_HOME)")
    return sdk.resolve()


def jdk_home_of(java: Path):
    """Ask a java executable for its own home; handles the Windows javapath shim."""
    try:
        result = subprocess.run([str(java), "-XshowSettings:properties", "-version"],
                                capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    for line in (result.stdout + result.stderr).splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "java.home":
            return Path(value.strip())
    return None


def resolve_jdk(value):
    candidates = [Path(value)] if value else []
    env = os.environ.get("JAVA_HOME")
    if env:
        candidates.append(Path(env))
    on_path = shutil.which("java")
    if on_path:
        candidates.append(Path(on_path))
    tried = []
    for candidate in candidates:
        if candidate.is_file():
            home = jdk_home_of(candidate)
            if home is None:
                tried.append(f"{candidate} (not a JDK launcher)")
                continue
            candidate = home
        tried.append(str(candidate))
        java = candidate / "bin" / exe("java")
        javac = candidate / "bin" / exe("javac")
        keytool = candidate / "bin" / exe("keytool")
        if java.is_file() and javac.is_file() and keytool.is_file():
            return candidate.resolve()
    raise SystemExit("JDK 21 not found (use --jdk or set JAVA_HOME); tried: "
                     + ", ".join(tried))


def resolve_original(value):
    candidates = [value] if value else [os.environ.get("FREEMOMO_ORIGINAL"),
                                        ROOT / "墨墨背单词_5.6.00.apk"]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            original = Path(candidate).resolve()
            break
    else:
        raise SystemExit("official 墨墨背单词 5.6.00 APK not found "
                         "(use --original or FREEMOMO_ORIGINAL)")
    require(sha256(original) == ORIGINAL_SHA256,
            "unsupported original APK: expected the official 5.6.00 build "
            f"(SHA-256 {ORIGINAL_SHA256})")
    return original


def resolve_module_apk(value, version):
    if value:
        module = Path(value).resolve()
        require(module.is_file(), f"module APK not found: {module}")
        return module
    candidates = [
        ROOT / "build" / f"freemomo-{version}.apk",
        ROOT / "app/build/outputs/apk/release" / f"freemomo-{version}-release-unsigned.apk",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise SystemExit("module APK not found. Build it first:\n"
                     "  .\\gradlew.bat :app:assembleRelease\n"
                     "or pass --module-apk <path>")


def download_hidden_api(target: Path):
    spec = DEPS["hidden_api"]
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".download")
    print(f"downloading {spec['name']} ...")
    with urllib.request.urlopen(spec["url"], timeout=120) as source, \
            temporary.open("xb") as output:
        shutil.copyfileobj(source, output, 1024 * 1024)
    require(sha256(temporary) == spec["sha256"], "hiddenapibypass digest mismatch")
    temporary.rename(target)
    return target


def resolve_hidden_api(value, out_root: Path):
    if value:
        path = Path(value).resolve()
        require(path.is_file(), f"hiddenapibypass AAR not found: {path}")
        require(sha256(path) == DEPS["hidden_api"]["sha256"],
                "hiddenapibypass digest mismatch")
        return path
    env = os.environ.get("FREEMOMO_HIDDEN_API_AAR")
    if env:
        path = Path(env).resolve()
        require(path.is_file(), f"hiddenapibypass AAR not found: {path}")
        require(sha256(path) == DEPS["hidden_api"]["sha256"],
                "hiddenapibypass digest mismatch")
        return path
    target = out_root / "deps/hiddenapibypass-6.1.aar"
    if target.is_file() and sha256(target) == DEPS["hidden_api"]["sha256"]:
        return target
    try:
        return download_hidden_api(target)
    except OSError as error:
        raise SystemExit(f"cannot download hiddenapibypass: {error}\n"
                         "download it manually and pass --hidden-api-aar") from error


def resolve_signing(args, out_root: Path, jdk: Path):
    signing_dir = out_root / "signing"
    keystore = (Path(args.keystore) if args.keystore
                else Path(os.environ.get("FREEMOMO_KEYSTORE",
                                         signing_dir / "freemomo.p12"))).resolve()
    alias = args.key_alias or os.environ.get("FREEMOMO_KEY_ALIAS", "freemomo")
    password_file = (Path(args.keystore_password_file)
                     if args.keystore_password_file else None)
    if password_file is None and keystore.exists():
        sibling = keystore.with_name("password.txt")
        if sibling.is_file():
            password_file = sibling
    password_env = None if password_file else "FREEMOMO_KEYSTORE_PASSWORD"

    if not keystore.exists():
        keystore.parent.mkdir(parents=True, exist_ok=True)
        if password_file is not None:
            require(password_file.is_file(), f"keystore password file not found: {password_file}")
            password = password_file.read_text(encoding="utf-8").strip()
            require(password, "keystore password file is empty")
        else:
            password = secrets.token_urlsafe(24)
        run([jdk / "bin" / exe("keytool"), "-genkeypair", "-noprompt",
             "-keystore", keystore, "-storetype", "PKCS12", "-alias", alias,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", password, "-keypass", password,
             "-dname", "CN=FreeMOMO Rootless, O=Local, C=CN"],
            "signing key generation")
        if password_file is None:
            password_file = keystore.with_name("password.txt")
            password_file.write_text(password + "\n", encoding="utf-8")
            try:
                password_file.chmod(0o600)
            except OSError:
                pass
        password_env = None
        print(f"generated signing key: {keystore}")
        print(f"keep {keystore.name} and {password_file.name}; updates must use the same key")

    if password_file is not None:
        require(password_file.is_file(), f"keystore password file not found: {password_file}")
        password_file = password_file.resolve()
    elif not os.environ.get("FREEMOMO_KEYSTORE_PASSWORD"):
        raise SystemExit("keystore password not supplied "
                         "(use --keystore-password-file or FREEMOMO_KEYSTORE_PASSWORD)")
    return keystore, password_file, password_env, alias


def bridge_ret_java(ret: str) -> str:
    return BRIDGE_RET.get(ret, "java.lang.Object")


def bridge_dispatch(ret: str):
    if ret in DISPATCH:
        return DISPATCH[ret]
    if ret in ("float", "double", "long"):
        raise SystemExit(f"no dispatch wrapper for return type {ret}")
    return "dispatchR"


def gen_bridges(targets) -> str:
    reg_lines, method_lines = [], []
    for slug, cls, member, params, ret, is_static, is_ctor in targets:
        name = "b_" + slug
        backup = "b_" + slug + "_orig"
        params_arr = "new String[]{" + ", ".join(f'"{p}"' for p in params) + "}"
        reg_lines.append(
            f'        register("{name}", "{cls}", "{member}", {params_arr}, '
            f'{str(is_static).lower()}, {str(is_ctor).lower()}, "{backup}");')

        jparams = []
        if is_ctor or not is_static:
            jparams.append("Object thiz")
        for i, p in enumerate(params):
            jparams.append({"int": f"int a{i}", "long": f"long a{i}", "boolean": f"boolean a{i}",
                            "float": f"float a{i}", "double": f"double a{i}"}.get(p, f"Object a{i}"))
        args_expr = ("ArtHookEngine.EMPTY_ARGS" if not params
                     else "new Object[]{" + ", ".join(f"a{i}" for i in range(len(params))) + "}")
        receiver = "null" if is_static else "thiz"

        method_lines.append(f"    public static {bridge_ret_java(ret)} {name}({', '.join(jparams)}) throws Throwable {{")
        if ret == "void":
            method_lines.append(f'        ArtHookEngine.{bridge_dispatch(ret)}("{name}", {receiver}, {args_expr});')
        else:
            method_lines.append(f'        return ArtHookEngine.{bridge_dispatch(ret)}("{name}", {receiver}, {args_expr});')
        method_lines.append("    }")
        method_lines.append(f"    public static {bridge_ret_java(ret)} {backup}({', '.join(jparams)}) {{")
        method_lines.append("        throw new UnsupportedOperationException(")
        method_lines.append(f'                "FreeMOMO shadow backup deopt: {name}");')
        method_lines.append("    }")
    return "\n".join(
        ["package com.repack.bridge;", "",
         "import com.repack.art.ArtHookEngine;", "",
         "// GENERATED by rootless/build_rootless.py -- do not edit by hand.",
         "public final class Bridges {", "",
         "    private Bridges() {}", "",
         "    static {",
         *reg_lines,
         "    }", "",
         "    private static void register(String id, String cls, String member, String[] params,",
         "            boolean isStatic, boolean isCtor, String backup) {",
         "        com.repack.art.BridgeRegistry.register(id, cls, member, params, isStatic, isCtor, id, backup);",
         "    }", "",
         *method_lines,
         "}", ""])


# (slug, declaringClass, memberName, paramTypeNames, returnTypeName, isStatic, isCtor)
BRIDGE_TARGETS = [
    ("word", "com.maimemo.android.momo.a", "s", [], "int", True, False),
    ("cloud", "ej7", "c", [], "int", True, False),
    ("disp_g", "dgb", "g", ["android.widget.TextView", "int"], "void", True, False),
    ("disp_h", "dgb", "h", ["android.widget.TextView", "int", "boolean", "float"], "void", True, False),
    ("priv_k", "com.maimemo.android.momo.user.level.a", "k",
     ["com.maimemo.android.momo.user.level.PrivilegeCode"], "boolean", True, False),
    ("priv_m", "com.maimemo.android.momo.user.level.a", "m",
     ["com.maimemo.android.momo.user.level.PrivilegeCode"], "boolean", True, False),
    ("priv_l", "com.maimemo.android.momo.user.level.a", "l",
     ["com.maimemo.android.momo.user.level.PrivilegeCode", "boolean"], "boolean", True, False),
    ("attach", "android.app.Application", "attach", ["android.content.Context"], "void", False, False),
    ("app_oncreate", "android.app.Application", "onCreate", [], "void", False, False),
    ("instr_callappcreate", "android.app.Instrumentation", "callApplicationOnCreate",
     ["android.app.Application"], "void", False, False),
    ("aw_attachbase", "com.secneo.apkwrapper.AW", "attachBaseContext",
     ["android.content.Context"], "void", False, False),
    ("gson_toj_obj", "com.google.gson.Gson", "toJson", ["java.lang.Object"], "java.lang.String", False, False),
    ("gson_toj_obj_type", "com.google.gson.Gson", "toJson",
     ["java.lang.Object", "java.lang.reflect.Type"], "java.lang.String", False, False),
    ("gson_toj_je", "com.google.gson.Gson", "toJson", ["com.google.gson.JsonElement"], "java.lang.String", False, False),
    ("gson_toj_obj_app", "com.google.gson.Gson", "toJson",
     ["java.lang.Object", "java.lang.Appendable"], "void", False, False),
    ("gson_toj_obj_type_app", "com.google.gson.Gson", "toJson",
     ["java.lang.Object", "java.lang.reflect.Type", "java.lang.Appendable"], "void", False, False),
    ("gson_toj_je_app", "com.google.gson.Gson", "toJson",
     ["com.google.gson.JsonElement", "java.lang.Appendable"], "void", False, False),
    ("gson_toj_el5", "com.google.gson.Gson", "toJson", ["el5"], "java.lang.String", False, False),
    ("gson_toj_el5_app", "com.google.gson.Gson", "toJson",
     ["el5", "java.lang.Appendable"], "void", False, False),
    ("json_tostr", "org.json.JSONObject", "toString", [], "java.lang.String", False, False),
    ("json_tostr_i", "org.json.JSONObject", "toString", ["int"], "java.lang.String", False, False),
    ("secneo_is", "com.secneo.apkwrapper.H", "is", ["int"], "boolean", True, False),
    ("secneo_callsw", "com.secneo.apkwrapper.H", "callSW", ["android.content.Context"], "void", True, False),
    ("secneo_callbs", "com.secneo.apkwrapper.H", "callBS", ["android.content.Context"], "void", True, False),
    ("upd_page_destroy", "android.app.Activity", "onDestroy", [], "void", False, False),
    ("upd_page_upgrade", "com.maimemo.android.momo.settings.appinfo.AppUpgradeActivity", "U0",
     ["android.view.ViewGroup", "android.os.Bundle"], "void", False, False),
    ("upd_page_about", "com.maimemo.android.momo.settings.appinfo.AboutMaiMemoActivity", "U0",
     ["android.view.ViewGroup", "android.os.Bundle"], "void", False, False),
    ("upd_request_get", "q60", "get", [], "java.lang.Object", False, False),
    ("upd_result_upgrade", "com.maimemo.android.momo.settings.appinfo.c", "accept",
     ["java.lang.Object"], "void", False, False),
    ("upd_result_about", "u99", "accept", ["java.lang.Object"], "void", False, False),
    ("upd_click_upgrade", "z70", "onClick", ["android.view.View"], "void", False, False),
    ("upd_click_dialog", "jx6", "invoke", [], "java.lang.Object", False, False),
    ("upd_dispatch", "wpd", "e",
     ["android.content.Context", "com.maimemo.android.momo.model.upgrade.AppUpgradeDialogInfo"],
     "void", True, False),
    ("upd_model_approve", "com.maimemo.android.momo.upgrade.d", "d", [], "void", False, False),
    ("upd_model_download", "com.maimemo.android.momo.upgrade.d", "e",
     ["java.lang.String", "com.maimemo.android.momo.upgrade.MemoAppUpgradeModel$PromptType", "boolean"],
     "boolean", False, False),
    ("upd_model_install", "com.maimemo.android.momo.upgrade.d", "c", ["java.lang.String"], "void", False, False),
    ("upd_model_close", "com.maimemo.android.momo.upgrade.d", "a", ["boolean"], "void", False, False),
    ("upd_cb_precheck", "com.maimemo.android.momo.upgrade.c", "accept", ["java.lang.Object"], "void", False, False),
    ("upd_cb_proceed", "com.maimemo.android.momo.upgrade.b", "run", [], "void", False, False),
    ("upd_cb_cancel", "oq6", "run", [], "void", False, False),
    ("upd_cb_install", "gs2", "run", [], "void", False, False),
    ("upd_cb_retry", "com.maimemo.android.momo.upgrade.a", "a",
     ["com.maimemo.android.momo.ui.widget.dialog.MMCommonDialogFragment", "hi6"], "boolean", False, False),
    ("upd_conn_connected", "q55", "onServiceConnected",
     ["android.content.ComponentName", "android.os.IBinder"], "void", False, False),
    ("upd_conn_disconnected", "q55", "onServiceDisconnected", ["android.content.ComponentName"], "void", False, False),
    ("upd_fg_progress", "lx6", "a", ["int"], "void", False, False),
    ("upd_fg_failure", "lx6", "b", ["java.lang.Throwable"], "void", False, False),
    ("upd_fg_complete", "lx6", "c", ["java.lang.String"], "void", False, False),
    ("upd_bg_progress", "p55", "a", ["int"], "void", False, False),
    ("upd_bg_failure", "p55", "b", ["java.lang.Throwable"], "void", False, False),
    ("upd_bg_complete", "p55", "c", ["java.lang.String"], "void", False, False),
    ("upd_helper_network", "com.maimemo.android.momo.upgrade.InAppUpgradeHelper",
     "checkNetworkStatusOnDownloadFile",
     ["android.content.Context", "long", "java.lang.Runnable", "java.lang.Runnable"], "void", True, False),
    ("upd_helper_start", "com.maimemo.android.momo.upgrade.InAppUpgradeHelper", "startDownload",
     ["android.content.Context", "java.lang.String", "boolean", "e70"],
     "android.content.ServiceConnection", True, False),
    ("upd_helper_retry", "com.maimemo.android.momo.upgrade.InAppUpgradeHelper", "retryDownload",
     ["android.content.Context", "boolean"], "void", True, False),
    ("upd_helper_stop", "com.maimemo.android.momo.upgrade.InAppUpgradeHelper", "stopDownload",
     ["android.content.Context"], "void", True, False),
    ("upd_pi_getbroadcast", "android.app.PendingIntent", "getBroadcast",
     ["android.content.Context", "int", "android.content.Intent", "int"],
     "android.app.PendingIntent", True, False),
    ("upd_retry_receive", "com.maimemo.android.momo.upgrade.MemoDownloadService$NotificationReceiver",
     "onReceive", ["android.content.Context", "android.content.Intent"], "void", False, False),
    ("upd_notif_sync", "mk7", "k",
     ["com.maimemo.android.momo.network.response.notification.NotificationSyncResponse"], "void", True, False),
    ("upd_notif_consume", "vsd", "accept", ["java.lang.Object"], "void", False, False),
    ("upd_notif_dialog", "com.maimemo.android.momo.notification.d", "a",
     ["com.maimemo.android.momo.notification.Notification$Dialog", "android.app.Activity",
      "com.maimemo.android.momo.notification.Notification", "boolean", "mc4", "p79", "int"],
     "void", True, False),
    ("upd_notif_im", "com.maimemo.android.momo.notification.Notification", "isIM", [], "boolean", False, False),
    ("upd_ctor_request", "q60", "<init>", ["int"], "void", False, True),
    ("upd_ctor_result_upgrade", "com.maimemo.android.momo.settings.appinfo.c", "<init>",
     ["com.maimemo.android.momo.settings.appinfo.AppUpgradeActivity"], "void", False, True),
    ("upd_ctor_result_about", "u99", "<init>", ["int", "java.lang.Object"], "void", False, True),
    ("upd_ctor_model", "com.maimemo.android.momo.upgrade.d", "<init>",
     ["android.content.Context", "com.maimemo.android.momo.model.upgrade.AppUpgradeDialogInfo"],
     "void", False, True),
    ("upd_ctor_precheck", "com.maimemo.android.momo.upgrade.c", "<init>",
     ["com.maimemo.android.momo.upgrade.d", "java.lang.String",
      "com.maimemo.android.momo.upgrade.MemoAppUpgradeModel$PromptType"], "void", False, True),
    ("upd_ctor_proceed", "com.maimemo.android.momo.upgrade.b", "<init>",
     ["com.maimemo.android.momo.upgrade.d", "java.lang.String",
      "com.maimemo.android.momo.upgrade.MemoAppUpgradeModel$PromptType"], "void", False, True),
    ("upd_ctor_cancel", "oq6", "<init>", ["int", "java.lang.Object"], "void", False, True),
    ("upd_ctor_dialog", "jx6", "<init>", ["com.maimemo.android.momo.upgrade.d", "int"], "void", False, True),
    ("upd_ctor_install", "gs2", "<init>", ["java.lang.Object", "int", "java.lang.Object"], "void", False, True),
    ("upd_ctor_retry", "com.maimemo.android.momo.upgrade.a", "<init>",
     ["kx6", "com.maimemo.android.momo.upgrade.d"], "void", False, True),
    ("upd_ctor_connection", "q55", "<init>", ["boolean", "e70"], "void", False, True),
    ("upd_ctor_foreground", "lx6", "<init>",
     ["com.maimemo.android.momo.upgrade.d", "com.maimemo.android.momo.databinding.ViewAppUpgradeProgressBinding"],
     "void", False, True),
    ("upd_ctor_background", "p55", "<init>", ["int", "java.lang.Object"], "void", False, True),
]

for slug, cls, member, params, ret, is_static, is_ctor in BRIDGE_TARGETS:
    if is_static or is_ctor:
        continue
    if cls.startswith("android.") and slug not in (
            "attach", "app_oncreate", "instr_callappcreate", "upd_page_destroy"):
        raise SystemExit(f"virtual framework target needs review: {slug} {cls}.{member}")

PROBE_TARGETS = [
    ("attach", "android.app.Application", "attach", ["android.content.Context"], "void", False, False),
    ("onresume", "android.app.Activity", "onResume", [], "void", False, False),
    ("value", "com.freemomo.probe.ProbeTarget", "value", [], "int", True, False),
    ("value2", "com.freemomo.probe.ProbeTarget", "value2", [], "int", True, False),
    ("ctor", "com.freemomo.probe.ProbeTarget", "<init>", ["int"], "void", False, True),
    ("vbounce", "com.freemomo.probe.ProbeVirtual$Base", "bounce", [], "void", False, False),
]

PROBE_MANIFEST = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    package="com.freemomo.probe" android:versionCode="1" android:versionName="1.0">\n'
    '    <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>\n'
    '    <application android:label="FreeMOMO Probe" android:hasCode="true"\n'
    '        android:name="com.freemomo.probe.ProbeApp" android:debuggable="true">\n'
    '        <activity android:name="com.freemomo.probe.MainActivity" android:exported="true">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.intent.action.MAIN"/>\n'
    '                <category android:name="android.intent.category.LAUNCHER"/>\n'
    '            </intent-filter>\n'
    '        </activity>\n'
    '    </application>\n'
    '</manifest>\n')

SPLIT_MANIFEST = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    f'    package="{PACKAGE}"\n'
    '    split="repack01"\n'
    '    android:isFeatureSplit="true"\n'
    f'    android:versionCode="{VERSION_CODE}"\n'
    f'    android:versionName="{VERSION_NAME}">\n'
    '    <application android:hasCode="true" android:extractNativeLibs="false"/>\n'
    '</manifest>\n')


class Builder:
    def __init__(self, args):
        self.version = app_version()
        self.sdk = resolve_sdk(args.sdk)
        self.jdk = resolve_jdk(args.jdk)
        self.build_tools = (Path(args.build_tools).resolve() if args.build_tools
                            else cr.pick_build_tools(self.sdk))
        self.android_jar = self.sdk / "platforms/android-36/android.jar"
        require(self.android_jar.is_file(),
                f"Android platform 36 not installed: {self.android_jar}")
        self.aapt2 = self.build_tools / exe("aapt2")
        self.d8jar = self.build_tools / "lib/d8.jar"
        self.java = self.jdk / "bin" / exe("java")
        self.javac = self.jdk / "bin" / exe("javac")
        self.jar = self.jdk / "bin" / exe("jar")
        self.out_root = Path(args.out).resolve()
        self.art = self.out_root / "artifacts"
        self.logs = fresh(self.out_root / "logs")
        self.work = fresh(self.out_root / "work")
        self.art.mkdir(parents=True, exist_ok=True)

        self.original = resolve_original(args.original)
        self.module_apk = resolve_module_apk(args.module_apk, self.version)
        self.hidden_api = resolve_hidden_api(args.hidden_api_aar, self.out_root)
        self.keystore, self.password_file, self.password_env, self.key_alias = \
            resolve_signing(args, self.out_root, self.jdk)

        with zipfile.ZipFile(self.module_apk) as archive:
            self.module_dex = archive.read("classes.dex")
        print(f"module dex: {self.module_apk.name} {len(self.module_dex):,}B")

    def compile_dex(self, name: str, sources, classpath_dirs, manifest_xml: Path,
                    extra_dex_inputs=(), expect_in_dex=()) -> Path:
        classes = fresh(self.work / f"{name}-classes")
        classpath = os.pathsep.join(str(p) for p in [self.android_jar, *classpath_dirs])
        run([self.javac, "-classpath", classpath, "-implicit:none", "-encoding", "UTF-8",
             "-d", classes, *sorted(str(p) for p in sources)], f"{name} javac")
        dex_out = fresh(self.work / f"{name}-dex")
        run([self.java, "-classpath", self.d8jar, "com.android.tools.r8.D8",
             "--lib", self.android_jar, "--min-api", "28", "--output", dex_out,
             *[str(p) for p in extra_dex_inputs],
             *sorted(str(p) for p in classes.rglob("*.class"))], f"{name} d8")
        dex_bytes = (dex_out / "classes.dex").read_bytes()
        for marker in expect_in_dex:
            require(marker in dex_bytes, f"{name}: dex missing expected marker {marker!r}")

        unsigned = self.work / f"{name}-unsigned.apk"
        run([self.aapt2, "link", "-o", unsigned, "-I", self.android_jar,
             "--manifest", manifest_xml], f"{name} aapt2 link")
        staged = self.work / f"{name}-plus.apk"
        with zipfile.ZipFile(unsigned) as source, zipfile.ZipFile(staged, "w") as target:
            for info in source.infolist():
                target.writestr(info, source.read(info.filename))
            target.writestr(zipfile.ZipInfo("classes.dex", (2026, 1, 1, 0, 0, 0)), dex_bytes)
        return staged

    def hidden_api_classes(self) -> Path:
        directory = self.work / "hidden-api-classes"
        if not (directory / "classes.jar").is_file():
            fresh(directory)
            run([self.jar, "xf", self.hidden_api, "classes.jar"], "hidden-api extract", cwd=directory)
        return directory

    def validate_base(self, base: Path):
        badging = run([self.aapt2, "dump", "badging", base], "base badging check")
        expected = f"package: name='{PACKAGE}' versionCode='{VERSION_CODE}' versionName='{VERSION_NAME}'"
        require(expected in badging, f"unexpected base package metadata:\n{badging}")
        with zipfile.ZipFile(base) as archive:
            manifest = archive.read("AndroidManifest.xml")
        utf8 = MANIFEST_NEW.encode()
        utf16 = MANIFEST_NEW.encode("utf-16-le")
        require(utf8 in manifest or utf16 in manifest,
                f"base does not point its component factory at {MANIFEST_NEW}")

    def build_base(self, supplied: Path | None) -> Path:
        if supplied:
            base = supplied.resolve()
            require(base.is_file(), f"base APK not found: {base}")
        else:
            base = self.out_root / "base.apk"
            print("building base from the original APK ...")
            cr.build_e1_like(
                self.original, base, [], (MANIFEST_OLD, MANIFEST_NEW),
                keystore=self.keystore, password_file=self.password_file,
                sdk=self.sdk, jdk=self.jdk, label="base", logs=self.logs,
                key_alias=self.key_alias, build_tools=self.build_tools,
                password_env=self.password_env)
        self.validate_base(base)
        delivered = self.art / "base.apk"
        if base.resolve() != delivered.resolve():
            shutil.copyfile(base, delivered)
        return delivered

    def build_split(self) -> Path:
        generated = fresh(self.work / "generated")
        (generated / "com/repack/bridge").mkdir(parents=True)
        targets = [t for t in BRIDGE_TARGETS if t[0] not in PRODUCTION_DROP]
        (generated / "com/repack/bridge/Bridges.java").write_text(
            gen_bridges(targets), encoding="utf-8")

        fake_dir = fresh(self.work / "fake-classes-src")
        fake = fake_dir / "com/secneo/apkwrapper/AP.java"
        fake.parent.mkdir(parents=True)
        fake.write_text(
            "package com.secneo.apkwrapper;\n"
            "public final class AP extends android.app.AppComponentFactory {}\n",
            encoding="ascii")
        fake_classes = fresh(self.work / "fake-classes")
        run([self.javac, "-classpath", self.android_jar, "-d", fake_classes, fake],
            "fake factory javac")

        source = fresh(self.work / "split-src")
        shutil.copytree(ROOT / "rootless/engine/src", source, dirs_exist_ok=True)
        shutil.copytree(generated / "com/repack/bridge", source / "com/repack/bridge",
                        dirs_exist_ok=True)
        manifest = self.work / "split-manifest.xml"
        manifest.write_text(SPLIT_MANIFEST, encoding="utf-8")
        hidden_classes = self.hidden_api_classes()
        split = self.compile_dex(
            "rootless-split", sorted(source.rglob("*.java")),
            [fake_classes, hidden_classes / "classes.jar"], manifest,
            extra_dex_inputs=[hidden_classes / "classes.jar"],
            expect_in_dex=(b"hiddenapibypass", b"repack/appfactory/SplashGate",
                           b"markBusinessReady", b"onWordBridgeArmed"))

        fused = self.work / "rootless-split-fused.apk"
        with zipfile.ZipFile(split) as source_zip, zipfile.ZipFile(fused, "w") as target:
            for info in source_zip.infolist():
                target.writestr(info, source_zip.read(info.filename))
            target.writestr(zipfile.ZipInfo("classes2.dex", (2026, 1, 1, 0, 0, 0)),
                            self.module_dex)

        final = self.art / f"freemomo-rootless-{self.version}-split.apk"
        signing = cr.align_and_sign(
            fused, final, self.sdk, self.jdk, self.keystore, self.password_file,
            self.key_alias, self.logs, "split", zipalign_rewrite=True, v1=True,
            build_tools=self.build_tools, password_env=self.password_env)
        require("Verified using v2 scheme" in signing["signature_text"], "split is not v2-signed")
        with zipfile.ZipFile(final) as archive:
            names = set(archive.namelist())
            require("classes.dex" in names and "classes2.dex" in names,
                    f"split lost code entries: {sorted(names)}")
            require(archive.read("classes2.dex") == self.module_dex,
                    "module dex changed during signing")
        print(f"split: {len(targets)} bridges -> {final.name} {final.stat().st_size:,}B")
        return final

    def build_probe(self) -> Path:
        generated = fresh(self.work / "probe-generated")
        (generated / "com/repack/bridge").mkdir(parents=True)
        (generated / "com/repack/bridge/Bridges.java").write_text(
            gen_bridges(PROBE_TARGETS), encoding="utf-8")
        source = fresh(self.work / "probe-src")
        shutil.copytree(ROOT / "rootless/engine/src/com/repack/art",
                        source / "com/repack/art", dirs_exist_ok=True)
        shutil.copytree(ROOT / "rootless/engine/src/de", source / "de", dirs_exist_ok=True)
        shutil.copytree(ROOT / "rootless/probe/src", source, dirs_exist_ok=True)
        shutil.copytree(generated / "com/repack/bridge", source / "com/repack/bridge",
                        dirs_exist_ok=True)
        manifest = self.work / "probe-manifest.xml"
        manifest.write_text(PROBE_MANIFEST, encoding="utf-8")
        hidden_classes = self.hidden_api_classes()
        probe = self.compile_dex(
            "rootless-probe", sorted(source.rglob("*.java")),
            [hidden_classes / "classes.jar"], manifest,
            extra_dex_inputs=[hidden_classes / "classes.jar"],
            expect_in_dex=(b"hiddenapibypass", b"onWordBridgeArmed"))
        final = self.art / f"freemomo-rootless-{self.version}-probe.apk"
        cr.align_and_sign(probe, final, self.sdk, self.jdk, self.keystore,
                          self.password_file, self.key_alias, self.logs, "probe",
                          zipalign_rewrite=True, v1=False,
                          build_tools=self.build_tools, password_env=self.password_env)
        print(f"probe: {final.name} {final.stat().st_size:,}B")
        return final

    def bundle(self, base: Path, split: Path) -> Path:
        final = self.art / f"freemomo-rootless-{self.version}.apks"
        with zipfile.ZipFile(final, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr(zipfile.ZipInfo("meta/splits.xml", (2026, 1, 1, 0, 0, 0)),
                             '<?xml version="1.0" encoding="utf-8"?>\n'
                             '<packages>\n'
                             '    <package name="base.apk"/>\n'
                             '    <package name="split_repack01.apk"/>\n'
                             '</packages>\n')
            archive.writestr(zipfile.ZipInfo("base.apk", (2026, 1, 1, 0, 0, 0)),
                             base.read_bytes())
            archive.writestr(zipfile.ZipInfo("split_repack01.apk", (2026, 1, 1, 0, 0, 0)),
                             split.read_bytes())
        print(f"bundle: {final.name} {final.stat().st_size:,}B")
        return final

    def finish(self, produced, base: Path):
        lines = [f"{sha256(path)}  {path.name}" for path in sorted(produced)]
        lines.append(f"{sha256(base)}  [base] {base.name}")
        lines.append(f"{sha256(self.original)}  [original] {self.original.name}")
        lines.append(f"{sha256(self.module_apk)}  [module] {self.module_apk.name}")
        (self.art / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("done:", self.out_root)


def main():
    parser = argparse.ArgumentParser(
        description="Build the FreeMOMO rootless base + split from the official APK.")
    parser.add_argument("--original", type=Path,
                        help="official MoMo 5.6.00 APK (or FREEMOMO_ORIGINAL)")
    parser.add_argument("--module-apk", type=Path,
                        help="module APK built from app/ (default: auto-detect)")
    parser.add_argument("--base-apk", type=Path,
                        help="reuse an existing base APK signed with the same key")
    parser.add_argument("--hidden-api-aar", type=Path,
                        help="hiddenapibypass-6.1.aar (downloaded if omitted)")
    parser.add_argument("--keystore", type=Path,
                        help="PKCS12/JKS signing key (generated if omitted)")
    parser.add_argument("--keystore-password-file", type=Path,
                        help="file containing the keystore password")
    parser.add_argument("--key-alias", default=None,
                        help="signing key alias (default: freemomo)")
    parser.add_argument("--sdk", type=Path, help="Android SDK (or ANDROID_HOME)")
    parser.add_argument("--jdk", type=Path, help="JDK 21 home (or JAVA_HOME)")
    parser.add_argument("--build-tools", type=Path,
                        help="Android build-tools directory (default: newest installed)")
    parser.add_argument("--out", type=Path, default=ROOT / "build/rootless",
                        help="output directory (default: build/rootless)")
    parser.add_argument("--probe", action="store_true",
                        help="also build the standalone probe APK")
    parser.add_argument("--no-bundle", action="store_true",
                        help="do not create the base+split .apks bundle")
    args = parser.parse_args()

    builder = Builder(args)
    base = builder.build_base(args.base_apk)
    split = builder.build_split()
    produced = [split]
    if args.probe:
        produced.append(builder.build_probe())
    if not args.no_bundle:
        produced.append(builder.bundle(base, split))
    builder.finish(produced, base)


if __name__ == "__main__":
    main()
