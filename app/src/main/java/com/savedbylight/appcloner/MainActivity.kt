package com.savedbylight.appcloner

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        val recycler = findViewById<RecyclerView>(R.id.appListRecycler)
        progressBar = findViewById(R.id.progressBar)
        recycler.layoutManager = LinearLayoutManager(this)

        CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            val apps = withContext(Dispatchers.IO) { loadUserApps() }
            progressBar.visibility = View.GONE
            recycler.adapter = AppListAdapter(apps) { app -> onAppSelected(app) }
        }
    }

    private fun loadUserApps(): List<InstalledApp> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // skip system apps
            .filter { it.packageName != packageName } // don't offer to clone ourselves
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
                Toast.makeText(
                    this@MainActivity,
                    "Clone failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
