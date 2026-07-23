package app.cinder

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A process attached to a real pseudo-terminal.
 *
 * Interactive CLIs — Claude Code's login among them — put the terminal in raw mode and read keys
 * directly. Given a pipe they simply never read, which is why typing a code into a piped process
 * did nothing. Everything here goes through forkpty instead.
 */
class PtyProcess(val fd: Int, val pid: Int) {
    private val descriptor: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(fd)
    val input: InputStream = FileInputStream(descriptor.fileDescriptor)
    val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)

    fun waitFor(): Int = Pty.waitFor(pid)

    fun kill() {
        Pty.killPid(pid)
        runCatching { descriptor.close() }
    }

    fun resize(rows: Int, cols: Int) = Pty.resize(fd, rows, cols)

    /** Terminals end a line with a carriage return — "\n" alone is often ignored. */
    fun writeLine(text: String) {
        output.write((text + "\r").toByteArray())
        output.flush()
    }
}

object Pty {
    init { System.loadLibrary("ptyexec") }

    @JvmStatic
    external fun spawn(
        argv: Array<String>, envp: Array<String>, cwd: String?, rows: Int, cols: Int, pidOut: IntArray
    ): Int

    @JvmStatic external fun waitFor(pid: Int): Int
    @JvmStatic external fun killPid(pid: Int)
    @JvmStatic external fun resize(fd: Int, rows: Int, cols: Int)

    fun start(
        argv: Array<String>, envp: Array<String>, cwd: String?, rows: Int = 40, cols: Int = 100
    ): PtyProcess {
        val pidOut = IntArray(1)
        val fd = spawn(argv, envp, cwd, rows, cols, pidOut)
        check(fd >= 0) { "forkpty failed" }
        return PtyProcess(fd, pidOut[0])
    }

    private const val ESC = "\u001B"
    private val csi = Regex(ESC + "\\[[0-9;?]*[ -/]*[@-~]")
    private val osc = Regex(ESC + "\\][^\u0007]*(\u0007|" + ESC + "\\\\)")
    private val misc = Regex(ESC + "[()][0-9A-B]|" + ESC + "[=>78]|[\u0000-\u0008\u000B-\u001F\u007F]")

    /** Terminal output is full of escape sequences; strip them so the log stays readable. */
    fun clean(s: String): String =
        misc.replace(osc.replace(csi.replace(s, ""), ""), "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
}
