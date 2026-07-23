# Claude Code Mobile

An Android client that runs the **real** Claude Code CLI on a phone — not a reimplementation, not a
remote shell, not a web wrapper. The official `claude` binary executes inside a self-contained Linux
sandbox bundled with the app, and a Jetpack Compose UI renders its output as a proper transcript.

> Unofficial and unaffiliated with Anthropic. Sideload only.

<br>

## What it actually is

| Layer | What it is |
|---|---|
| UI | Jetpack Compose — transcript, tool cards, diffs, terminal, setup |
| Bridge | `claude --print --input-format stream-json --output-format stream-json` over a pipe, plus a JNI PTY for interactive flows |
| Sandbox | proot + a 4 MB Alpine (musl, aarch64) rootfs, in app-private storage |
| Engine | Anthropic's `claude` binary — **downloaded at runtime, never bundled** |
| Auth | The CLI's own OAuth. No API key is ever entered or stored by this app |

The app ships proot, its loader, `libtalloc`, `libandroid-shmem`, and the Alpine minirootfs. It does
**not** ship Claude Code: that binary is Anthropic's, and the setup screen fetches it from the npm
registry on demand.

<br>

## Features

- **Chat transcript** in Claude Code's visual language — `⏺` markers, per-tool cards, `Edit` diffs,
  collapsible results, `⎿` result gutters
- **Live file-creation status** — `creating file · 84 lines written` → `file created · 212 lines`,
  by watching the file on disk as it grows
- **Thinking markers** when the model reasons
- **Sign in with an Anthropic account** via `/login` in chat — the CLI's own OAuth flow, driven over
  a real PTY, with the browser handoff and code paste wired up
- **Session history** in a side drawer — resumes with `--resume`, so the CLI reloads real context
  rather than just redrawing text
- **Live model picker** — queries `/v1/models` with the stored OAuth credential and lists exactly
  what the account can use
- **Built-in terminal** into the same sandbox
- **File upload** into the workspace; files the agent creates become tap-to-download chips
- **Package management without root** — `apk add`, `npm -g`, `pip install`, plus Android build tools

<br>

## The five problems worth writing down

Most of the work here was not UI. It was making a 250 MB glibc-era Linux binary run on Android at
all. Each of these presented as something unrelated.

### 1. The CLI is dynamically linked against musl

Claude Code ships one prebuilt native binary per platform — there is no source to cross-compile, and
no Android target. The `linux-arm64-musl` build is the closest fit, but reading its ELF headers
shows it is *dynamic*:

```
ELF 64-bit  type=ET_EXEC  machine=AArch64
segments: PT_PHDR PT_INTERP PT_LOAD PT_LOAD PT_LOAD PT_TLS PT_DYNAMIC
DYNAMIC — interpreter: /lib/ld-musl-aarch64.so.1
```

It needs a loader at an absolute path Android doesn't have and won't let you create without root.
Hence proot with an Alpine rootfs — and because it is *musl*, that rootfs is 4 MB rather than a
400 MB Debian.

### 2. `targetSdk = 28`, deliberately

Apps targeting API 29+ cannot execute binaries from their own writable data directory. The whole
sandbox depends on doing exactly that. Termux solved it the same way. This is why the app is
sideload-only — the Play Store requires a current target API.

### 3. aapt silently renames `.gz` assets

The rootfs shipped as `assets/rootfs.tar.gz`, but the build tool gunzips and renames such assets, so
it arrived as `assets/rootfs.tar` — and `assets.open("rootfs.tar.gz")` threw `FileNotFoundException`.
The bootstrap failed on every launch while everything *looked* fine. Renaming to `rootfs.tgz` plus
`noCompress` fixed it. (Compressed assets over ~1 MB can't be streamed either, so `noCompress` was
needed regardless.)

### 4. Login needs a real terminal

`claude auth login` puts the terminal in raw mode and reads keystrokes directly. Given a pipe it
never reads stdin at all, so a pasted code went nowhere — silently. The fix is a small JNI library
(`libptyexec.so`, ~90 lines of C) calling `forkpty`; bionic has had it since API 23. The code must
also be terminated with `\r`, not `\n`.

### 5. Android has no SysV IPC — so `apk` cannot work

Alpine's minirootfs ships no `/etc/resolv.conf`, so every request died as `getaddrinfo ETIMEDOUT`
until DNS servers were written in from `ConnectivityManager`. Then package installs failed anyway:
apk-tools v3 locks its database with a **SysV semaphore**, and Android kernels are built without
`CONFIG_SYSVIPC`. `semget` returns ENOSYS. No permission or root workaround exists.

So apk was replaced. Alpine packages are ordinary gzipped tarballs listed in a plain-text index, so
`alpine-install` fetches `APKINDEX`, resolves dependencies (including `so:`/`cmd:` provides),
downloads the `.apk` files and unpacks them — recording each package's file list so `apk del` can
undo it. `apk` is a shim over that, and needs neither IPC nor root.

<br>

## Installing packages inside the sandbox

| Need | Command |
|---|---|
| Alpine packages | `apk add <pkg>` / `apk del <pkg>` / `apk info` |
| Node modules | `npm install -g <pkg>` → `/root/.npm-global` |
| Python modules | `pip install <pkg>` → `/root/.local` |
| Android/aarch64 binaries | `termux-install aapt2 d8 apksigner ecj openjdk-17` |
| Full Android toolchain | the **install android build tools** button in setup |

Nothing runs as root. proot's `-0` is userspace fake root and `fakeroot` is an `LD_PRELOAD` shim —
no Magisk, no unlocked bootloader, no kernel involvement.

### About the "SDK and NDK"

Google ships the Android SDK build-tools and the NDK as **x86-64 Linux binaries**. They cannot
execute on an ARM phone at any price. What the build-tools button installs instead:

- `aapt2`, `d8`, `apksigner`, `ecj`, `openjdk-17` — Termux's **aarch64** builds of the same tools
- `android.jar` for API 35 — from Google directly; it is plain Java and architecture-independent
- Gradle
- `clang`, `make`, `binutils` — the ARM-native stand-in for the NDK

That is a genuine `ecj → d8 → aapt2 → apksigner` pipeline running on the phone.

<br>

## Building

```sh
git clone https://github.com/<owner>/ClaudeCodeMobile
cd ClaudeCodeMobile
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires the Android SDK with NDK 27 (for the PTY library) and JDK 17. arm64-v8a only.

### First run

1. Open **setup** — the bundled runtime (proot + rootfs) installs itself
2. **download claude** — about 250 MB from npm, wifi recommended
3. In **chat**, type `/login` and sign in to your Anthropic account
4. Optionally **install android build tools** for on-device APK builds

<br>

## Licensing and attribution

This app bundles third-party components:

| Component | License | Source |
|---|---|---|
| proot (+ loader) | GPL-2.0 | https://github.com/termux/proot |
| libtalloc | LGPL-3.0 | https://packages.termux.dev |
| libandroid-shmem | MIT | https://github.com/termux/libandroid-shmem |
| Alpine minirootfs (busybox, musl) | GPL-2.0 / MIT | https://alpinelinux.org |

Binaries come from Termux's aarch64 repository; upstream sources are at the links above, as required
by the GPL.

**Claude Code itself is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance) and is downloaded at runtime by the user.
Claude and Claude Code are trademarks of Anthropic PBC; this project is unofficial and not endorsed
by or affiliated with Anthropic.

Application code in this repository is MIT licensed.
