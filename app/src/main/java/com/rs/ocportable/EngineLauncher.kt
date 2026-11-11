package com.rs.ocportable

import android.content.Context
import java.io.File

/**
 * Builds and runs the proot command line that starts `opencode serve` inside the Alpine rootfs.
 *
 * The engine binary is NOT copied into the rootfs. It stays in `nativeLibraryDir`, the only place
 * Android >= 10 lets a third-party app execute, and is bind-mounted into the guest at
 * [GUEST_ENGINE]. Copying it into app data would produce a file the kernel refuses to exec.
 */
object EngineLauncher {

    const val PORT = 4096
    const val HOSTNAME = "127.0.0.1"
    const val GUEST_ENGINE = "/usr/local/bin/opencode"
    const val GUEST_WORKSPACE = "/workspace"

    /**
     * The files the app owns.
     * `rootfs/` is the extracted Alpine tree; `proot-libs/` receives `proot-deps.dat`.
     */
    class Layout(val filesDir: File, val nativeLibraryDir: File) {
        val rootfs = File(filesDir, "rootfs")
        val proot = File(nativeLibraryDir, "libproot.so")
        val engine = File(nativeLibraryDir, "libopencode.so")
        val prootLibs = File(filesDir, "proot-libs")
        val resolvConf = File(rootfs, "etc/resolv.conf")
        val tmp = File(filesDir, "tmp")
        val home = File(filesDir, "home")
        val workspace = File(filesDir, "workspace")
        val guestEngineDir = File(rootfs, "usr/local/bin")
        val guestEngine = File(rootfs, "usr/local/bin/opencode")
        val guestWorkspace = File(rootfs, "workspace")
    }

    /**
     * @param password the stable password from [PasswordStore]. The engine reads it from the
     *   environment; without it `serve` generates a random password on every launch.
     */
    fun command(L: Layout, password: String): List<String> {
        val binds = listOf(
            "/dev" to "/dev",
            "/proc" to "/proc",
            "/sys" to "/sys",
            L.tmp.absolutePath to "/tmp",
            L.workspace.absolutePath to GUEST_WORKSPACE,
            L.home.absolutePath to "/root",
            L.engine.absolutePath to GUEST_ENGINE,
        )
        return buildList {
            add(L.proot.absolutePath)
            add("-0")
            add("--link2symlink")
            add("--kill-on-exit")
            add("-r"); add(L.rootfs.absolutePath)
            add("-w"); add(GUEST_WORKSPACE)
            binds.forEach { (host, guest) -> add("-b"); add("$host:$guest") }
            add("/usr/bin/env")
            add("-i")
            // `env -i` clears the environment. These are assignments, NOT `-u` flags: `-u NAME`
            // unsets, and with `-u NAME=value` the engine would receive none of them.
            add("HOME=/root")
            add("TMPDIR=/tmp")
            add("TERM=xterm-256color")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("OPENCODE_PASSWORD=$password")
            add(GUEST_ENGINE)
            add("serve")
            add("--hostname"); add(HOSTNAME)
            add("--port"); add(PORT.toString())
        }
    }

    fun resolvConf(dnsServers: List<String>): String =
        dnsServers.filter { it.isNotBlank() }.joinToString("\n") { "nameserver $it" } + "\n"

    /**
     * Environment for proot itself, not for the guest. proot is a Bionic binary; its two
     * non-system libraries (libtalloc, libandroid-shmem) are extracted from `proot-deps.dat`
     * into [Layout.prootLibs].
     */
    fun environment(L: Layout): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to L.prootLibs.absolutePath,
        "PROOT_TMP_DIR" to L.tmp.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
    )

    fun start(L: Layout, dnsServers: List<String>, password: String): Process {
        listOf(L.tmp, L.home, L.workspace, L.prootLibs, L.guestEngineDir, L.guestWorkspace).forEach { it.mkdirs() }
        // proot bind targets exist as placeholders; the host paths replace them.
        if (!L.guestEngine.exists()) L.guestEngine.createNewFile()
        // Empty DNS means "leave the image's default"; writing an empty file would remove it.
        if (dnsServers.any { it.isNotBlank() }) {
            L.resolvConf.parentFile?.mkdirs()
            L.resolvConf.writeText(resolvConf(dnsServers))
        }
        val pb = ProcessBuilder(command(L, password))
        pb.environment().putAll(environment(L))
        pb.redirectErrorStream(true)
        return pb.start()
    }

    fun appFiles(context: Context) = Layout(context.filesDir, File(context.applicationInfo.nativeLibraryDir))
}
