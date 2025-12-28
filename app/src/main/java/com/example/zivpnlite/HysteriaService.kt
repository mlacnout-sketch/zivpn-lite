package com.example.zivpnlite

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress

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
                // Bersihkan file sisa sebelum mulai
                cleanUpFiles()

                val resolvedIP = InetAddress.getByName(host).hostAddress
                
                prepareConfigs()
                startHysteriaCore(resolvedIP, pass)
                startLoadBalancer()
                startDnsServer()
                startTun2Socks()
            } catch (e: Exception) {
                e.printStackTrace()
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun cleanUpFiles() {
        val filesToDelete = listOf("tun.sock", "process_log.txt")
        filesToDelete.forEach { 
            File(filesDir, it).delete() 
        }
    }

    private fun startTun2Socks() {
        val builder = Builder()
        builder.setSession("ZIVPN Lite")
        builder.addAddress(VPN_ADDRESS, 24)
        // KEMBALI KE DNS INTERNAL (Seperti Aslinya)
        builder.addDnsServer(VPN_ADDRESS) 
        builder.setMtu(1500)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        builder.addRoute("0.0.0.0", 0)

        vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN")
        val tunFd = vpnInterface!!.fd

        val libtun = File(nativeLibDir, "libtun2socks.so").absolutePath
        val sockFile = File(filesDir, "tun.sock")

        // Pastikan sock file tidak ada sebelum start
        if (sockFile.exists()) sockFile.delete()

        val cmd = arrayOf(
            libtun,
            "--netif-ipaddr", TUN2SOCKS_ADDRESS,
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", "127.0.0.1:$LOAD_BALANCER_PORT",
            "--tunmtu", "1500",
            "--tunfd", tunFd.toString(),
            "--sock", sockFile.absolutePath,
            "--dnsgw", "$VPN_ADDRESS:8091" // Tambahkan DNS Gateway eksplisit ke pdnsd
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
        
        val logFile = File(filesDir, "process_log.txt")
        val process = ProcessBuilder(*cmd)
            .directory(filesDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
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
