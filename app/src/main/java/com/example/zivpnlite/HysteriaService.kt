package com.example.zivpnlite

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class HysteriaService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var processList = mutableListOf<Process>()
    private val binaries = listOf("libuz.so", "libload.so", "libtun2socks.so", "libpdnsd.so", "pdnsd.conf")

    private val PORT_RANGES = listOf(
        "6000-9500",
        "9501-13000",
        "13001-16500",
        "16501-19999"
    )
    private val OBFS_KEY = "hu``hqb`c"
    private val LOCAL_PORTS = listOf(1080, 1081, 1082, 1083)
    private val LOAD_BALANCER_PORT = 7777
    
    // Konfigurasi IP VPN (Meniru ZIVPN)
    private val VPN_ADDRESS = "169.254.1.1"
    private val TUN2SOCKS_ADDRESS = "169.254.1.2"
    private val DNS_PORT = 8091

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
                prepareBinaries()
                startHysteriaCore(host, pass)
                startLoadBalancer()
                startDnsServer() // NEW: Start DNS
                startTun2Socks()
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun prepareBinaries() {
        val binDir = File(filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()

        binaries.forEach { fileName ->
            val file = File(binDir, fileName)
            assets.open(fileName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            if (fileName.endsWith(".so")) {
                file.setExecutable(true)
            }
        }
        
        // Fix path cache_dir di pdnsd.conf agar sesuai lokasi HP user
        val confFile = File(binDir, "pdnsd.conf")
        val content = confFile.readText()
        val newContent = content.replace("/data/user/0/com.example.zivpnlite/files", filesDir.absolutePath)
        confFile.writeText(newContent)
    }

    private fun startHysteriaCore(host: String, pass: String) {
        val binDir = File(filesDir, "bin")
        val libuz = File(binDir, "libuz.so").absolutePath

        for (i in PORT_RANGES.indices) {
            val range = PORT_RANGES[i]
            val localPort = LOCAL_PORTS[i]
            
            val configContent = """
            {
              "server": "$host:$range",
              "obfs": "$OBFS_KEY",
              "auth": "$pass",
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

            val cmd = arrayOf(
                libuz,
                "-s", OBFS_KEY,
                "--config", configFile.absolutePath
            )
            
            val process = ProcessBuilder(*cmd)
                .directory(filesDir)
                .start()
            processList.add(process)
        }
    }

    private fun startLoadBalancer() {
        val binDir = File(filesDir, "bin")
        val libload = File(binDir, "libload.so").absolutePath

        val tunnelArgs = LOCAL_PORTS.map { "127.0.0.1:$it" }
        val cmd = mutableListOf(libload, "-lport", LOAD_BALANCER_PORT.toString(), "-tunnel")
        cmd.addAll(tunnelArgs)

        val process = ProcessBuilder(cmd)
            .directory(filesDir)
            .start()
        processList.add(process)
    }

    private fun startDnsServer() {
        val binDir = File(filesDir, "bin")
        val libpdnsd = File(binDir, "libpdnsd.so").absolutePath
        val confFile = File(binDir, "pdnsd.conf").absolutePath

        // Command: ./libpdnsd.so -v9 -c pdnsd.conf
        val cmd = arrayOf(libpdnsd, "-v9", "-c", confFile)
        
        val process = ProcessBuilder(*cmd)
            .directory(filesDir)
            .start()
        processList.add(process)
    }

    private fun startTun2Socks() {
        val builder = Builder()
        builder.setSession("ZIVPN Lite")
        builder.addAddress(VPN_ADDRESS, 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer(VPN_ADDRESS) // Set DNS ke diri sendiri (PDNSD)
        builder.setMtu(1500)
        
        vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN")
        val tunFd = vpnInterface!!.fd

        val binDir = File(filesDir, "bin")
        val libtun = File(binDir, "libtun2socks.so").absolutePath

        // Command update: IP 169.254.1.2 dan DNS handling
        val cmd = arrayOf(
            libtun,
            "--netif-ipaddr", TUN2SOCKS_ADDRESS,
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", "127.0.0.1:$LOAD_BALANCER_PORT",
            "--tunmtu", "1500",
            "--tun-fd", tunFd.toString()
        )

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