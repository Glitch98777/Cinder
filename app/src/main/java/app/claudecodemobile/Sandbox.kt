package app.claudecodemobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * The app's own little Linux sandbox.
 *
 * Everything lives under the app's private files dir, which on Android is a real filesystem the
 * app fully owns — so the agent can create, edit, and compile whatever it likes without asking
 * for any storage permission.
 *
 *   files/
 *     home/            $HOME — config, caches, the claude login credential
 *     workspace/       projects the agent works in (its cwd)
 *     bin/             executables imported here (busybox, proot, claude, aapt2, d8 …)
 *     rootfs/          tiny Alpine tree, only needed to satisfy /lib/ld-musl-aarch64.so.1
 *
 * Executing binaries out of a writable app directory is blocked for apps that target API 29+,
 * so this app targets 28 on purpose — the same approach Termux used. Sideload only; Play would
 * reject it.
 */
class Sandbox(private val ctx: Context) {

    val root: File get() = ctx.filesDir
    val home = File(root, "home")
    val workspace = File(root, "workspace")
    val bin = File(root, "bin")
    val lib = File(root, "lib")
    val libexec = File(root, "libexec")
    val rootfs = File(root, "rootfs")
    val tmp = File(root, "tmp")

    fun ensureLayout() {
        listOf(home, workspace, bin, lib, libexec, rootfs, tmp).forEach { it.mkdirs() }
    }

    /**
     * Termux's proot is dynamically linked and shells out to a separate loader binary, so
     * copying just `proot` out of Termux gives a binary that dies before it does anything:
     * it needs libtalloc.so.2 on the library path and PROOT_LOADER pointing at the loader.
     */
    val prootLoader = File(libexec, "loader")
    val hasLoader get() = prootLoader.exists()
    val hasTalloc get() = lib.listFiles()?.any { it.name.startsWith("libtalloc") } == true
    val hasBash get() = File(rootfs, "bin/bash").exists()

    /** fakeroot is what makes apk usable by the unprivileged agent — no device root required. */
    val hasFakeroot get() = File(rootfs, "usr/bin/fakeroot").exists()

    /** Absolute path of an imported executable, or null when it isn't installed yet. */
    fun tool(name: String): File? = File(bin, name).takeIf { it.exists() && it.canExecute() }

    val hasProot get() = tool("proot") != null
    val hasClaude get() = tool("claude") != null
    val hasMuslLoader get() = File(rootfs, "lib/ld-musl-aarch64.so.1").exists()

    /** Where the claude binary can actually run: needs proot + a musl loader inside the rootfs. */
    val engineReady get() = hasClaude && hasProot && hasMuslLoader

