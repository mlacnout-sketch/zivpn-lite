package com.example.zivpnlite

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var inputHost: EditText
    private lateinit var inputPass: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputHost = findViewById(R.id.inputHost)
        inputPass = findViewById(R.id.inputPass)
        btnConnect = findViewById(R.id.btnConnect)
        btnStop = findViewById(R.id.btnStop)

        btnConnect.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 1)
            } else {
                onActivityResult(1, Activity.RESULT_OK, null)
            }
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, HysteriaService::class.java)
            intent.action = "STOP"
            startService(intent)
            updateUI(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            val intent = Intent(this, HysteriaService::class.java)
            intent.putExtra("HOST", inputHost.text.toString())
            intent.putExtra("PASS", inputPass.text.toString())
            startService(intent)
            updateUI(true)
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(isConnected: Boolean) {
        if (isConnected) {
            btnConnect.visibility = Button.GONE
            btnStop.visibility = Button.VISIBLE
            inputHost.isEnabled = false
            inputPass.isEnabled = false
        } else {
            btnConnect.visibility = Button.VISIBLE
            btnStop.visibility = Button.GONE
            inputHost.isEnabled = true
            inputPass.isEnabled = true
        }
    }
}
