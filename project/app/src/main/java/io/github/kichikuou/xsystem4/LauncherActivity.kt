package io.github.kichikuou.xsystem4

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

private const val ICON_SIZE_DP = 40
private const val STORAGE_PERMISSION_REQUEST = 2
private const val OPEN_DOCUMENT_TREE_REQUEST = 3
private const val STATE_AUTO_PROMPTED_PERMISSION = "auto_prompted_permission"

private class GameListAdapter(activity: Activity, private val gameList: GameList) : BaseAdapter() {
    companion object {
        private var defaultTextColor: ColorStateList? = null
    }
    private val context: Context = activity

    override fun getCount(): Int = gameList.size
    override fun getItem(position: Int): Any = gameList[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val item = gameList[position]
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.launcher_item, parent, false)
        val icon = item.getIconBitmap((ICON_SIZE_DP * context.resources.displayMetrics.density).toInt())
        view.findViewById<ImageView>(R.id.icon).setImageBitmap(icon)
        val title = view.findViewById<TextView>(R.id.title)
        if (defaultTextColor == null)
            defaultTextColor = title.textColors
        title.text = item.name
        if (item.error != null)
            title.setTextColor(Color.GRAY)
        else
            title.setTextColor(defaultTextColor)
        return view
    }
}

class LauncherActivity : Activity() {
    companion object {
        private var savedGameList: GameList? = null
    }

