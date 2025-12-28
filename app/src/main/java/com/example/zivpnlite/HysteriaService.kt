package com.example.zivpnlite

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.ArrayList

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
                startDnsServer() // PDNSD is BACK!
                startTun2Socks(resolvedIP)
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun cleanUpFiles() {
        val filesToDelete = listOf("tun.sock", "process_log.txt", "pdnsd_cache/pdnsd.cache")
        filesToDelete.forEach { File(filesDir, it).delete() }
    }

                prepareConfigs()
                startHysteriaCore(resolvedIP, pass)
                // startLoadBalancer() // BYPASS LOAD BALANCER (V17)
                startDnsServer()
                startTun2Socks(resolvedIP)
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun cleanUpFiles() {
        val filesToDelete = listOf("tun.sock", "process_log.txt", "pdnsd_cache/pdnsd.cache")
        filesToDelete.forEach { File(filesDir, it).delete() }
    }

    private fun prepareConfigs() {
        val cacheDir = File(filesDir, "pdnsd_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        
        // Hapus cache lama agar tidak korup
        File(cacheDir, "pdnsd.cache").delete()

        val confFile = File(filesDir, "pdnsd.conf")
        // Selalu overwrite config untuk memastikan path benar
        assets.open("pdnsd.conf").use { input ->
            FileOutputStream(confFile).use { output ->
                input.copyTo(output)
            }
        }
        val content = confFile.readText()
        // PENTING: Path harus absolute dan tanpa petik ganda yang salah
        val newContent = content.replace("/data/user/0/com.example.zivpnlite/files", cacheDir.absolutePath)
        confFile.writeText(newContent)
    }

    private fun startDnsServer() {
        val libpdnsd = File(nativeLibDir, "libpdnsd.so").absolutePath
        val confFile = File(filesDir, "pdnsd.conf").absolutePath
        
        // Pastikan executable
        File(libpdnsd).setExecutable(true)

        // Argumen pdnsd: -c config_file -d (daemon) atau -v9 (verbose)
        // Kita pakai -v9 agar muncul di log
        val cmd = arrayOf(libpdnsd, "-v9", "-c", confFile)
        
        val logFile = File(filesDir, "process_log.txt")
        val process = ProcessBuilder(*cmd)
            .directory(filesDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        processList.add(process)
    }

    private fun startTun2Socks(serverIp: String) {
        val builder = Builder()
        builder.setSession("ZIVPN Lite")
        builder.addAddress(VPN_ADDRESS, 24)
        // DNS diarahkan ke IP Tun (169.254.1.1)
        // Tun2Socks akan menangkap ini via --dnsgw
        builder.addDnsServer(VPN_ADDRESS) 
        builder.setMtu(1500)

        val routes = calculateRoutes(serverIp)
        for (route in routes) {
            builder.addRoute(route.address, route.prefix)
        }
        builder.addRoute(TUN2SOCKS_ADDRESS, 32)

        vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN")
        val tunFd = vpnInterface!!.fd

        val libtun = File(nativeLibDir, "libtun2socks.so").absolutePath
        val sockFile = File(filesDir, "tun.sock")
        if (sockFile.exists()) sockFile.delete()

        // DIRECT MODE (V17): Point to Hysteria Port (1080)
        
        val cmd = arrayOf(
            libtun,
            "--netif-ipaddr", TUN2SOCKS_ADDRESS,
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", "127.0.0.1:1080", // DIRECT
            "--tunmtu", "1500",
            "--tunfd", tunFd.toString(),
            "--sock", sockFile.absolutePath,
            "--dnsgw", "$VPN_ADDRESS:8091",
            "--udpgw-remote-server-addr", "127.0.0.1:1080" // DIRECT UDPGW
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
    data class CidrRoute(val address: String, val prefix: Int)

    private fun calculateRoutes(excludeIpStr: String): List<CidrRoute> {
        val excludeIp = ipToLong(excludeIpStr)
        val routes = ArrayList<CidrRoute>()
        addRouteRecursive(routes, 0L, 0, excludeIp)
        return routes
    }

    private fun addRouteRecursive(routes: ArrayList<CidrRoute>, currentIp: Long, currentPrefix: Int, excludeIp: Long) {
        val blockSize = 1L shl (32 - currentPrefix)
        val endIp = currentIp + blockSize - 1
        if (excludeIp < currentIp || excludeIp > endIp) {
            routes.add(CidrRoute(longToIp(currentIp), currentPrefix))
            return
        }
        if (currentPrefix == 32) return
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