    fun env(extra: Map<String, String> = emptyMap()): Array<String> {
        val path = listOf(bin.absolutePath, "/system/bin", "/system/xbin").joinToString(":")
        val base = mutableMapOf(
            "HOME" to home.absolutePath,
            "PATH" to path,
            "TMPDIR" to tmp.absolutePath,
            "PWD" to workspace.absolutePath,
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            // proot's own requirements — without these it exits before tracing anything
            "PROOT_LOADER" to prootLoader.absolutePath,
            "PROOT_LOADER_32" to File(libexec, "loader32").absolutePath,
            "PROOT_TMP_DIR" to tmp.absolutePath,
            "LD_LIBRARY_PATH" to listOf(lib.absolutePath, bin.absolutePath).joinToString(":"),
            // Claude Code writes its config and OAuth credential under HOME; keeping it inside
            // the sandbox means the credential never leaves app-private storage.
            "CLAUDE_CONFIG_DIR" to File(home, ".claude").absolutePath
        )
        base.putAll(extra)
        return base.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    /**
     * Runs a command inside the sandbox, streaming combined stdout/stderr line by line.
     * Returns the exit code. Android always ships /system/bin/sh, so a shell exists before
     * anything is imported.
     */
    suspend fun shell(
        command: String,
        cwd: File = workspace,
        onLine: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        ensureLayout()
        val process = Runtime.getRuntime().exec(
            arrayOf("/system/bin/sh", "-c", command),
            env(),
            cwd
        )
        process.outputStream.close()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val errReader = BufferedReader(InputStreamReader(process.errorStream))
        val errThread = Thread {
            errReader.forEachLine { onLine(it) }
        }.apply { start() }
        reader.forEachLine { onLine(it) }
        errThread.join()
        process.waitFor()
    }

    /**
     * Wraps a command so it runs inside the musl rootfs under proot: the rootfs becomes /,
     * with the sandbox dirs bound in so the agent still sees its own workspace and home.
     */
    /**
     * proot refuses to sanitize a binding whose target doesn't exist inside the rootfs, so the
     * mount points have to be created there first.
     */
    fun ensureMountPoints() {
        val fixed = listOf(
            "workspace", "root", "tmp", "usr/local/bin", "usr/local/libexec", "usr/local/androidlib",
            // JVMs and anything using shm_open expect this to exist; Alpine's minirootfs has no
            // /dev/shm and Android provides none to bind.
            "dev/shm"
        )
        // Android's linker and bionic libs live in /apex, and /system/bin/linker64 is only a
        // symlink into it — a mount point is needed inside the rootfs for each path we bind.
        (fixed + androidPaths.map { it.trimStart('/') }).forEach { File(rootfs, it).mkdirs() }
    }

    /**
     * Alpine's minirootfs ships no /etc/resolv.conf, so nothing inside the sandbox can resolve a
     * hostname — every request dies as ETIMEDOUT. Write the device's current DNS servers in, and
     * refresh it on each launch since they change with the network.
     */
    fun configureNetwork() {
        val servers = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            cm.getLinkProperties(net)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        }.getOrDefault(emptyList())

        // Public resolvers as a fallback: a VPN or a restricted network can hide the real ones.
        val all = (servers + listOf("1.1.1.1", "8.8.8.8")).distinct().take(4)
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(
            all.joinToString("\n") { "nameserver $it" } + "\noptions timeout:2 attempts:2\n"
        )
        val hosts = File(etc, "hosts")
        if (!hosts.exists() || hosts.readText().isBlank()) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        }
    }

    /**
     * [asRoot] fakes uid 0 inside the rootfs, which apk needs to install packages. The agent must
     * NOT run that way: Claude Code refuses to skip permission checks while it thinks it is root,
     * and exits 1. Package management gets root; the engine runs as the app's own uid.
     */
    /** Host paths worth binding, in preference order. */
    private val androidPaths = listOf(
        "/system", "/apex", "/linkerconfig", "/vendor", "/proc", "/dev", "/sys",
        "/system_ext", "/product"
    )

    /**
     * Reported for diagnostics only. canRead() is a bad gate for binding: /dev and /proc read as
     * unreadable from an app uid yet bind and work perfectly, and dropping them aborts musl
     * (signal 6). Bind them all and let proot warn about the ones it can't canonicalise.
     */
    fun visibleAndroidPaths(): List<Pair<String, Boolean>> =
        androidPaths.map { it to File(it).exists() }

    private fun androidBinds(): Array<String> =
        androidPaths.flatMap { listOf("-b", "$it:$it") }.toTypedArray()

    fun prootCommand(inner: String, asRoot: Boolean = false): Array<String> {
        val proot = tool("proot")?.absolutePath ?: error("proot is not installed")
        ensureMountPoints()
        configureNetwork()
        // Inside the rootfs the host's PATH and LD_LIBRARY_PATH are meaningless — they point at
        // Android's bionic world, and leaving them set makes musl binaries fail to find anything.
        // Claude Code wants a POSIX shell it can drive; busybox ash is a fallback, real bash is
        // what it asks for. SHELL has to name one that exists or the Bash tool refuses to run.
        val shell = if (hasBash) "/bin/bash" else "/bin/sh"
        // /system/bin is bound in, so Android's own tools — getprop, dumpsys, pm, am — are
        // reachable from inside the rootfs alongside the Alpine ones.
        val prelude = buildString {
            append("export PATH=/usr/local/bin:/root/.npm-global/bin:/root/.local/bin:")
            append("/data/data/com.termux/files/usr/bin:")   // Android-native tools (aapt2, d8…)
            append("/usr/bin:/bin:/usr/sbin:/sbin:/system/bin:/system/xbin; ")
            append("export TERMUX_PREFIX=/data/data/com.termux/files/usr; ")
            append("export ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk; ")
            append("export PATH=\$PATH:/root/android-sdk/gradle/bin; ")
            // A JVM under proot trips over three things by default: it maps a shared class-data
            // archive at a fixed address, writes perf data into /tmp/hsperfdata, and sizes its
            // heap from the device's total RAM rather than what this process may actually get.
            append("export JAVA_TOOL_OPTIONS=\"-XX:-UsePerfData -Xshare:off ")
            append("-XX:MaxRAMPercentage=25 -Djava.io.tmpdir=/tmp\"; ")
            append("unset LD_LIBRARY_PATH; export HOME=/root TMPDIR=/tmp SHELL=$shell; ")
            // npm -g and pip both want to write to system dirs, which the unprivileged agent
            // can't touch. Point them at the home directory instead so installs just work.
            append("export NPM_CONFIG_PREFIX=/root/.npm-global; ")
            append("export PIP_USER=1 PIP_BREAK_SYSTEM_PACKAGES=1 PIP_DISABLE_PIP_VERSION_CHECK=1; ")
            append("export PYTHONUSERBASE=/root/.local; ")
        }
        return listOfNotNull(
            proot,
            if (asRoot) "-0" else null,
            "-r", rootfs.absolutePath,
            // Only bind host paths this process can actually stat: SELinux hides some of them
            // from an app uid, and proot warns loudly ("can't sanitize binding") for each one.
            *androidBinds(),
            "-b", "${workspace.absolutePath}:/workspace",
            "-b", "${home.absolutePath}:/root",
            "-b", "${bin.absolutePath}:/usr/local/bin",
            // proot's own pieces have to be reachable from inside too, so `sudo` can re-enter
            "-b", "${libexec.absolutePath}:/usr/local/libexec",
            "-b", "${lib.absolutePath}:/usr/local/androidlib",
            "-b", "${tmp.absolutePath}:/tmp",
            "-w", "/workspace",
            "/bin/sh", "-c", prelude + inner
        ).toTypedArray()
    }

    /** Starts a proot command and hands back the live process so callers can write to stdin. */
    fun spawn(inner: String, asRoot: Boolean = false): Process {
        ensureLayout()
        val pb = ProcessBuilder(*prootCommand(inner, asRoot)).directory(workspace)
        pb.environment().putAll(env().associate { it.substringBefore('=') to it.substringAfter('=') })
        return pb.start()
    }

    /**
     * A `sudo` for the sandbox.
     *
     * The agent runs unprivileged — Claude Code refuses to skip permission checks as root — but
     * apk needs to write to the rootfs. This wrapper re-enters the same filesystem through a
     * nested proot with `-0`, so package installs work without the agent itself being root.
     */
    fun writeHelpers() {
        ensureLayout()
        val sudo = File(bin, "sudo")
        sudo.writeText(
            """
            #!/bin/sh
            # No device root is involved anywhere here. Every file in this rootfs is already owned
            # by the app's own uid, so writes succeed — the only obstacle is that apk refuses to
            # run unless it believes it is uid 0. fakeroot is an LD_PRELOAD shim that says so.
            if command -v fakeroot >/dev/null 2>&1; then
              exec fakeroot -- "${'$'}@"
            fi
            exec "${'$'}@"
            """.trimIndent() + "\n"
        )
        sudo.setExecutable(true, false)

        // `apk add` is the muscle memory for Alpine, and the agent will reach for it before it
        // reaches for sudo. /usr/local/bin comes first on PATH, so this shim shadows /sbin/apk
        // and routes the write-y subcommands through the fake-root helper.
        // A package installer that doesn't need apk.
        //
        // Android kernels are built without CONFIG_SYSVIPC, so semget/shmget return ENOSYS —
        // and apk-tools v3 locks its database with a SysV semaphore. It cannot work here at all.
        // Alpine packages are ordinary gzipped tarballs listed in a plain-text index, so fetching
        // and unpacking them directly sidesteps the whole problem, needs no root, and no IPC.
        val alpine = File(bin, "alpine-install")
        alpine.writeText(
            """
            #!/bin/sh
            # usage: alpine-install <package>...    (resolves dependencies, no apk, no root)
            set -e
            MIRROR=${'$'}{ALPINE_MIRROR:-https://dl-cdn.alpinelinux.org/alpine}
            REL=${'$'}(cut -d. -f1,2 /etc/alpine-release 2>/dev/null || echo edge)
            ARCH=aarch64
            CACHE=/var/cache/alpine-install
            mkdir -p "${'$'}CACHE"

            for repo in main community; do
              idx="${'$'}CACHE/APKINDEX.${'$'}repo"
              if [ ! -s "${'$'}idx" ]; then
                echo "fetching ${'$'}repo index..."
                wget -qO "${'$'}CACHE/i.tar.gz" "${'$'}MIRROR/v${'$'}REL/${'$'}repo/${'$'}ARCH/APKINDEX.tar.gz"
                ( cd "${'$'}CACHE" && tar -xzf i.tar.gz APKINDEX && mv APKINDEX "${'$'}idx" )
                rm -f "${'$'}CACHE/i.tar.gz"
              fi
            done

            resolve() {  # package -> "repo/filename"; understands so:/cmd:/pc: provides
              want="${'$'}1"
              for repo in main community; do
                out=${'$'}(awk -v w="${'$'}want" -F: '
                  /^P:/ {p=${'$'}2} /^V:/ {v=${'$'}2}
                  /^p:/ {split(${'$'}2,a," "); for(i in a){split(a[i],b,"="); if(b[1]==w){print p "-" v; exit}}}
                  /^P:/ {if(p==w) hit=1}
                  /^V:/ {if(hit){print p "-" v; exit}}
                ' "${'$'}CACHE/APKINDEX.${'$'}repo" | head -1)
                if [ -n "${'$'}out" ]; then echo "${'$'}repo ${'$'}out"; return 0; fi
              done
              return 1
            }

            deps_of() {
              awk -v w="${'$'}1" -F: '
                /^P:/ {p=${'$'}2}
                /^D:/ {if(p==w){print ${'$'}2; exit}}
              ' "${'$'}CACHE/APKINDEX.main" "${'$'}CACHE/APKINDEX.community" 2>/dev/null | head -1
            }

            install_one() {
              name="${'$'}1"
              case " ${'$'}DONE " in *" ${'$'}name "*) return 0 ;; esac
              DONE="${'$'}DONE ${'$'}name"
              line=${'$'}(resolve "${'$'}name") || { echo "  ! not found: ${'$'}name"; return 0; }
              repo=${'$'}{line%% *}; file=${'$'}{line#* }
              if [ ! -f "${'$'}CACHE/${'$'}file.apk" ]; then
                wget -qO "${'$'}CACHE/${'$'}file.apk" "${'$'}MIRROR/v${'$'}REL/${'$'}repo/${'$'}ARCH/${'$'}file.apk" || {
                  echo "  ! download failed: ${'$'}file"; return 0; }
              fi
              # Record what this package owns so it can be removed again later — without apk
              # there is no database, so the file list is the database.
              mkdir -p "${'$'}CACHE/installed"
              tar -tzf "${'$'}CACHE/${'$'}file.apk" 2>/dev/null \
                | grep -v '^\.' > "${'$'}CACHE/installed/${'$'}name.files" || true
              tar -xzf "${'$'}CACHE/${'$'}file.apk" -C / 2>/dev/null || true
              echo "  + ${'$'}file"
            }

            DONE=""
            for pkg in "${'$'}@"; do
              echo "==> ${'$'}pkg"
              for d in ${'$'}(deps_of "${'$'}pkg"); do
                case "${'$'}d" in
                  so:*|cmd:*|pc:*) install_one "${'$'}d" ;;
                  !*) ;;
                  *) install_one "${'$'}{d%%[<>=]*}" ;;
                esac
              done
              install_one "${'$'}pkg"
            done
            rm -f /.PKGINFO /.SIGN.* 2>/dev/null || true
            echo "done"
            """.trimIndent() + "\n"
        )
        alpine.setExecutable(true, false)

        // The other half: removal. Files recorded at install time are the package database.
        val alpineDel = File(bin, "alpine-uninstall")
        alpineDel.writeText(
            """
            #!/bin/sh
            # usage: alpine-uninstall <package>...   (removes what alpine-install put down)
            CACHE=/var/cache/alpine-install
            DB="${'$'}CACHE/installed"

            [ ${'$'}# -gt 0 ] || { echo "usage: alpine-uninstall <package>..."; exit 2; }

            for pkg in "${'$'}@"; do
              list="${'$'}DB/${'$'}pkg.files"
              if [ ! -f "${'$'}list" ]; then
                echo "not installed (or installed before tracking): ${'$'}pkg"
                continue
              fi
              echo "==> removing ${'$'}pkg"
              removed=0
              # files first, directories after, so directories are empty by the time we try
              for f in ${'$'}(grep -v '/${'$'}' "${'$'}list"); do
                # keep anything another installed package also owns
                if grep -lFx "${'$'}f" "${'$'}DB"/*.files 2>/dev/null | grep -qv "^${'$'}list${'$'}"; then
                  continue
                fi
                rm -f "/${'$'}f" 2>/dev/null && removed=${'$'}((removed+1))
              done
              for d in ${'$'}(grep '/${'$'}' "${'$'}list" | sort -r); do
                rmdir "/${'$'}d" 2>/dev/null || true
              done
              rm -f "${'$'}list"
              echo "    ${'$'}removed files removed"
            done
            """.trimIndent() + "\n"
        )
        alpineDel.setExecutable(true, false)

        val apkShim = File(bin, "apk")
        apkShim.writeText(
            """
            #!/bin/sh
            # Real apk cannot run on Android: apk-tools v3 locks its database with a SysV
            # semaphore, and Android kernels are built without CONFIG_SYSVIPC. Route installs to
            # the direct downloader instead, which needs neither IPC nor root.
            case "${'$'}1" in
              add)
                shift
                exec /usr/local/bin/alpine-install "${'$'}@" ;;
              del|remove)
                shift
                exec /usr/local/bin/alpine-uninstall "${'$'}@" ;;
              info|list)
                ls /var/cache/alpine-install/installed 2>/dev/null | sed 's/\.files${'$'}//'
                exit 0 ;;
              update)
                rm -f /var/cache/alpine-install/APKINDEX.* ; echo "index cache cleared"; exit 0 ;;
              *)
                exec /sbin/apk "${'$'}@" ;;
            esac
            """.trimIndent() + "\n"
        )
        apkShim.setExecutable(true, false)

        // Alpine's repo has no Android build tools, and Google ships aapt2/d8/NDK as x86-64
        // Linux binaries that cannot run here at all. Termux publishes aarch64 builds of the same
        // tools for Android's own libc — and those DO run inside this sandbox now that /system
        // and /apex are bound. They expect to live at Termux's own prefix, so install them there.
        val termux = File(bin, "termux-install")
        termux.writeText(
            """
            #!/bin/sh
            # Install Android/aarch64 packages from the Termux repo into this sandbox.
            #   termux-install aapt2 d8 apksigner openjdk-17
            set -e
            REPO=https://packages.termux.dev/apt/termux-main
            IDX=/tmp/termux-packages-index
            PREFIX=/data/data/com.termux/files/usr

            [ ${'$'}# -gt 0 ] || { echo "usage: termux-install <package>..."; exit 2; }
            command -v dpkg  >/dev/null 2>&1 || apk add dpkg  >/dev/null
            command -v wget  >/dev/null 2>&1 || apk add wget  >/dev/null

            if [ ! -s "${'$'}IDX" ]; then
              echo "fetching package index..."
              wget -qO "${'$'}IDX" "${'$'}REPO/dists/stable/main/binary-aarch64/Packages"
            fi

            mkdir -p "${'$'}PREFIX/bin" "${'$'}PREFIX/lib"
            for pkg in "${'$'}@"; do
              file=${'$'}(awk -v p="${'$'}pkg" '${'$'}1=="Package:"{c=${'$'}2} ${'$'}1=="Filename:"&&c==p{print ${'$'}2; exit}' "${'$'}IDX")
              if [ -z "${'$'}file" ]; then echo "no such package: ${'$'}pkg"; exit 1; fi
              deps=${'$'}(awk -v p="${'$'}pkg" '${'$'}1=="Package:"{c=${'$'}2} /^Depends:/&&c==p{sub(/^Depends: /,""); gsub(/\([^)]*\)/,""); gsub(/,/," "); print; exit}' "${'$'}IDX")
              echo "==> ${'$'}pkg ${'$'}{deps:+(deps: ${'$'}deps)}"
              wget -qO /tmp/termux-pkg.deb "${'$'}REPO/${'$'}file"
              dpkg -x /tmp/termux-pkg.deb /
              rm -f /tmp/termux-pkg.deb
              for d in ${'$'}deps; do
                case " ${'$'}INSTALLED " in *" ${'$'}d "*) continue ;; esac
                dfile=${'$'}(awk -v p="${'$'}d" '${'$'}1=="Package:"{c=${'$'}2} ${'$'}1=="Filename:"&&c==p{print ${'$'}2; exit}' "${'$'}IDX")
                [ -n "${'$'}dfile" ] || continue
                wget -qO /tmp/termux-dep.deb "${'$'}REPO/${'$'}dfile" && dpkg -x /tmp/termux-dep.deb / && echo "    + ${'$'}d"
                rm -f /tmp/termux-dep.deb
                INSTALLED="${'$'}INSTALLED ${'$'}d"
              done
            done
            echo "installed under ${'$'}PREFIX (already on PATH)"
            """.trimIndent() + "\n"
        )
        termux.setExecutable(true, false)

        // The real SDK, as far as it can exist on ARM: android.jar is plain Java and comes
        // straight from Google, while the executables (aapt2/d8/apksigner) come from Termux
        // because Google only ships those for x86-64.
        val sdk = File(bin, "android-sdk-install")
        sdk.writeText(
            """
            #!/bin/sh
            # Assemble a working Android SDK for aarch64.
            set -e
            API=${'$'}{1:-35}
            SDK=${'$'}{ANDROID_HOME:-/root/android-sdk}
            PLATFORM_URL=https://dl.google.com/android/repository/platform-35_r02.zip

            command -v unzip >/dev/null 2>&1 || apk add unzip >/dev/null
            command -v wget  >/dev/null 2>&1 || apk add wget  >/dev/null

            echo "==> build tools (Termux aarch64 builds)"
            termux-install aapt2 d8 apksigner ecj openjdk-17

            echo "==> android.jar for API ${'$'}API (from Google)"
            mkdir -p "${'$'}SDK/platforms"
            wget -qO /tmp/platform.zip "${'$'}PLATFORM_URL"
            rm -rf /tmp/platform-x && mkdir -p /tmp/platform-x
            unzip -q /tmp/platform.zip -d /tmp/platform-x
            src=${'$'}(find /tmp/platform-x -name android.jar | head -1)
            [ -n "${'$'}src" ] || { echo "android.jar not found in archive"; exit 1; }
            mkdir -p "${'$'}SDK/platforms/android-${'$'}API"
            cp "${'$'}src" "${'$'}SDK/platforms/android-${'$'}API/android.jar"
            rm -rf /tmp/platform.zip /tmp/platform-x

            echo "==> gradle"
            if [ ! -x "${'$'}SDK/gradle/bin/gradle" ]; then
              GRADLE_VER=${'$'}{GRADLE_VER:-8.14.3}
              wget -qO /tmp/gradle.zip \
                "https://services.gradle.org/distributions/gradle-${'$'}GRADLE_VER-bin.zip" || true
              if [ -s /tmp/gradle.zip ]; then
                mkdir -p "${'$'}SDK"
                unzip -q /tmp/gradle.zip -d "${'$'}SDK"
                rm -rf "${'$'}SDK/gradle"
                mv "${'$'}SDK/gradle-${'$'}GRADLE_VER" "${'$'}SDK/gradle"
                rm -f /tmp/gradle.zip
                echo "  gradle ${'$'}GRADLE_VER"
              else
                echo "  ! gradle download failed (skipping)"
              fi
            else
              echo "  already installed"
            fi

            echo
            echo "ANDROID_HOME=${'$'}SDK"
            echo "  gradle:    ${'$'}(command -v gradle || echo "${'$'}SDK/gradle/bin/gradle")"
            echo "  platforms/android-${'$'}API/android.jar  ${'$'}(du -h "${'$'}SDK/platforms/android-${'$'}API/android.jar" | cut -f1)"
            echo "  aapt2:     ${'$'}(command -v aapt2     || echo MISSING)"
            echo "  d8:        ${'$'}(command -v d8        || echo MISSING)"
            echo "  apksigner: ${'$'}(command -v apksigner || echo MISSING)"
            echo "  ecj:       ${'$'}(command -v ecj       || echo MISSING)"
            echo
            echo "Build: ecj -> d8 -> aapt2 link -> apksigner. Google's own build-tools and the NDK"
            echo "are x86-64 binaries and cannot run on this device; these are the ARM equivalents."
            """.trimIndent() + "\n"
        )
        sdk.setExecutable(true, false)
    }

    /** Copies an imported file into bin/ and makes it executable. */
    fun install(name: String, bytes: ByteArray): File {
        ensureLayout()
        val target = File(bin, name)
        target.writeBytes(bytes)
        target.setExecutable(true, false)
        target.setReadable(true, false)
        return target
    }

    fun diskUsage(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** A fresh project skeleton so "create files and compile" has somewhere to start. */
    fun seedWorkspace() {
        ensureLayout()
        val readme = File(workspace, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                """
                # Sandbox workspace

                This directory is the agent's working directory. It lives in this app's private
                storage — no storage permission is involved, and uninstalling the app removes it.

                Everything here is writable and executable: create files, run shell commands,
                build projects.
                """.trimIndent()
            )
        }
    }
}
