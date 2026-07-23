# Cinder

**A mobile Claude Code client.** Run the real Claude Code CLI on an Android phone — not a
reimplementation, not a remote shell, not a web wrapper. Anthropic's official `claude` binary
executes inside a self-contained Linux sandbox that ships with the app, and a Jetpack Compose UI
renders its output as a proper transcript.

> Unofficial and unaffiliated with Anthropic. Sideload only. arm64-v8a.

<br>

## What it is (and what it isn't)

Cinder is a **front-end**. It does not contain, copy, or reimplement Claude Code. The `claude`
binary is Anthropic's, published on the public npm registry, and **the app downloads it at runtime
from Anthropic's own servers** — exactly as it would install on a laptop. You then sign in with
**your own** Anthropic account through the CLI's own login. Cinder is the thing that presses
"download," provides a Linux environment for the binary to run in, and draws the UI.

The analogy is a universal remote: it doesn't contain the TV, it talks to one you already own. What
*is* bundled in the APK is open-source infrastructure (proot, an Alpine rootfs, small Termux
libraries), each credited with its license below.

| Layer | What it is |
|---|---|
| UI | Jetpack Compose — transcript, tool cards, diffs, code canvas, terminal, setup |
| Bridge | `claude --print --input-format stream-json --output-format stream-json`, plus a JNI PTY for interactive flows |
| Sandbox | proot + a 4 MB Alpine (musl, aarch64) rootfs, in app-private storage |
| Engine | Anthropic's `claude` binary — **downloaded at runtime, never bundled** |
| Auth | The CLI's own OAuth. No API key is ever entered or stored by this app |

<br>

## Features

- **Runs the real Claude Code CLI** on the phone, in a bundled Linux sandbox
- **Chat transcript** in Claude Code's style — tool cards, Edit diffs, collapsible results
- **Code canvas** — fenced code blocks render as scrollable panels with a copy button
- **Live file-creation status** — `creating file, 84 lines written` becomes `file created, 212 lines`
- **Thinking markers** when the model reasons
- **Sign in** with an Anthropic account via `/login` in chat — the CLI's own OAuth over a real PTY
- **Session history** in a side drawer — resumes with `--resume`, reloading real context
- **Live model picker** — queries `/v1/models` with the stored OAuth credential
- **Built-in terminal**, file upload, tap-to-download for files the agent creates
- **Package management without root** — `apk add`, `npm -g`, `pip install`, plus APK build/decompile tools

<br>

## The engineering: five problems worth writing down

Most of the work here was not UI. It was making a 250 MB Linux binary run on Android at all. Each of
these first presented as something unrelated.

### 1. The CLI is dynamically linked against musl

Claude Code ships one prebuilt native binary per platform — no source to cross-compile, no Android
target. The `linux-arm64-musl` build is the closest fit, but its ELF headers show it is *dynamic*:
its interpreter is `/lib/ld-musl-aarch64.so.1`, a loader at an absolute path Android doesn't have and
won't let you create without root. Hence proot with an Alpine rootfs — and because it's *musl*, that
rootfs is 4 MB, not a 400 MB Debian.

### 2. `targetSdk = 28`, deliberately

Apps targeting API 29+ cannot execute binaries from their own writable data directory, which the
sandbox depends on. Termux solved it the same way. This is why Cinder is sideload-only — the Play
Store requires a current target API.

### 3. aapt silently renames `.gz` assets

The rootfs shipped as `assets/rootfs.tar.gz`, but the build tool gunzips and renames such assets, so
it arrived as `assets/rootfs.tar` — and opening `rootfs.tar.gz` threw FileNotFoundException. Bootstrap
failed on every launch while everything looked fine. Renaming to `rootfs.tgz` plus `noCompress` fixed it.

### 4. Login needs a real terminal

`claude auth login` puts the terminal in raw mode and reads keystrokes directly; given a pipe it
never reads stdin, so a pasted code went nowhere — silently. The fix is a small JNI library
(`libptyexec.so`, about 90 lines of C) calling forkpty. The code must end with a carriage return, not
a newline.

### 5. Android has no SysV IPC — so `apk` needed a workaround

Alpine's minirootfs ships no `/etc/resolv.conf`, so every request died with a DNS timeout until
nameservers were written in from ConnectivityManager. Then apk failed its database write: apk-tools v3
locks the DB with a SysV semaphore, and Android kernels are built without `CONFIG_SYSVIPC`. The
workaround: run apk under a nested proot `-0` (userspace fake root), which extracts the full
dependency tree correctly — the only failure is the final DB bookkeeping, which happens after every
file is on disk, so it's cosmetic and filtered from output.

<br>

## Installing packages inside the sandbox

| Need | Command |
|---|---|
| Alpine packages | `apk add <pkg>` / `apk del <pkg>` |
| Node modules | `npm install -g <pkg>` |
| Python modules | `pip install <pkg>` |
| Android build tools | the **install android build tools** button in setup |
| APK decompile tools | the **install apk tools** button in setup (apktool + jadx) |

Nothing runs as device root. proot's `-0` is userspace fake root — no Magisk, no unlocked bootloader,
no kernel involvement.

**On the SDK/NDK:** Google ships the Android SDK build-tools and NDK as x86-64 Linux binaries; they
cannot execute on an ARM phone. Cinder installs the Termux aarch64 builds of aapt2, d8, apksigner and
ecj, plus Google's architecture-independent android.jar and Gradle — a genuine ecj to d8 to aapt2 to
apksigner pipeline. apktool and jadx run on Alpine's musl JVM.

<br>

## Building

```sh
git clone https://github.com/<owner>/Cinder
cd Cinder
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires the Android SDK with NDK 27 (for the PTY library) and JDK 17. arm64-v8a only.

**First run:** open **setup** (bundled runtime installs itself), then **download claude** (~250 MB
from npm, wifi recommended), then in **chat** type `/login`, then optionally install the build or
decompile toolchains.

<br>

## Licensing and attribution

Cinder bundles these open-source components. Sources are at the links, as their licenses require:

| Component | License | Source |
|---|---|---|
| proot (+ loader) | GPL-2.0 | https://github.com/termux/proot |
| libtalloc | LGPL-3.0 | https://packages.termux.dev |
| libandroid-shmem | MIT | https://github.com/termux/libandroid-shmem |
| Alpine minirootfs (busybox, musl) | GPL-2.0 / MIT | https://alpinelinux.org |

**Claude Code itself is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance) and is downloaded at runtime by the user, from
Anthropic's official distribution. Claude and Claude Code are trademarks of Anthropic PBC; this
project is unofficial and not endorsed by or affiliated with Anthropic.

Application code in this repository is MIT licensed.
