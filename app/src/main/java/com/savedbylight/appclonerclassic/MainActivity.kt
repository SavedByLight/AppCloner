package com.savedbylight.appclonerclassic

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var appListRecycler: RecyclerView
    private lateinit var cloneListRecycler: RecyclerView
    private lateinit var searchEditText: EditText
    private lateinit var tabLayout: TabLayout

    private var allApps: List<InstalledApp> = emptyList()
    private var allClones: List<CloneInfo> = emptyList()
    private var filteredApps: List<InstalledApp> = emptyList()
    private var filteredClones: List<CloneInfo> = emptyList()

    private lateinit var appAdapter: AppListAdapter
    private lateinit var cloneAdapter: CloneListAdapter

    private var currentTab = 0 // 0 = Clone New, 1 = My Clones

    // Packages we never offer to clone (signature / Play Integrity tied)
    private val excludedPackagePrefixes = listOf(
        "com.google.",
        "com.android.vending",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Logger.init(applicationContext)

        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        emptyStateText = findViewById(R.id.emptyStateText)
        appListRecycler = findViewById(R.id.appListRecycler)
        cloneListRecycler = findViewById(R.id.cloneListRecycler)
        searchEditText = findViewById(R.id.searchEditText)
        tabLayout = findViewById(R.id.tabLayout)

        appListRecycler.layoutManager = LinearLayoutManager(this)
        cloneListRecycler.layoutManager = LinearLayoutManager(this)

        appAdapter = AppListAdapter(emptyList()) { app -> onAppSelected(app) }
        cloneAdapter = CloneListAdapter(
            emptyList(),
            onOpen = { clone -> openClone(clone) },
            onUninstall = { clone -> confirmUninstall(clone) }
        )
        appListRecycler.adapter = appAdapter
        cloneListRecycler.adapter = cloneAdapter

        tabLayout.addTab(tabLayout.newTab().setText("Clone New"))
        tabLayout.addTab(tabLayout.newTab().setText("My Clones"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                searchEditText.setText("")
                updateVisibleList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        findViewById<Button>(R.id.viewLogsButton).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        // Refresh clone list when returning (e.g. after install / uninstall)
        if (::cloneAdapter.isInitialized) {
            refreshClonesOnly()
        }
    }

    private fun loadData() {
        CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Loading apps…"
            val (apps, clones) = withContext(Dispatchers.IO) {
                Pair(loadUserApps(), loadExistingClones())
            }
            allApps = apps
            allClones = clones
            filteredApps = apps
            filteredClones = clones
            progressBar.visibility = View.GONE
            statusText.text = "${apps.size} apps · ${clones.size} clones"
            Logger.log("Loaded ${apps.size} user apps and ${clones.size} existing clones")
            updateVisibleList()
        }
    }

    private fun refreshClonesOnly() {
        CoroutineScope(Dispatchers.Main).launch {
            val clones = withContext(Dispatchers.IO) { loadExistingClones() }
            allClones = clones
            applyFilter(searchEditText.text?.toString().orEmpty())
            // Also refresh clone counts on the app list
            val apps = withContext(Dispatchers.IO) { loadUserApps() }
            allApps = apps
            if (currentTab == 0) {
                applyFilter(searchEditText.text?.toString().orEmpty())
            }
            statusText.text = "${allApps.size} apps · ${allClones.size} clones"
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        if (currentTab == 0) {
            filteredApps = if (q.isEmpty()) allApps
            else allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        } else {
            filteredClones = if (q.isEmpty()) allClones
            else allClones.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        updateVisibleList()
    }

    private fun updateVisibleList() {
        if (currentTab == 0) {
            appListRecycler.visibility = View.VISIBLE
            cloneListRecycler.visibility = View.GONE
            appAdapter.updateData(filteredApps)
            if (filteredApps.isEmpty()) {
                emptyStateText.visibility = View.VISIBLE
                emptyStateText.text = if (searchEditText.text.isNullOrBlank()) {
                    "No clonable apps found.\n\nOnly non-system user apps are shown."
                } else {
                    "No apps match your search."
                }
            } else {
                emptyStateText.visibility = View.GONE
            }
        } else {
            appListRecycler.visibility = View.GONE
            cloneListRecycler.visibility = View.VISIBLE
            cloneAdapter.updateData(filteredClones)
            if (filteredClones.isEmpty()) {
                emptyStateText.visibility = View.VISIBLE
                emptyStateText.text = if (searchEditText.text.isNullOrBlank()) {
                    "No clones yet.\n\nGo to “Clone New”, pick an app, and create your first dual instance."
                } else {
                    "No clones match your search."
                }
            } else {
                emptyStateText.visibility = View.GONE
            }
        }
    }

    private fun isExcludedFromCloning(packageName: String): Boolean =
        excludedPackagePrefixes.any { packageName.startsWith(it) }

    private fun loadUserApps(): List<InstalledApp> {
        val pm = packageManager
        val cloneCounts = countClonesByBase()
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { it.packageName != packageName }
            .filter { !isExcludedFromCloning(it.packageName) }
            .filter { !it.packageName.contains(".clone") } // don't list clones in the "to clone" list
            .mapNotNull { ai ->
                try {
                    InstalledApp(
                        label = pm.getApplicationLabel(ai).toString(),
                        packageName = ai.packageName,
                        sourceApkPath = ai.sourceDir,
                        icon = pm.getApplicationIcon(ai),
                        existingCloneCount = cloneCounts[ai.packageName] ?: 0
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun countClonesByBase(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val pm = packageManager
        pm.getInstalledApplications(0).forEach { ai ->
            val pkg = ai.packageName
            val match = Regex("""^(.+)\.clone(\d+)$""").find(pkg)
            if (match != null) {
                val base = match.groupValues[1]
                counts[base] = (counts[base] ?: 0) + 1
            }
        }
        return counts
    }

    private fun loadExistingClones(): List<CloneInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .mapNotNull { ai ->
                val pkg = ai.packageName
                val match = Regex("""^(.+)\.clone(\d+)$""").find(pkg) ?: return@mapNotNull null
                val base = match.groupValues[1]
                val num = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                try {
                    // Prefer the base app's label if still installed
                    val baseLabel = try {
                        val baseInfo = pm.getApplicationInfo(base, 0)
                        pm.getApplicationLabel(baseInfo).toString()
                    } catch (_: Exception) {
                        pm.getApplicationLabel(ai).toString()
                    }
                    CloneInfo(
                        label = baseLabel,
                        packageName = pkg,
                        basePackageName = base,
                        cloneNumber = num,
                        icon = pm.getApplicationIcon(ai)
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.cloneNumber }))
    }

    private fun onAppSelected(app: InstalledApp) {
        val msg = if (app.existingCloneCount > 0) {
            "Create another clone of “${app.label}”?\n\nYou already have ${app.existingCloneCount} clone(s). A new one will be named ${app.packageName}.cloneN."
        } else {
            "Create a dual instance of “${app.label}”?\n\nThis installs a second independent copy you can log into with a different account."
        }

        AlertDialog.Builder(this)
            .setTitle("Clone ${app.label}?")
            .setMessage(msg)
            .setPositiveButton("Clone") { _, _ -> performClone(app) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performClone(app: InstalledApp) {
        CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Cloning ${app.label}…"
            try {
                val engine = CloneEngine(this@MainActivity)
                val resultApks = withContext(Dispatchers.IO) {
                    engine.cloneApp(app)
                }
                progressBar.visibility = View.GONE

                val targetPkg = resultApks.firstOrNull()?.parentFile?.name
                    ?.removePrefix("clone_work_")
                    ?: "${app.packageName}.clone?"

                statusText.text = "Cloned → $targetPkg"
                Toast.makeText(
                    this@MainActivity,
                    "Clone ready: $targetPkg\nConfirm the install prompt…",
                    Toast.LENGTH_LONG
                ).show()

                engine.launchInstall(targetPkg, resultApks)

                // Refresh after a short delay so the new clone appears if install succeeds
                statusText.postDelayed({ refreshClonesOnly() }, 4000)
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = "Clone failed"
                Logger.log("ERROR cloning ${app.packageName}: ${e.message}\n${e.stackTraceToString()}")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clone failed")
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton("View Logs") { _, _ ->
                        startActivity(Intent(this@MainActivity, LogActivity::class.java))
                    }
                    .setNegativeButton("OK", null)
                    .show()
            }
        }
    }

    private fun openClone(clone: CloneInfo) {
        val launch = packageManager.getLaunchIntentForPackage(clone.packageName)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "No launchable activity for this clone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmUninstall(clone: CloneInfo) {
        AlertDialog.Builder(this)
            .setTitle("Remove clone?")
            .setMessage("Uninstall “${clone.label} (${clone.cloneNumber})”?\n\n${clone.packageName}\n\nThis only removes the clone, not the original app.")
            .setPositiveButton("Uninstall") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:${clone.packageName}")
                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    }
                    startActivity(intent)
                    statusText.postDelayed({ refreshClonesOnly() }, 2000)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not start uninstall: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
