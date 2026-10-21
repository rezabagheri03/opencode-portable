package com.rs.ocportable

import android.content.Context
import java.io.File

/**
 * Builds and runs the proot command line that starts `opencode serve` inside the Alpine rootfs.
 *
 * Everything here is deliberately free of Android APIs except [java.io.File] so the command line
 * can be asserted off-device; only [start] touches the process model.
 */
object EngineLauncher {

    const val PORT = 4096
    const val HOSTNAME = "127.0.0.1"

    /**
     * The files the app writes into its private dir on first run.
     * `rootfs/` is the extracted Alpine tree; `bin/proot` ships as `libproot.so`.
     */
    class Layout(val filesDir: File, val nativeLibraryDir: File) {
        val rootfs = File(filesDir, "rootfs")
        val proot = File(nativeLibraryDir, "libproot.so")
        val opencode = File(rootfs, "usr/local/bin/opencode")
        val resolvConf = File(rootfs, "etc/resolv.conf")
        val tmp = File(filesDir, "proot-tmp")
        val home = File(filesDir, "home")
        val workspace = File(filesDir, "workspace")
        val marker = File(rootfs, ".ok")
    }

    /**
     * @param dnsServers servers from the *current* network, written to the rootfs's resolv.conf.
     *   Empty means leave whatever is there; the engine then has no DNS.
     */
    fun command(L: Layout, dnsServers: List<String>, password: String, homeDir: File = L.home): List<String> {
        val binds = listOf(
            "/dev" to "/dev",
            "/proc" to "/proc",
            "/sys" to "/sys",
            L.workspace.absolutePath to "/workspace",
            homeDir.absolutePath to "/root",
        )
        return buildList {
            add(L.proot.absolutePath)
            add("-0")
            add("--link2symlink")
            add("-r"); add(L.rootfs.absolutePath)
            add("-w"); add("/root")
            binds.forEach { (host, guest) -> add("-b"); add("$host:$guest") }
            add("/usr/bin/env")
            add("-i")
            add("-u"); add("HOME=/root")
            add("-u"); add("TERM=xterm-256color")
            add("-u"); add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("-u"); add("OPENCODE_PASSWORD=$password")
            add(L.opencode.absolutePath)
            add("serve")
            add("--hostname"); add(HOSTNAME)
            add("--port"); add(PORT.toString())
        }
    }

    fun resolvConf(dnsServers: List<String>): String =
        dnsServers.filter { it.isNotBlank() }.joinToString("\n") { "nameserver $it" } + "\n"

    /**
     * proot sets its own LD_LIBRARY_PATH for the guest; the host-side value must only carry the
     * two Bionic libraries proot itself links against.
     */
    fun environment(L: Layout, libDir: File): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to libDir.absolutePath,
        "PROOT_TMP_DIR" to L.tmp.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
    )

    fun start(L: Layout, dnsServers: List<String>, password: String): Process {
        listOf(L.tmp, L.home, L.workspace).forEach { it.mkdirs() }
        L.resolvConf.writeText(resolvConf(dnsServers))
        val pb = ProcessBuilder(command(L, dnsServers, password))
        pb.environment().putAll(environment(L, L.proot.parentFile!!.let { File(it.parentFile, "ocportable-libs") }))
        pb.redirectErrorStream(true)
        return pb.start()
    }

    fun appFiles(context: Context) = Layout(context.filesDir, File(context.applicationInfo.nativeLibraryDir))
}
