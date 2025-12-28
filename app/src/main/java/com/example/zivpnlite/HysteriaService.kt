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
                // 1. Resolve IP Server untuk Exclusion
                val serverIP = InetAddress.getByName(host).hostAddress
                
                prepareConfigs()
                startHysteriaCore(host, pass)
                startLoadBalancer()
                startDnsServer()
                
                // 2. Start Tun2Socks dengan Routing Pintar
                startTun2Socks(serverIP) 
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    // --- Helper Classes & Functions for Routing ---

    data class Cidr(val ip: Long, val prefix: Int) {
        fun getStartIp(): Long = ip
        fun getEndIp(): Long = ip + (1L shl (32 - prefix)) - 1
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        var result = 0L
        for (part in parts) {
            result = result shl 8 or part.toLong()
        }
        return result
    }

    private fun longToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    private fun calculateRoutes(excludeIp: String): List<Cidr> {
        val routes = LinkedList<Cidr>()
        routes.add(Cidr(0, 0)) // Start with 0.0.0.0/0

        val excludeLong = ipToLong(excludeIp)
        
        // Split routes to exclude the specific IP
        // Ini implementasi sederhana: Kita tambahkan route "kecuali" dengan memecah CIDR.
        // Tapi cara paling aman dan standar di Android VpnService:
        // Tambahkan route 0.0.0.0/0, tapi API Android tidak support "exclude route" secara langsung.
        // Jadi kita harus memecah 0.0.0.0/0 menjadi banyak route kecil yang TIDAK menyentuh IP Server.
        // Karena algoritma ini kompleks, kita pakai pendekatan "Best Effort":
        // Exclude range /32 tidak didukung semua Android.
        // Kita akan Exclude seluruh subnet /24 atau /16 jika perlu, atau gunakan trik Java.
        
        // UNTUK SAAT INI: Kita gunakan logika sederhana yang biasa dipakai di OpenVPN for Android.
        // Kita tidak bisa menulis algoritma split CIDR penuh di sini tanpa library IPAddress.
        // TAPI, kita bisa mengandalkan fitur 'addRoute' yang menimpa.
        // Sayangnya VpnService tidak punya 'removeRoute'.
        
        // SOLUSI: Kita add 0.0.0.0/1 dan 128.0.0.0/1 (cover all).
        // Lalu kita biarkan OS menghandle pengecualian? Tidak bisa.
        
        return routes // Placeholder, logika sebenarnya ada di startTun2Socks
    }

    // --- End Helpers ---

    private fun startTun2Socks(serverIP: String) {
        val builder = Builder()
        builder.setSession("ZIVPN Lite")
        builder.addAddress(VPN_ADDRESS, 24)
        builder.addDnsServer(VPN_ADDRESS)
        builder.setMtu(1500)

        // ROUTING LOGIC:
        // 1. Exclude Local LAN (10.0.0.0/8, 192.168.0.0/16, etc) -> Jangan add route ke sini.
        // 2. Exclude Server IP -> Jangan add route yang mencakup IP ini? SUSAH.
        // Cara termudah di Android:
        // Gunakan 'addRoute' untuk 0.0.0.0/0.
        // Tapi ini akan menyebabkan loop.
        
        // TRIK ZIVPN ASLI (Kemungkinan):
        // Mereka menghitung subnet yang TIDAK mengandung Server IP.
        
        // Mari kita coba implementasi manual exclude:
        // Jika IP Server 1.2.3.4.
        // Kita add route: 0.0.0.0/1, 128.0.0.0/1
        // TAPI KITA TIDAK BISA EXCLUDE SPESIFIK IP DENGAN API INI.
        
        // WAIT! Android punya builder.addRoute(String address, int prefix)
        // Kita akan menambahkan 0.0.0.0/0.
        // Lalu berharap OS pintar? Tidak.
        
        // KITA GUNAKAN LOGIKA SEDERHANA:
        // Bypass VPN untuk aplikasi kita sendiri (libuz berjalan di dalam app ini).
        // builder.addDisallowedApplication(packageName)
        // INI SOLUSINYA!
        // Jika kita exclude diri sendiri, maka libuz (yang ada di dalam paket ini) akan akses internet langsung.
        // TAPI, browser (Chrome) ada di paket lain, jadi dia akan masuk VPN.
        // APAKAH TUN2SOCKS JUGA KENA EXCLUDE?
        // Tun2socks hanya menerima paket dari Tun interface. Dia tidak mengirim paket keluar (dia hanya forward ke libload -> libuz).
        // Jadi yang butuh internet adalah LIBUZ.
        
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            // Android 4.x tidak support, tapi ZIVPN Lite minSdk 24.
            e.printStackTrace()
        }

        builder.addRoute("0.0.0.0", 0)

        vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN")
        val tunFd = vpnInterface!!.fd

        val libtun = File(nativeLibDir, "libtun2socks.so").absolutePath

        val cmd = arrayOf(
            libtun,
            "--netif-ipaddr", TUN2SOCKS_ADDRESS,
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", "127.0.0.1:$LOAD_BALANCER_PORT",
            "--tunmtu", "1500",
            "--tunfd", tunFd.toString(),
            "--sock", File(filesDir, "tun.sock").absolutePath
        )

        val logFile = File(filesDir, "process_log.txt")
        val process = ProcessBuilder(*cmd)
            .directory(filesDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        processList.add(process)
    }

    private fun prepareConfigs() {
        val confFile = File(filesDir, "pdnsd.conf")
        if (!confFile.exists()) {
            assets.open("pdnsd.conf").use { input ->
                FileOutputStream(confFile).use { output ->
                    input.copyTo(output)
                }
            }
            val content = confFile.readText()
            val newContent = content.replace("/data/user/0/com.example.zivpnlite/files", filesDir.absolutePath)
            confFile.writeText(newContent)
        }
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

            val cmd = arrayOf(
                libuz,
                "-s", OBFS_KEY,
                "--config", finalJsonArg
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
        val libpdnsd = File(nativeLibDir, "libpdnsd.so").absolutePath
        val confFile = File(filesDir, "pdnsd.conf").absolutePath
        val cmd = arrayOf(libpdnsd, "-v9", "-c", confFile)
        val process = ProcessBuilder(*cmd)
            .directory(filesDir)
            .start()
        processList.add(process)
    }

    private fun stopVpn() {
        processList.forEach { 
            try { it.destroy() } catch (_: Exception) {} 
        }
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
