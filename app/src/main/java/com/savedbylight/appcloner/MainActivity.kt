package com.savedbylight.appcloner

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private val mainScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Logger.init(applicationContext)

        val recycler = findViewById<RecyclerView>(R.id.appListRecycler)
        progressBar = findViewById(R.id.progressBar)
        recycler.layoutManager = LinearLayoutManager(this)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        mainScope.launch {
            setBusy(true)
            try {
                val apps = withContext(Dispatchers.IO) { loadUserApps() }
                Logger.log("Loaded ${apps.size} installed apps")
                recycler.adapter = AppListAdapter(apps) { app -> onAppSelected(app) }
            } catch (e: Exception) {
                Logger.log("ERROR loading installed apps: ${e.message}")
                Toast.makeText(this@MainActivity, "Couldn't load apps: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private val signatureSensitivePackagePrefixes = listOf(
        "com.google.",
        "com.android.vending",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary"
    )

    private fun isAlreadyAClone(packageName: String): Boolean =
        Regex("\\.clone\\d+$").containsMatchIn(packageName)

    private fun loadUserApps(): List<InstalledApp> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .filter { !isAlreadyAClone(it.packageName) }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { ai ->
                try {
                    InstalledApp(
                        label = pm.getApplicationLabel(ai).toString(),
                        packageName = ai.packageName,
                        sourceApkPath = ai.sourceDir,
                        icon = pm.getApplicationIcon(ai),
                        isSystemApp = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun onAppSelected(app: InstalledApp) {
        AlertDialog.Builder(this)
            .setTitle("Clone ${app.label}")
            .setItems(
                arrayOf(
                    "ROM clone profile — separate app data (root + ROM support)",
                    "Standalone APK clone — installs as a separate package"
                )
            ) { _, which ->
                when (which) {
                    0 -> chooseRomProfile(app)
                    1 -> {
                        val signatureSensitive = app.isSystemApp || signatureSensitivePackagePrefixes.any { app.packageName.startsWith(it) }
                        if (signatureSensitive) {
                            AlertDialog.Builder(this)
                                .setTitle("Standalone clone compatibility warning")
                                .setMessage(
                                    "${app.label} is a system or signature-sensitive app. Re-signing it as a separate package may stop it from working. " +
                                        "Use ROM clone profile mode for a true separate user instance when your ROM supports it. Continue anyway?"
                                )
                                .setPositiveButton("Continue") { _, _ -> ensureStandaloneInstallAllowed { chooseStandaloneInstance(app) } }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            ensureStandaloneInstallAllowed { chooseStandaloneInstance(app) }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseStandaloneInstance(app: InstalledApp) {
        val choices = (1..5).map { index ->
            val target = "${app.packageName}.clone$index"
            val alreadyInstalled = isPackageInstalled(target)
            if (alreadyInstalled) "Clone $index — update existing instance" else "Clone $index — create new instance"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("${app.label}: APK clone")
            .setItems(choices) { _, selected -> cloneAsStandaloneApk(app, selected + 1) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun ensureStandaloneInstallAllowed(onAllowed: () -> Unit) {
        mainScope.launch {
            val rootAvailable = withContext(Dispatchers.IO) { RootInstaller.isRootAvailable() }
            if (!rootAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Allow APK installation")
                    .setMessage(
                        "Android needs permission for App Cloner to prepare standalone APK clones. " +
                            "Enable ‘Allow from this source’, return here, then select the app again."
                    )
                    .setPositiveButton("Open settings") { _, _ ->
                        try {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Open Settings and allow installs for App Cloner.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@launch
            }
            onAllowed()
        }
    }

    private fun cloneAsStandaloneApk(app: InstalledApp, index: Int) {
        val targetPackage = "${app.packageName}.clone$index"
        mainScope.launch {
            setBusy(true)
            try {
                val routedToRoot = withContext(Dispatchers.IO) {
                    val engine = CloneEngine(this@MainActivity)
                    val resultFiles = engine.cloneApp(app, targetPackage)
                    engine.launchInstall(targetPackage, resultFiles)
                }
                val message = if (routedToRoot) {
                    "Clone $index installed. Open it from your launcher."
                } else {
                    "Clone $index staged. Confirm installation in Android's installer if prompted."
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Logger.log("ERROR cloning ${app.packageName} as $targetPackage: ${e.message}\n${e.stackTraceToString()}")
                Toast.makeText(this@MainActivity, "Clone failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun chooseRomProfile(app: InstalledApp) {
        mainScope.launch {
            setBusy(true)
            try {
                val profiles = withContext(Dispatchers.IO) { CloneProfileManager(this@MainActivity).listProfiles() }
                setBusy(false)
                if (profiles.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No ROM clone profile found")
                        .setMessage(
                            "Create an Android clone profile, then install ${app.label} into it. " +
                                "This requires root and a ROM that implements the CLONE user type (such as a ROM with native app-clone support)."
                        )
                        .setPositiveButton("Create profile") { _, _ -> createProfileAndClone(app) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@launch
                }

                val labels = profiles.map { "${it.name} (user ${it.userId})" } + "Create a new clone profile…"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Install ${app.label} into profile")
                    .setItems(labels.toTypedArray()) { _, selected ->
                        if (selected == profiles.size) createProfileAndClone(app)
                        else cloneIntoProfile(app, profiles[selected])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                setBusy(false)
                Logger.log("ROM profile lookup failed: ${e.message}")
                Toast.makeText(this@MainActivity, e.message ?: "Couldn't list ROM clone profiles", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createProfileAndClone(app: InstalledApp?) {
        mainScope.launch {
            setBusy(true)
            try {
                val profile = withContext(Dispatchers.IO) {
                    CloneProfileManager(this@MainActivity).createProfile()
                }
                if (app == null) {
                    Toast.makeText(this@MainActivity, "Created ${profile.name} (user ${profile.userId}).", Toast.LENGTH_LONG).show()
                } else {
                    installAndOpenInProfile(app, profile)
                }
            } catch (e: Exception) {
                Logger.log("ROM profile creation failed: ${e.message}\n${e.stackTraceToString()}")
                Toast.makeText(this@MainActivity, e.message ?: "Couldn't create clone profile", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun cloneIntoProfile(app: InstalledApp, profile: CloneProfile) {
        mainScope.launch {
            setBusy(true)
            try {
                installAndOpenInProfile(app, profile)
            } catch (e: Exception) {
                Logger.log("ROM profile clone failed for ${app.packageName} in user ${profile.userId}: ${e.message}\n${e.stackTraceToString()}")
                Toast.makeText(this@MainActivity, e.message ?: "Couldn't clone app into profile", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun installAndOpenInProfile(app: InstalledApp, profile: CloneProfile) {
        val launched = withContext(Dispatchers.IO) {
            val manager = CloneProfileManager(this@MainActivity)
            manager.installApp(app.packageName, profile)
            manager.launchApp(app.packageName, profile)
        }
        val message = if (launched) {
            "${app.label} installed in ${profile.name} and launch requested."
        } else {
            "${app.label} is installed in ${profile.name}. Use the ROM's clone-profile launcher entry if it didn't open."
        }
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
    }

    private fun showProfileManager() {
        mainScope.launch {
            setBusy(true)
            try {
                val profiles = withContext(Dispatchers.IO) { CloneProfileManager(this@MainActivity).listProfiles() }
                setBusy(false)
                val labels = listOf("Create new clone profile…") + profiles.map { "${it.name} (user ${it.userId})" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("ROM clone profiles")
                    .setItems(labels.toTypedArray()) { _, selected ->
                        if (selected == 0) {
                            createProfileAndClone(null)
                        } else {
                            confirmDeleteProfile(profiles[selected - 1])
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(this@MainActivity, e.message ?: "Couldn't manage clone profiles", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteProfile(profile: CloneProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${profile.name}?")
            .setMessage(
                "This removes the entire Android user/profile ${profile.userId}, including its cloned apps and their data. " +
                    "The original apps in your main profile will not be removed."
            )
            .setPositiveButton("Delete profile") { _, _ ->
                mainScope.launch {
                    setBusy(true)
                    try {
                        withContext(Dispatchers.IO) { CloneProfileManager(this@MainActivity).removeProfile(profile) }
                        Toast.makeText(this@MainActivity, "Removed ${profile.name}.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, e.message ?: "Couldn't remove profile", Toast.LENGTH_LONG).show()
                    } finally {
                        setBusy(false)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_view_logs -> {
            startActivity(Intent(this, LogActivity::class.java))
            true
        }
        R.id.action_firebase_config -> {
            startActivity(Intent(this, FirebaseConfigActivity::class.java))
            true
        }
        R.id.action_manage_clone_profiles -> {
            showProfileManager()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