    private lateinit var gameList: GameList
    private lateinit var adapter: GameListAdapter
    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var permissionPanel: View
    private lateinit var addFolderFab: Button
    private var hasAutoPromptedPermission = false
    private var storageAccessGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.launcher)

        listView = findViewById<ListView>(R.id.list)
        emptyView = findViewById<View>(R.id.empty)
        permissionPanel = findViewById<View>(R.id.permission_required)
        addFolderFab = findViewById<Button>(R.id.add_folder_fab)

        findViewById<TextView>(R.id.usage).text = fromHtml(getString(R.string.usage))
        findViewById<Button>(R.id.permission_button).setOnClickListener {
            requestAllFilesAccess()
        }
        findViewById<Button>(R.id.add_folder_empty).setOnClickListener {
            openFolderPicker()
        }
        addFolderFab.setOnClickListener {
            openFolderPicker()
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            onListItemClick(listView.adapter.getItem(pos) as Item)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            onItemLongClick(listView.adapter.getItem(pos) as Item)
        }

        hasAutoPromptedPermission = savedInstanceState?.getBoolean(STATE_AUTO_PROMPTED_PERMISSION) ?: false
        storageAccessGranted = hasAllFilesPermission()

        if (storageAccessGranted) {
            applyGameList(getGameList(refresh = false))
        } else {
            savedGameList = null
            applyGameList(GameList(this, allowStorageAccess = false))
            showPermissionRequired()
            if (!hasAutoPromptedPermission) {
                hasAutoPromptedPermission = true
                requestAllFilesAccess()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // This also runs when the user comes back from the "All files access" settings screen.
        // If access was just granted, rebuild the list so a game found in /storage/emulated/0
        // or /storage/emulated/0/Download shows up without restarting the app.
        val granted = hasAllFilesPermission()
        if (granted) {
            if (!storageAccessGranted) {
                storageAccessGranted = true
                applyGameList(getGameList(refresh = true))
            } else {
                updateEmptyState()
            }
        } else {
            val wasGranted = storageAccessGranted
            storageAccessGranted = false
            if (wasGranted) {
                // Access was revoked while the app was running. Do not keep showing a stale list
                // that can no longer be used.
                savedGameList = null
                applyGameList(GameList(this, allowStorageAccess = false))
            }
            updateEmptyState()
            // Only auto-open the permission screen once; otherwise returning without granting
            // would bounce the user back to Settings forever.
            if (!hasAutoPromptedPermission) {
                hasAutoPromptedPermission = true
                requestAllFilesAccess()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AUTO_PROMPTED_PERMISSION, hasAutoPromptedPermission)
    }

    // Html.fromHtml(String) is deprecated since API 24. The newer overload needs API 24, but the
    // deprecated one behaves identically on every version we support (minSdk 21).
    @Suppress("DEPRECATION")
    private fun fromHtml(text: String): CharSequence = Html.fromHtml(text)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (hasAllFilesPermission()) {
                storageAccessGranted = true
                applyGameList(getGameList(refresh = true))
            } else {
                storageAccessGranted = false
                updateEmptyState()
                Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.launcher_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                refreshGameList()
                true
            }
            R.id.add_game_folder -> {
                openFolderPicker()
                true
            }
            R.id.add_game_folder_manual -> {
                showAddGameFolderDialog()
                true
            }
            R.id.manage_game_folders -> {
                showManageCustomFoldersDialog()
                true
            }
            R.id.grant_storage_access -> {
                requestAllFilesAccess()
                true
            }
            R.id.help -> {
                AlertDialog.Builder(this)
                    .setMessage(fromHtml(getString(R.string.usage)))
                    .show()
                true
            }
            R.id.licenses -> {
                val intent = Intent(this, LicensesMenuActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OPEN_DOCUMENT_TREE_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            onFolderTreeSelected(uri)
        }
    }

    private fun getGameList(refresh: Boolean): GameList {
        if (savedGameList == null || refresh) {
            savedGameList = GameList(this)
        }
        return savedGameList!!
    }

    private fun applyGameList(newGameList: GameList) {
        gameList = newGameList
        adapter = GameListAdapter(this, gameList)
        listView.adapter = adapter
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (!storageAccessGranted) {
            permissionPanel.visibility = View.VISIBLE
            listView.visibility = View.GONE
            emptyView.visibility = View.GONE
            addFolderFab.visibility = View.GONE
            return
        }
        val hasGames = gameList.size > 0
        permissionPanel.visibility = View.GONE
        listView.visibility = if (hasGames) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasGames) View.GONE else View.VISIBLE
        // Show the + button prominently while no game has been found. When games are listed it is
        // hidden to avoid overlapping the list; the "Add Game Folder" menu entry stays available.
        addFolderFab.visibility = if (hasGames) View.GONE else View.VISIBLE
    }

    private fun showPermissionRequired() {
        storageAccessGranted = false
        updateEmptyState()
    }

    private fun refreshGameList() {
        if (!ensureStoragePermission()) {
            return
        }
        applyGameList(getGameList(refresh = true))
    }

    private fun onListItemClick(item: Item) {
        if (item.error != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.error)
                .setMessage(item.error)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val i = Intent()
        i.setClass(applicationContext, XSystem4Activity::class.java)
        i.putExtra(XSystem4Activity.EXTRA_GAME_ROOT, item.path.path)
        i.putExtra(XSystem4Activity.EXTRA_SAVE_DIR, item.savedir!!.path)
        i.putExtra(XSystem4Activity.EXTRA_ENCODING, item.encoding)
        startActivity(i)
    }

    private fun onItemLongClick(item: Item): Boolean {
        if (item.customPathRoot != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.uninstall_dialog_title)
                .setMessage(getString(R.string.remove_custom_folder_message, item.customPathRoot))
                .setPositiveButton(R.string.ok) { _, _ ->
                    CustomGamePaths.removeCustomPath(this, item.customPathRoot)
                    if (hasAllFilesPermission()) {
                        applyGameList(getGameList(refresh = true))
                    } else {
                        updateEmptyState()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }
        if (item.isDirectExternal) {
            Toast.makeText(
                this,
                getString(R.string.external_uninstall_notice, item.path.parentFile?.path ?: item.path.path),
                Toast.LENGTH_LONG
            ).show()
            return true
        }
        if (!ensureStoragePermission()) {
            return true
        }
        AlertDialog.Builder(this).setTitle(R.string.uninstall_dialog_title)
            .setMessage(getString(R.string.uninstall_dialog_message, item.name))
            .setPositiveButton(R.string.ok) {_, _ ->
                gameList.uninstall(item)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
            .setNegativeButton(R.string.cancel) {_, _ -> }
            .show()
        return true
    }

    private fun hasAllFilesPermission(): Boolean {
        return GameList.hasAllFilesPermission(this)
    }

    private fun ensureStoragePermission(): Boolean {
        if (hasAllFilesPermission()) {
            return true
        }
        Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_LONG).show()
        showPermissionRequired()
        requestAllFilesAccess()
        return false
    }

    private fun requestAllFilesAccess() {
        hasAutoPromptedPermission = true
        if (hasAllFilesPermission()) {
            Toast.makeText(this, R.string.storage_access_already_granted, Toast.LENGTH_SHORT).show()
            if (!storageAccessGranted) {
                storageAccessGranted = true
                applyGameList(getGameList(refresh = true))
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAllFilesSettings()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_REQUEST
            )
        } else {
            // No runtime storage permission is needed on Android 5.1 and older.
            storageAccessGranted = true
            applyGameList(getGameList(refresh = true))
        }
    }

    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w("LauncherActivity", "App-specific all files settings not found", e)
        } catch (e: Exception) {
            Log.w("LauncherActivity", "Failed to open app-specific all files settings", e)
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e: Exception) {
            Log.w("LauncherActivity", "Failed to open all files settings", e)
            Toast.makeText(this, R.string.permission_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION") // Activity Result APIs require AndroidX, which this project does not use;
                             // startActivityForResult is still supported on every API level.
    private fun openFolderPicker() {
        if (!ensureStoragePermission()) {
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivityForResult(intent, OPEN_DOCUMENT_TREE_REQUEST)
        } catch (e: ActivityNotFoundException) {
            Log.w("LauncherActivity", "No folder picker found", e)
            Toast.makeText(this, R.string.folder_picker_unavailable, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w("LauncherActivity", "Failed to open folder picker", e)
            Toast.makeText(this, R.string.folder_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun onFolderTreeSelected(uri: Uri) {
        if (!ensureStoragePermission()) {
            return
        }
        val path = try {
            treeUriToPhysicalPath(uri)
        } catch (e: Exception) {
            Log.w("LauncherActivity", "Failed to resolve folder path", e)
            null
        }
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, R.string.folder_picker_invalid_path, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
                Toast.makeText(this, R.string.folder_picker_not_found, Toast.LENGTH_LONG).show()
                return
            }
            if (!GameList.isValidGameOrParentDirectory(dir)) {
                Toast.makeText(
                    this,
                    getString(R.string.folder_picker_no_game, dir.path),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        } catch (e: SecurityException) {
            Log.w("LauncherActivity", "No permission to read $path", e)
            Toast.makeText(this, R.string.folder_picker_not_found, Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Log.w("LauncherActivity", "Failed to read $path", e)
            Toast.makeText(this, R.string.folder_picker_not_found, Toast.LENGTH_LONG).show()
            return
        }
        val added = CustomGamePaths.addCustomPath(this, path)
        Toast.makeText(
            this,
            if (added) getString(R.string.add_folder_added, path) else getString(R.string.add_folder_already_added),
            Toast.LENGTH_SHORT
        ).show()
        applyGameList(getGameList(refresh = true))
    }

    // Maps a SAF tree URI (ACTION_OPEN_DOCUMENT_TREE) to a path the native code can open.
    // Supported document IDs: "primary:...", "home:...", marshal ("raw:...") and any other
    // volume ID such as an SD card UUID ("1234-ABCD:...").
    @Suppress("DEPRECATION") // Environment.getExternalStorageDirectory/PublicDirectory are the
                             // portable way to map these volume IDs on API 21+.
    private fun treeUriToPhysicalPath(uri: Uri): String? {
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            Log.w("LauncherActivity", "Failed to read tree document id from $uri", e)
            return null
        }

        if (documentId.startsWith("raw:")) {
            val rawPath = documentId.substring("raw:".length).trim()
            return rawPath.ifEmpty { null }
        }
        // Some providers expose an absolute path directly as the document ID.
        if (documentId.startsWith("/")) {
            return documentId
        }
        // Cloud/third-party providers do not map to a filesystem path. Only the system
        // ExternalStorageProvider (internal storage and SD cards) is backed by real files.
        val authority = uri.authority
        if (authority != null && authority != "com.android.externalstorage.documents") {
            return null
        }

        val separator = documentId.indexOf(':')
        val volumeId = if (separator < 0) documentId else documentId.substring(0, separator)
        val relPath = if (separator < 0) "" else documentId.substring(separator + 1).trim('/')
        if (volumeId.isEmpty()) {
            return null
        }
        val basePath = when {
            volumeId.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            // The Documents root is reported as volume "home" on some Android versions
            // (both "home" and "home:" occur in the wild).
            volumeId.equals("home", ignoreCase = true) ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
            else -> "/storage/$volumeId"
        }
        return if (relPath.isEmpty()) basePath else "$basePath/$relPath"
    }

    private fun showAddGameFolderDialog() {
        if (!ensureStoragePermission()) {
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            hint = getString(R.string.add_folder_hint)
        }
        container.addView(editText)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.add_folder_dialog_title)
            .setMessage(R.string.add_folder_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.add_folder_button_add, null)
            .setNegativeButton(R.string.cancel, null)

        if (CustomGamePaths.getCustomPaths(this).isNotEmpty()) {
            builder.setNeutralButton(R.string.add_folder_button_manage) { _, _ ->
                showManageCustomFoldersDialog()
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawPath = editText.text.toString().trim()
                if (rawPath.isEmpty()) {
                    editText.error = getString(R.string.add_folder_error_empty)
                    return@setOnClickListener
                }
                if (!hasAllFilesPermission()) {
                    editText.error = getString(R.string.add_folder_error_not_readable)
                    requestAllFilesAccess()
                    return@setOnClickListener
                }
                try {
                    val dir = File(rawPath)
                    if (!dir.exists() || !dir.isDirectory) {
                        editText.error = getString(R.string.add_folder_error_not_found)
                        return@setOnClickListener
                    }
                    if (!dir.canRead()) {
                        editText.error = getString(R.string.add_folder_error_not_readable)
                        return@setOnClickListener
                    }
                    if (!GameList.isValidGameOrParentDirectory(dir)) {
                        editText.error = getString(R.string.add_folder_error_no_games)
                        return@setOnClickListener
                    }
                    val canonical = CustomGamePaths.canonicalOrAbsolute(dir)
                    val added = CustomGamePaths.addCustomPath(this, canonical)
                    Toast.makeText(
                        this,
                        if (added) getString(R.string.add_folder_added, canonical) else getString(R.string.add_folder_already_added),
                        Toast.LENGTH_SHORT
                    ).show()
                    applyGameList(getGameList(refresh = true))
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.w("LauncherActivity", "Failed to validate folder $rawPath", e)
                    editText.error = getString(R.string.add_folder_error_not_readable)
                }
            }
        }
        dialog.show()
    }

    private fun showManageCustomFoldersDialog() {
        val paths = CustomGamePaths.getCustomPaths(this)
        if (paths.isEmpty()) {
            Toast.makeText(this, R.string.no_custom_folders, Toast.LENGTH_SHORT).show()
            return
        }
        val keep = BooleanArray(paths.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_folders_dialog_title)
            .setMultiChoiceItems(paths.toTypedArray(), keep) { _, which, isChecked ->
                keep[which] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val remaining = paths.filterIndexed { index, _ -> keep[index] }
                CustomGamePaths.setCustomPaths(this, remaining)
                if (hasAllFilesPermission()) {
                    applyGameList(getGameList(refresh = true))
                } else {
                    updateEmptyState()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
