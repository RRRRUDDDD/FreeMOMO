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

The monitor exits after a patch, a write failure, a stable signature mismatch, or 10 seconds.
Polling backs off from 0.5 ms for the first second, to 2 ms through the third second, then to
10 ms. High-frequency checks read only `/proc/self/statm`; a virtual-size change triggers an
immediate maps scan, with an 8 ms forced scan as a fallback. A candidate mismatch is rechecked
for 20 ms before failing closed. Logs use the tag `FreeMOMO.Zygisk` and distinguish patched,
already patched, signature mismatch, write failure, and timeout states.

Build on Windows with the project's NDK 21.4:

```powershell
.\zygisk-companion\build.ps1
```

The production library deliberately uses no C++ runtime dependency. It is compiled with
`-fno-threadsafe-statics` because the Zygisk entry registers once; this keeps ZN Linker from
having to resolve `libstdc++.so` while retaining the small module footprint.

The script reads the module version from `module/module.prop`, validates it, and emits
`zygisk-companion/build/freemomo-zygisk-<version>.zip` with its SHA-256.
Installing that ZIP changes root module state and requires a reboot; neither action is part of
the build script.
