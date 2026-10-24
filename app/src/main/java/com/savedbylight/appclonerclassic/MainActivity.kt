package com.savedbylight.appclonerclassic

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import com.google.android.material.appbar.MaterialToolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var fridaSwitch: SwitchMaterial
    private lateinit var dexPatchSwitch: SwitchMaterial
    private lateinit var downloadFridaButton: Button

    private var allApps: List<InstalledApp> = emptyList()
    private var allClones: List<CloneInfo> = emptyList()
    private var filteredApps: List<InstalledApp> = emptyList()
    private var filteredClones: List<CloneInfo> = emptyList()

    private lateinit var appAdapter: AppListAdapter
    private lateinit var cloneAdapter: CloneListAdapter

    private var currentTab = 0 // 0 = Clone New, 1 = My Clones

    // Prefixes we never offer to clone (except allowlisted Google/Play packages below).
    private val excludedPackagePrefixes = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary"
    )

    /**
     * Google / Play stack packages that may be cloned (standalone or ROM profile).
     * These are often FLAG_SYSTEM, so they must be allowlisted past the user-app filter.
     * Prefer ROM profile for real dual-account use; standalone changes package + cert.
     */
    private val allowedGooglePackages = setOf(
        "com.google.android.gms",           // Google Play services
        "com.google.android.gsf",           // Google Services Framework
        "com.google.android.gsf.login",     // Google account login (some devices)
        "com.google.android.gms.policy_sidecar_aps",
        "com.android.vending",              // Google Play Store
        "com.google.android.play.games",    // Play Games
        "com.google.android.instantapps.supervisor"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Logger.init(applicationContext)

        findViewById<MaterialToolbar>(R.id.toolbar)?.let { setSupportActionBar(it) }

        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        emptyStateText = findViewById(R.id.emptyStateText)
        appListRecycler = findViewById(R.id.appListRecycler)
        cloneListRecycler = findViewById(R.id.cloneListRecycler)
        searchEditText = findViewById(R.id.searchEditText)
        tabLayout = findViewById(R.id.tabLayout)
        fridaSwitch = findViewById(R.id.fridaSwitch)
        dexPatchSwitch = findViewById(R.id.dexPatchSwitch)
        downloadFridaButton = findViewById(R.id.downloadFridaButton)

        dexPatchSwitch.isChecked = SmaliPatcher.shouldPatch(this)
        dexPatchSwitch.setOnCheckedChangeListener { _, isChecked ->
            SmaliPatcher.setShouldPatch(this, isChecked)
            Toast.makeText(this,
                if (isChecked) "DEX patching ENABLED (breaks signature/root checks)"
                else "DEX patching DISABLED",
                Toast.LENGTH_SHORT).show()
        }

        fridaSwitch.isChecked = FridaInjector.shouldInject(this)
        fridaSwitch.setOnCheckedChangeListener { _, isChecked ->
            FridaInjector.setShouldInject(this, isChecked)
            Toast.makeText(this,
                if (isChecked) "Frida injection ENABLED for next clones"
                else "Frida injection DISABLED",
                Toast.LENGTH_SHORT).show()
        }

        downloadFridaButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = FridaInjector.ensureGadgetsDownloaded(this@MainActivity)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        if (ok) "Frida gadgets ready"
                        else "Download failed. Check internet / GitHub access.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        appListRecycler.layoutManager = LinearLayoutManager(this)
        cloneListRecycler.layoutManager = LinearLayoutManager(this)

        appAdapter = AppListAdapter(emptyList()) { app -> onAppSelected(app) }
        cloneAdapter = CloneListAdapter(
            emptyList(),
            onOpen = { clone -> openClone(clone) },
            onShortcut = { clone -> ShortcutHelper.pinCloneShortcut(this, clone) },
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

    private fun isAllowedGooglePackage(packageName: String): Boolean =
        packageName in allowedGooglePackages

    private fun isExcludedFromCloning(packageName: String): Boolean {
        if (isAllowedGooglePackage(packageName)) return false
        // Block other com.google.* unless explicitly allowlisted
        if (packageName.startsWith("com.google.")) return true
        return excludedPackagePrefixes.any { packageName.startsWith(it) }
    }

    private fun loadUserApps(): List<InstalledApp> {
        val pm = packageManager
        val cloneCounts = countClonesByBase()
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { ai ->
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // User apps, or allowlisted Google/Play system packages
                !isSystem || isAllowedGooglePackage(ai.packageName)
            }
            .filter { it.packageName != packageName }
            .filter { !isExcludedFromCloning(it.packageName) }
            .filter { !CloneUtils.isStealthClone(this, it.packageName) }
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
            if (CloneUtils.isStealthClone(this, pkg)) {
                val base = CloneUtils.extractBasePackage(pkg) ?: return@forEach
                counts[base] = (counts[base] ?: 0) + 1
            }
        }
        return counts
    }

    private fun loadExistingClones(): List<CloneInfo> {
        val pm = packageManager
        val standalone = pm.getInstalledApplications(0)
            .mapNotNull { ai ->
                val pkg = ai.packageName
                if (!CloneUtils.isStealthClone(this, pkg)) return@mapNotNull null
                val base = CloneUtils.extractBasePackage(pkg) ?: return@mapNotNull null
                val suffix = pkg.removePrefix("$base.")
                try {
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
                        cloneSuffix = suffix,
                        icon = pm.getApplicationIcon(ai)
                    )
                } catch (_: Exception) {
                    null
                }
            }

        // ROM profile installs share the original package name — they do not appear
        // as separate packages in the main user's drawer. List them from our registry.
        val romClones = try {
            val manager = CloneProfileManager(this)
            val mainPkgs = pm.getInstalledApplications(0).map { it.packageName }.toSet()
            manager.listInstalledApps().mapNotNull { (pkg, userId) ->
                // Only dual-installed user apps that exist on the main user too
                if (pkg !in mainPkgs) return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                if (isExcludedFromCloning(pkg)) return@mapNotNull null
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    if (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
                    CloneInfo(
                        label = pm.getApplicationLabel(ai).toString(),
                        packageName = pkg,
                        basePackageName = pkg,
                        cloneSuffix = "profile $userId",
                        icon = pm.getApplicationIcon(ai),
                        profileUserId = userId
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        return (standalone + romClones)
            .sortedWith(compareBy({ it.label.lowercase() }, { it.cloneSuffix }))
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_manage_clone_profiles -> {
                manageRomProfiles()
                true
            }
            R.id.action_view_logs -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onAppSelected(app: InstalledApp) {
        // High/critical apps (Snapchat, banks, …) need the original package + cert.
        // ROM clone profile = same app in a separate Android user (second account).
        val report = try {
            val ai = packageManager.getApplicationInfo(app.packageName, 0)
            PlayIntegrityDetector.analyze(packageManager, ai)
        } catch (_: Exception) {
            PlayIntegrityDetector.RiskReport(PlayIntegrityDetector.RiskLevel.MEDIUM, emptyList())
        }

        val needsRomProfile = report.level == PlayIntegrityDetector.RiskLevel.HIGH ||
            report.level == PlayIntegrityDetector.RiskLevel.CRITICAL

        if (needsRomProfile) {
            val reasons = if (report.reasons.isNotEmpty()) {
                "\n\nWhy: " + report.reasons.take(3).joinToString("; ")
            } else ""
            AlertDialog.Builder(this)
                .setTitle("Clone ${app.label}")
                .setMessage(
                    "“${app.label}” checks its real package name and Play Store signature. " +
                        "A standalone APK clone changes both, so login often shows “unusual activity”.\n\n" +
                        "Recommended: ROM clone profile — the same official app in a separate " +
                        "Android user/profile (second account, original app identity).\n" +
                        "Requires root and a ROM that supports clone profiles." +
                        reasons
                )
                .setPositiveButton("ROM profile") { _, _ -> startRomProfileClone(app) }
                .setNeutralButton("Standalone anyway") { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle("Standalone may not log in")
                        .setMessage(
                            "Continue only for testing. This creates a different app identity " +
                                "(new package name + new signature), not a bypass of Snapchat’s checks."
                        )
                        .setPositiveButton("Continue") { _, _ -> performClone(app) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            val msg = if (app.existingCloneCount > 0) {
                "Create another clone of “${app.label}”?\n\nYou already have ${app.existingCloneCount} clone(s)."
            } else {
                "Create a dual instance of “${app.label}”?\n\nStandalone APK clone works for many apps. " +
                    "Use ROM profile if this app later blocks login."
            }
            AlertDialog.Builder(this)
                .setTitle("Clone ${app.label}?")
                .setMessage(msg)
                .setPositiveButton("Standalone") { _, _ -> performClone(app) }
                .setNeutralButton("ROM profile") { _, _ -> startRomProfileClone(app) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startRomProfileClone(app: InstalledApp) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    CloneProfileManager(this@MainActivity).listProfiles()
                }
                if (profiles.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("No ROM clone profile")
                        .setMessage(
                            "Create an Android clone profile, then install ${app.label} into it.\n\n" +
                                "Needs root and a ROM that implements the CLONE user type."
                        )
                        .setPositiveButton("Create profile") { _, _ -> createProfileAndClone(app) }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    val labels = profiles.map { "${it.name} (user ${it.userId})" } +
                        "Create a new clone profile…"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Install ${app.label} into profile")
                        .setItems(labels.toTypedArray()) { _, selected ->
                            if (selected == profiles.size) createProfileAndClone(app)
                            else cloneIntoProfile(app, profiles[selected])
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                Logger.log("ROM profile lookup failed: ${e.message}")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("ROM profile unavailable")
                    .setMessage(
                        (e.message ?: "Couldn't list clone profiles") +
                            "\n\nStock unrooted devices cannot use ROM profiles. " +
                            "Standalone clone will not keep Snapchat’s original identity."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun createProfileAndClone(app: InstalledApp) {
        CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Creating clone profile…"
            try {
                val profile = withContext(Dispatchers.IO) {
                    CloneProfileManager(this@MainActivity).createProfile()
                }
                progressBar.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "Created ${profile.name} (user ${profile.userId})",
                    Toast.LENGTH_LONG
                ).show()
                installAndOpenInProfile(app, profile)
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Logger.log("ROM profile creation failed: ${e.message}\n${e.stackTraceToString()}")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Couldn't create profile")
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton("View Logs") { _, _ ->
                        startActivity(Intent(this@MainActivity, LogActivity::class.java))
                    }
                    .setNegativeButton("OK", null)
                    .show()
            }
        }
    }

    private fun cloneIntoProfile(app: InstalledApp, profile: CloneProfile) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                installAndOpenInProfile(app, profile)
            } catch (e: Exception) {
                Logger.log(
                    "ROM profile clone failed for ${app.packageName} in user ${profile.userId}: " +
                        "${e.message}\n${e.stackTraceToString()}"
                )
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: "Couldn't clone into profile",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun installAndOpenInProfile(app: InstalledApp, profile: CloneProfile) {
        progressBar.visibility = View.VISIBLE
        statusText.text = "Installing ${app.label} into ${profile.name}…"
        try {
            val launched = withContext(Dispatchers.IO) {
                val manager = CloneProfileManager(this@MainActivity)
                manager.installApp(app.packageName, profile)
                manager.launchApp(app.packageName, profile)
            }
            statusText.text = "${app.label} → ${profile.name}"
            refreshClonesOnly()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("${app.label} ready in ${profile.name}")
                .setMessage(
                    "Installed as the same official app in Android user ${profile.userId}.\n\n" +
                        "It will not appear as a second icon in your main app drawer — " +
                        "Android only shows packages for the current user.\n\n" +
                        "Open it from App Cloner → My Clones (listed as “profile ${profile.userId}”), " +
                        "or use your ROM’s dual-app / profile switcher."
                )
                .setPositiveButton("Open now") { _, _ ->
                    openClone(
                        CloneInfo(
                            label = app.label,
                            packageName = app.packageName,
                            basePackageName = app.packageName,
                            cloneSuffix = "profile ${profile.userId}",
                            icon = packageManager.getApplicationIcon(app.packageName),
                            profileUserId = profile.userId
                        )
                    )
                }
                .setNegativeButton("OK", null)
                .show()
        } finally {
            progressBar.visibility = View.GONE
        }
    }

    private fun manageRomProfiles() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    CloneProfileManager(this@MainActivity).listProfiles()
                }
                val labels = listOf("Create new clone profile…") +
                    profiles.map { "${it.name} (user ${it.userId})" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("ROM clone profiles")
                    .setItems(labels.toTypedArray()) { _, selected ->
                        if (selected == 0) {
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    val p = withContext(Dispatchers.IO) {
                                        CloneProfileManager(this@MainActivity).createProfile()
                                    }
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Created ${p.name} (user ${p.userId})",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        e.message ?: "Create failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            confirmDeleteProfile(profiles[selected - 1])
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: "Couldn't manage clone profiles",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmDeleteProfile(profile: CloneProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${profile.name}?")
            .setMessage(
                "Removes Android user/profile ${profile.userId} and its app data. " +
                    "Apps in your main profile are not removed."
            )
            .setPositiveButton("Delete") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        CloneProfileManager(this@MainActivity).removeProfile(profile)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Deleted ${profile.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                e.message ?: "Delete failed",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
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
                    ?: "unknown"

                statusText.text = "Cloned → $targetPkg"
                Toast.makeText(
                    this@MainActivity,
                    "Clone ready: $targetPkg\nConfirm the install prompt…",
                    Toast.LENGTH_LONG
                ).show()

                engine.installPackageSession(targetPkg, resultApks)

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
        val userId = clone.profileUserId
        if (userId != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        CloneProfileManager(this@MainActivity).launchApp(
                            clone.packageName,
                            CloneProfile(userId, "profile $userId", true)
                        )
                    } catch (e: Exception) {
                        Logger.log("Open ROM profile clone failed: ${e.message}")
                        false
                    }
                }
                if (!ok) {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not start ${clone.label} in profile $userId. " +
                            "Switch to that profile in system settings if your ROM has a dual-app switcher.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(clone.packageName)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "No launchable activity for this clone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmUninstall(clone: CloneInfo) {
        val userId = clone.profileUserId
        if (userId != null) {
            AlertDialog.Builder(this)
                .setTitle("Remove profile install?")
                .setMessage(
                    "Remove “${clone.label}” from Android user $userId?\n\n" +
                        "The original app in your main profile is not removed."
                )
                .setPositiveButton("Remove") { _, _ ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            RootShell.run("pm uninstall --user $userId ${RootShell.quote(clone.packageName)}")
                            CloneProfileManager(this@MainActivity)
                                .unregisterInstalledApp(clone.packageName, userId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Removed from profile $userId",
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshClonesOnly()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    e.message ?: "Remove failed",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove clone?")
            .setMessage(
                "Uninstall “${clone.label} (${clone.cloneSuffix})”?\n\n${clone.packageName}\n\n" +
                    "This only removes the clone, not the original app."
            )
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
