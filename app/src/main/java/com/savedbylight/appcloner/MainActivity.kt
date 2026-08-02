package com.savedbylight.appcloner

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Logger.init(applicationContext)

        val recycler = findViewById<RecyclerView>(R.id.appListRecycler)
        progressBar = findViewById(R.id.progressBar)
        recycler.layoutManager = LinearLayoutManager(this)

        // "View Logs" and "Firebase config" now live in the toolbar's
        // overflow menu (see onCreateOptionsMenu/onOptionsItemSelected)
        // instead of as buttons on the main app-list screen.
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            val apps = withContext(Dispatchers.IO) { loadUserApps() }
            progressBar.visibility = View.GONE
            Logger.log("Loaded ${apps.size} installed apps")
            recycler.adapter = AppListAdapter(apps) { app -> onAppSelected(app) }
        }
    }

    // Google apps are signature-tied to Play Services / Play Integrity and
    // gain nothing from cloning — they either crash on launch or get flagged
    // once the package name/signing cert changes. Exclude by prefix rather
    // than relying solely on FLAG_SYSTEM, since some OEMs ship these as
    // regular updatable packages.
    private val excludedPackagePrefixes = listOf(
        "com.google.",       // covers com.google.android.gms, GSF, YouTube, Gmail, Maps, etc.
        "com.android.vending", // Play Store
        "com.android.chrome",  // Chrome (stable) ships under com.android.*, not com.google.*
        "com.chrome.beta",     // Chrome Beta
        "com.chrome.dev",      // Chrome Dev
        "com.chrome.canary"    // Chrome Canary
    )

    private fun isExcludedFromCloning(packageName: String): Boolean =
        excludedPackagePrefixes.any { packageName.startsWith(it) }

    private fun loadUserApps(): List<InstalledApp> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // skip system apps
            .filter { it.packageName != packageName } // don't offer to clone ourselves
            .filter { !isExcludedFromCloning(it.packageName) } // skip Google apps
            .mapNotNull { ai ->
                try {
                    InstalledApp(
                        label = pm.getApplicationLabel(ai).toString(),
                        packageName = ai.packageName,
                        sourceApkPath = ai.sourceDir,
                        icon = pm.getApplicationIcon(ai)
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun onAppSelected(app: InstalledApp) {
        CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            try {
                val resultApk = withContext(Dispatchers.IO) {
                    CloneEngine(this@MainActivity).cloneApp(app)
                }
                progressBar.visibility = View.GONE
                CloneEngine(this@MainActivity).launchInstall(resultApk)
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Logger.log("ERROR cloning ${app.packageName}: ${e.message}\n${e.stackTraceToString()}")
                Toast.makeText(
                    this@MainActivity,
                    "Clone failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_logs -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            R.id.action_firebase_config -> {
                startActivity(Intent(this, FirebaseConfigActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}