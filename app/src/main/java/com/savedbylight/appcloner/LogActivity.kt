package com.savedbylight.appcloner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        listView = findViewById(R.id.logListView)
        val clearButton = findViewById<Button>(R.id.clearLogButton)
        val copyButton = findViewById<Button>(R.id.copyLogButton)

        refreshList()

        clearButton.setOnClickListener {
            Logger.clear()
            refreshList()
        }

        copyButton.setOnClickListener {
            copyLogToClipboard()
        }
    }

    private fun copyLogToClipboard() {
        val text = Logger.all().joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Clone Log", text))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun refreshList() {
        val lines = Logger.all().asReversed() // newest first
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            if (lines.isEmpty()) listOf("No log entries yet.") else lines
        )
    }
}
