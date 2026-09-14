# FreeMOMO Zygisk Companion

This arm64 Zygisk API v4 module is the native companion for
[FreeMOMO](https://github.com/RRRRUDDDD/FreeMOMO). It targets
`com.maimemo.android.momo`, keeps the existing LSPosed business hooks, and moves only the
SecNeo maps branch patch early enough to run before SecNeo forks its detector child.

The target process starts one detached monitor from `postAppSpecialize`. Non-target app
processes request `DLCLOSE_MODULE_LIBRARY` during `preAppSpecialize`. The monitor scans only
`/proc/self/maps`; it does not enumerate or write another PID. A candidate must be an anonymous
`0x117000 rwxp` mapping with ELF magic and all nine instruction signatures. Only then is
`payload + 0x1d744` changed from `0x54001741` to `0x34ff9e90`.

Only **4096-byte kernel pages** are supported. At monitor startup, `sysconf(_SC_PAGESIZE)`
checks the actual page size and logs an unsupported-size diagnostic before any scan or patch
on other page sizes (or if the query fails). A native 16 KiB-page VMA cannot have the exact
`0x117000` length; support requires separately verified mappings and fingerprints, not rounding
the size or weakening the checks.

The monitor exits after a patch, a write failure, a stable signature mismatch, or 10 seconds.
Polling backs off from 0.5 ms for the first second, to 2 ms through the third second, then to
10 ms. High-frequency checks read only `/proc/self/statm`; a virtual-size change triggers an
immediate maps scan. Forced full maps scans run every 8 ms during the first second, every 50 ms
from one through three seconds, and every 250 ms after that. A candidate mismatch bypasses this
backoff and is rechecked every 0.5 ms. Each real scan first collects the complete candidate
address set, up to a fixed capacity of 16, before attempting any patch. Exceeding that capacity
or failing to read complete maps data stops the monitor with a diagnostic and no patch.

The 20 ms mismatch window belongs to the unchanged, complete candidate set. Adding, removing,
or replacing a candidate restarts that window; a real empty scan clears it. Reordering the same
addresses does not restart it. A skipped statm poll is not an empty scan and cannot clear the
window. The total monotonic 10-second deadline is independent of these resets, is checked
before each maps scan, and caps the requested sleep. Scheduling delays and an in-flight system
call are not forcibly interrupted. Logs use the tag `FreeMOMO.Zygisk` and distinguish patched,
already patched, stable mismatch, write failure, timeout, candidate overflow, incomplete scan,
and unsupported page-size states.

The candidate set is an observation of map addresses, not a guarantee of mapping lifetime.
The patcher still reads and writes those addresses directly. Concurrent `munmap` or protection
changes can cause a fault between parsing maps and accessing an instruction. Atomic instruction
access and write verification do not prevent that fault; rereading maps would not remove the
race either. An unmap and replacement at the same address between scans is also not observable
as a set change. Host mapping lifetime still needs runtime validation; these changes do not
claim to resolve that risk.

Run the Linux host harness with a system `c++`, `g++`, or `clang++`:

```sh
sh zygisk-companion/test-host.sh
```

Both harnesses compile with strict warnings. The fingerprint/scanner harness requires 4096-byte
pages and executes the real patcher against isolated anonymous `rwxp` mappings in its own
process. It covers the original and already-patched fingerprints, invalid size and ELF inputs,
all nine mismatch points, complete multi-candidate scans, and overflow before any write.
The monitor-policy harness injects monotonic timestamps and scan results to test disappearance,
replacement, membership changes, reordered sets, skipped polls, 20 ms and 10-second boundaries,
backoff, terminal scan results, and unsupported page sizes. A failed check exits nonzero.

On Windows, run the same standalone harnesses on an existing arm64 Android device with 4096-byte
pages, using NDK 21.4 and adb (paths can be set with `-NdkPath` and `-AdbPath`):

```powershell
.\zygisk-companion\test-android.ps1 -DeviceSerial 'YOUR_DEVICE_SERIAL'
```

This helper builds two executables, runs them in a uniquely named `/data/local/tmp` directory,
and removes those temporary device files. It preserves the executables and command logs under
a new local `build/android-tests-<timestamp>-<id>/` directory. It does not install the module or
an APK, restart a device, or launch the target app. Running these harnesses is separate from
Zygisk/LSPosed integration or target-process lifetime validation.

Build on Windows with the project's NDK 21.4:

```powershell
.\zygisk-companion\build.ps1
```

The production library deliberately uses no C++ runtime dependency. It is compiled with
`-fno-threadsafe-statics` because the Zygisk entry registers once; this keeps ZN Linker from
having to resolve `libstdc++.so` while retaining the small module footprint.

The script reads the module version from `module/module.prop`, validates it, and emits
`zygisk-companion/build/release-<timestamp>-<id>/freemomo-zygisk-<version>.zip` with its SHA-256.
Both PowerShell helpers accept `-BuildSubdirectory` to choose a new directory under `build/`.
They reject existing output directories and keep earlier builds intact. Native objects and
libraries are also placed inside that new directory.
Installing that ZIP changes root module state and requires a reboot; neither action is part of
the build script.
