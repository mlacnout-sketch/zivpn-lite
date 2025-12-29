package com.example.zivpnlite

import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
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
                // Kill all zombies aggressively (ZIVPN Clean Up Logic)
                Runtime.getRuntime().exec("pkill -f libuz").waitFor()
                Runtime.getRuntime().exec("pkill -f libload").waitFor()
                Runtime.getRuntime().exec("pkill -f libpdnsd").waitFor()
                Runtime.getRuntime().exec("pkill -f libtun2socks").waitFor()

                cleanUpFiles()
                
                // Resolve IP (Logic dari p.java ZIVPN)
                val resolvedIP = InetAddress.getByName(host).hostAddress
                
                // 1. Start Hysteria Core (p.java logic)
                startHysteriaCore(resolvedIP, pass)
                
                // 2. Start Load Balancer (b.java logic)
                startLoadBalancer()
                
                // 3. Start Tun2Socks (f.java logic)
                startTun2Socks(resolvedIP)
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun cleanUpFiles() {
        // Hapus file sisa, tapi jangan hapus tun.sock nanti (akan di-create ulang)
        val filesToDelete = listOf("process_log.txt", "tun.sock")
        filesToDelete.forEach { File(filesDir, it).delete() }
    }

    private fun startTun2Socks(serverIp: String) {
        val builder = Builder()
        builder.setSession("ZIVPN Lite")
        builder.addAddress(VPN_ADDRESS, 24)
        
        // DNS Logic: ZIVPN Asli menggunakan Google DNS di Java layer
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("8.8.4.4")
        builder.setMtu(1500)

        // Routing Logic: Exclude Server IP (r3/k.java logic)
        val routes = calculateRoutes(serverIp)
        for (route in routes) {
            builder.addRoute(route.address, route.prefix)
        }
        // Tambahan khusus: Route IP Tun2Socks ke Interface (sesuai log ZIVPN)
        builder.addRoute(TUN2SOCKS_ADDRESS, 32)

        vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN")
        val tunFd = vpnInterface!!.fd

        val libtun = File(nativeLibDir, "libtun2socks.so").absolutePath
        // Gunakan CACHE DIR untuk socket (lebih aman dari permission issue)
        val sockFile = File(cacheDir, "tun.sock")
        
        // Pastikan bersih dan buat baru
        if (sockFile.exists()) sockFile.delete()
        sockFile.createNewFile()

        // ZIVPN Logic: Construct command string manually
        val sb = StringBuilder()
        sb.append(libtun)
        sb.append(" --netif-ipaddr $TUN2SOCKS_ADDRESS")
        sb.append(" --netif-netmask 255.255.255.0")
        sb.append(" --socks-server-addr 127.0.0.1:$LOAD_BALANCER_PORT")
        sb.append(" --tunmtu 1500")
        sb.append(" --tunfd $tunFd")
        sb.append(" --sock ${sockFile.absolutePath}")
        sb.append(" --loglevel 3")
        // UDPGW Logic: Always Enable for Full VPN
        sb.append(" --udpgw-transparent-dns")
        sb.append(" --udpgw-remote-server-addr 127.0.0.1:$LOAD_BALANCER_PORT")

        val logFile = File(filesDir, "process_log.txt")
        
        // ZIVPN Logic: Use Runtime.exec(String)
        val process = Runtime.getRuntime().exec(sb.toString())
        
        // Redirect Output
        Thread { process.inputStream.copyTo(FileOutputStream(logFile, true)) }.start()
        Thread { process.errorStream.copyTo(FileOutputStream(logFile, true)) }.start()

        processList.add(process)

        // Beri waktu binary untuk inisialisasi socket listener
        try { Thread.sleep(200) } catch (e: InterruptedException) {}

        // ZIVPN Logic: FD Injection
        if (!sendFdToSocket(vpnInterface!!, sockFile)) {
            throw IOException("Failed to send FD to tun2socks socket!")
        }
    }

    // Logic: r3.f.java b()
    private fun sendFdToSocket(vpnInterface: ParcelFileDescriptor, socketFile: File): Boolean {
        for (i in 0..10) { 
            try {
                val localSocket = LocalSocket()
                localSocket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                localSocket.setFileDescriptorsForSend(arrayOf(vpnInterface.fileDescriptor))
                localSocket.outputStream.write(42)
                localSocket.shutdownOutput()
                localSocket.close()
                return true
            } catch (e: Exception) {
                try { Thread.sleep(500) } catch (inter: InterruptedException) {}
            }
        }
        return false
    }

    // Logic: r3.c.java (NetworkSpace) -> Simplified Recursion
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

    // Logic: com.zi.zivpo.p.java
    private fun startHysteriaCore(serverIp: String, pass: String) {
        val libuz = File(nativeLibDir, "libuz.so").absolutePath

        for (i in PORT_RANGES.indices) {
            val range = PORT_RANGES[i]
            val localPort = LOCAL_PORTS[i]
            
            // Config String JSON (bukan File)
            val configContent = """
            {
              "server": "$serverIp:$range",
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

            // ZIVPN: ProcessBuilder(cmdArray)
            // Hysteria tidak rewel argumen, pakai ProcessBuilder aman.
            val cmd = arrayOf(
                libuz,
                "-s", OBFS_KEY,
                "--config", configContent
            )
            
            val logFile = File(filesDir, "process_log.txt")
            val process = ProcessBuilder(*cmd)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            processList.add(process)
        }
    }

    // Logic: r3.b.java
    private fun startLoadBalancer() {
        val libload = File(nativeLibDir, "libload.so").absolutePath
        val logFile = File(filesDir, "process_log.txt")

        // ZIVPN Logic: Runtime.exec(String) for libload too!
        // r3.b.java: sb.append(" -tunnel " + str4 + " " + str3...)
        val sb = StringBuilder()
        sb.append(libload)
        sb.append(" -lport $LOAD_BALANCER_PORT")
        sb.append(" -tunnel")
        for (port in LOCAL_PORTS) {
            sb.append(" 127.0.0.1:$port")
        }

        val process = Runtime.getRuntime().exec(sb.toString())
        
        Thread { process.inputStream.copyTo(FileOutputStream(logFile, true)) }.start()
        Thread { process.errorStream.copyTo(FileOutputStream(logFile, true)) }.start()

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