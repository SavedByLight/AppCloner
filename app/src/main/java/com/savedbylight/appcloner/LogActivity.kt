package com.savedbylight.appcloner

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        listView = findViewById(R.id.logListView)
        val clearButton = findViewById<Button>(R.id.clearLogButton)

        refreshList()

        clearButton.setOnClickListener {
            Logger.clear()
            refreshList()
        }
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
