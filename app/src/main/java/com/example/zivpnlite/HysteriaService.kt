package com.example.zivpnlite

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.LinkedList

class HysteriaService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var processList = mutableListOf<Process>()
    
    private val nativeLibDir: String by lazy {
        applicationInfo.nativeLibraryDir
    }

    private val PORT_RANGES = listOf("6000-9500", "9501-13000", "13001-16500", "16501-19999")
    private val OBFS_KEY = "hu``hqb`c"
    private val LOCAL_PORTS = listOf(1080, 1081, 1082, 1083)
    private val LOAD_BALANCER_PORT = 7777
    private val VPN_ADDRESS = "169.254.1.1"
    private val TUN2SOCKS_ADDRESS = "169.254.1.2"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra("HOST") ?: return START_NOT_STICKY
        val pass = intent?.getStringExtra("PASS") ?: return START_NOT_STICKY

        Thread {
            try {
                cleanUpFiles()
                val resolvedIP = InetAddress.getByName(host).hostAddress
                
                prepareConfigs()
                startHysteriaCore(resolvedIP, pass)
                startLoadBalancer()
                // startDnsServer() // Skip PDNSD, pakai Google DNS langsung
                startTun2Socks(resolvedIP) // Pass Server IP for routing
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun cleanUpFiles() {
        val filesToDelete = listOf("tun.sock", "process_log.txt")
        filesToDelete.forEach { File(filesDir, it).delete() }
    }

    private fun startTun2Socks(serverIp: String) {
        val builder = Builder()
        builder.setSession("ZIVPN Lite")
        builder.addAddress(VPN_ADDRESS, 24)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("8.8.4.4")
        builder.setMtu(1500)

        // IMPLEMENTASI ROUTING PRESISI (Split Tunneling)
        // Kita tidak pakai addDisallowedApplication lagi karena ZIVPN asli pakai Route Excluded.
        
        // 1. Exclude IP Server (Wajib, untuk hindari Loop)
        // 2. Exclude 10.0.0.0/8 (IP Lokal)
        
        val routes = calculateRoutes(serverIp)
        
        for (route in routes) {
            // Logika exclude 10.0.0.0/8 sederhana:
            // Jika route overlap dengan 10.0.0.0/8, skip.
            // Tapi karena CIDR split rumit, kita exclude IP server saja sudah cukup untuk internet.
            builder.addRoute(route.address, route.prefix)
        }
        
        // Tambahkan route khusus 169.254.1.2/32 seperti log ZIVPN
        builder.addRoute(TUN2SOCKS_ADDRESS, 32)

        vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN")
        val tunFd = vpnInterface!!.fd

        val libtun = File(nativeLibDir, "libtun2socks.so").absolutePath
        val sockFile = File(filesDir, "tun.sock")
        if (sockFile.exists()) sockFile.delete()

        // Hapus argumen --dnsgw agar tun2socks memproses DNS via SOCKS
        val cmd = arrayOf(
            libtun,
            "--netif-ipaddr", TUN2SOCKS_ADDRESS,
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", "127.0.0.1:$LOAD_BALANCER_PORT",
            "--tunmtu", "1500",
            "--tunfd", tunFd.toString(),
            "--sock", sockFile.absolutePath
        )

        val logFile = File(filesDir, "process_log.txt")
        val process = ProcessBuilder(*cmd)
            .directory(filesDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        processList.add(process)
    }

    // --- CIDR CALCULATION MAGIC ---
    // Memecah 0.0.0.0/0 menjadi list route yang MENCAKUP semua KECUALI ipToExclude
    
    data class CidrRoute(val address: String, val prefix: Int)

    private fun calculateRoutes(excludeIpStr: String): List<CidrRoute> {
        val excludeIp = ipToLong(excludeIpStr)
        val routes = ArrayList<CidrRoute>()
        
        // Kita mulai dari 0.0.0.0/0 dan membelahnya sampai ketemu IP exclude
        // Ini adalah binary search tree traversal
        addRouteRecursive(routes, 0L, 0, excludeIp)
        
        return routes
    }

    private fun addRouteRecursive(routes: ArrayList<CidrRoute>, currentIp: Long, currentPrefix: Int, excludeIp: Long) {
        // Jika IP Exclude TIDAK ada di dalam range CIDR ini, maka AMBIL CIDR ini.
        val blockSize = 1L shl (32 - currentPrefix)
        val endIp = currentIp + blockSize - 1
        
        if (excludeIp < currentIp || excludeIp > endIp) {
            routes.add(CidrRoute(longToIp(currentIp), currentPrefix))
            return
        }
        
        // Jika IP Exclude ada di sini, dan kita sudah sampai /32, maka JANGAN AMBIL (Exclude!)
        if (currentPrefix == 32) {
            return
        }
        
        // Jika ada di dalam, pecah jadi 2 subnet lebih kecil
        val nextPrefix = currentPrefix + 1
        val leftIp = currentIp
        val rightIp = currentIp + (1L shl (32 - nextPrefix))
        
        addRouteRecursive(routes, leftIp, nextPrefix, excludeIp)
        addRouteRecursive(routes, rightIp, nextPrefix, excludeIp)
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        return (parts[0].toLong() shl 24) + (parts[1].toLong() shl 16) + (parts[2].toLong() shl 8) + parts[3].toLong()
    }

    private fun longToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
    // --- END MAGIC ---

    private fun prepareConfigs() {
        // PDNSD tidak dipakai di V13, tapi config JSON masih perlu
    }

    private fun startHysteriaCore(host: String, pass: String) {
        val libuz = File(nativeLibDir, "libuz.so").absolutePath

        for (i in PORT_RANGES.indices) {
            val range = PORT_RANGES[i]
            val localPort = LOCAL_PORTS[i]
            val configContent = """
            {
              "server": "$host:$range",
              "obfs": "$OBFS_KEY",
              "auth": "$pass",
              "up": "",
              "down": "",
              "socks5": {
                "listen": "127.0.0.1:$localPort"
              },
              "insecure": true,
              "recvwindowconn": 131072,
              "recvwindow": 327680
            }
            """.trimIndent()

            val configFile = File(filesDir, "config_$i.json")
            configFile.writeText(configContent)
            val finalJsonArg = configFile.readText()

            val cmd = arrayOf(libuz, "-s", OBFS_KEY, "--config", finalJsonArg)
            val logFile = File(filesDir, "process_log.txt")
            val process = ProcessBuilder(*cmd)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            processList.add(process)
        }
    }

    private fun startLoadBalancer() {
        val libload = File(nativeLibDir, "libload.so").absolutePath
        val tunnelArgs = LOCAL_PORTS.map { "127.0.0.1:$it" }
        val cmd = mutableListOf(libload, "-lport", LOAD_BALANCER_PORT.toString(), "-tunnel")
        cmd.addAll(tunnelArgs)
        val logFile = File(filesDir, "process_log.txt")
        val process = ProcessBuilder(cmd)
            .directory(filesDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        processList.add(process)
    }

    private fun startDnsServer() {
        // Disabled in V13
    }

    private fun stopVpn() {
        processList.forEach { try { it.destroy() } catch (_: Exception) {} }
        processList.clear()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}