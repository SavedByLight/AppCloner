package com.savedbylight.appcloner

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Formerly a pair of buttons on MainActivity's main screen; now its own
 * screen, reached via the overflow menu ("Firebase config") so the app-list
 * screen stays focused on picking an app to clone.
 */
class FirebaseConfigActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firebase_config)
        title = "Firebase config"

        statusText = findViewById(R.id.currentStatusText)
        updateStatusText()

        findViewById<Button>(R.id.selectFirebaseJsonButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, REQUEST_CODE_FIREBASE_JSON)
        }

        findViewById<Button>(R.id.clearFirebaseJsonButton).setOnClickListener {
            FirebaseJsonProvider.clear()
            Toast.makeText(this, "Firebase config cleared, will use original", Toast.LENGTH_SHORT).show()
            updateStatusText()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_FIREBASE_JSON && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val json = stream.readBytes()
                        FirebaseJsonProvider.setJson(json)
                        Toast.makeText(this, "Firebase JSON loaded (${json.size} bytes)", Toast.LENGTH_SHORT).show()
                        updateStatusText()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to read JSON: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateStatusText() {
        val json = FirebaseJsonProvider.getJson()
        statusText.text = if (json != null) {
            "Current: replacement config set (${json.size} bytes)"
        } else {
            "Current: using each app's original Firebase config"
        }
    }

    companion object {
        private const val REQUEST_CODE_FIREBASE_JSON = 1001
    }
}
