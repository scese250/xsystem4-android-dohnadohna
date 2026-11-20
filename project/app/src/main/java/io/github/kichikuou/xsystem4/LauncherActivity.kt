package io.github.kichikuou.xsystem4

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

private const val ICON_SIZE_DP = 40
private const val INSTALL_REQUEST = 1
private const val STORAGE_PERMISSION_REQUEST = 2

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

class LauncherActivity : Activity(), GameListObserver {
    companion object {
        private var savedGameList: GameList? = null
    }

    private lateinit var gameList: GameList
    private lateinit var adapter: GameListAdapter
    private var progressDialog: Dialog? = null
    private var lastStorageAccessGranted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.launcher)

        lastStorageAccessGranted = hasAllFilesAccess()
        gameList = getGameList(refresh = false)
        gameList.observer = this
        adapter = GameListAdapter(this, gameList)

        val listView = findViewById<ListView>(R.id.list)
        listView.adapter = adapter
        listView.emptyView = findViewById(R.id.empty)
        findViewById<TextView>(R.id.usage).text = Html.fromHtml(getString(R.string.usage))
        listView.setOnItemClickListener { _, _, pos, _ ->
            onListItemClick(listView.adapter.getItem(pos) as Item)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            onItemLongClick(listView.adapter.getItem(pos) as Item)
        }
        renderInstallState(gameList.installState)
    }

    override fun onResume() {
        super.onResume()
        val granted = hasAllFilesAccess()
        if (granted != lastStorageAccessGranted) {
            lastStorageAccessGranted = granted
            setGameList(getGameList(refresh = true))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            lastStorageAccessGranted = hasAllFilesAccess()
            setGameList(getGameList(refresh = true))
        }
    }

    override fun onDestroy() {
        if (gameList.observer === this) {
            gameList.observer = null
        }
        dismissProgressDialog()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.launcher_menu, menu)
        // The charset parameter of ZipInputStream is not supported on Android <7.0.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            menu.findItem(R.id.install_from_zip).isEnabled = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                setGameList(getGameList(refresh = true))
                true
            }
            R.id.add_game_folder -> {
                showAddGameFolderDialog()
                true
            }
            R.id.grant_storage_access -> {
                requestAllFilesAccess()
                true
            }
            R.id.install_from_zip -> {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "application/zip"
                startActivityForResult(Intent.createChooser(i, getString(R.string.choose_a_file)), INSTALL_REQUEST)
                true
            }
            R.id.help -> {
                AlertDialog.Builder(this)
                    .setMessage(Html.fromHtml(getString(R.string.usage)))
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
        if (resultCode != RESULT_OK)
            return
        val uri = data?.data ?: return
        when (requestCode) {
            INSTALL_REQUEST -> {
                val input = contentResolver.openInputStream(uri) ?: return
                gameList.install(input)
                renderInstallState(gameList.installState)
            }
        }
    }

    override fun onInstallProgress(path: String) {
        renderInstallState(gameList.installState)
    }

    override fun onInstallSuccess() {
        renderInstallState(gameList.installState)
    }

    override fun onInstallFailure(msgId: Int) {
        renderInstallState(gameList.installState)
    }

    private fun getGameList(refresh: Boolean): GameList {
        if (savedGameList == null || refresh && savedGameList?.installState is InstallState.Idle) {
            savedGameList = GameList(this)
        }
        return savedGameList!!
    }

    private fun setGameList(newGameList: GameList) {
        if (gameList.observer === this) {
            gameList.observer = null
        }
        gameList = newGameList
        gameList.observer = this
        adapter = GameListAdapter(this, gameList)
        findViewById<ListView>(R.id.list).adapter = adapter
        renderInstallState(gameList.installState)
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
                    setGameList(getGameList(refresh = true))
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
        AlertDialog.Builder(this).setTitle(R.string.uninstall_dialog_title)
            .setMessage(getString(R.string.uninstall_dialog_message, item.name))
            .setPositiveButton(R.string.ok) {_, _ ->
                gameList.uninstall(item)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(R.string.cancel) {_, _ -> }
            .show()
        return true
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, R.string.storage_access_already_granted, Toast.LENGTH_SHORT).show()
                setGameList(getGameList(refresh = true))
                return
            }
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (hasAllFilesAccess()) {
                Toast.makeText(this, R.string.storage_access_already_granted, Toast.LENGTH_SHORT).show()
                setGameList(getGameList(refresh = true))
            } else {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    STORAGE_PERMISSION_REQUEST
                )
            }
        } else {
            Toast.makeText(this, R.string.storage_access_already_granted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddGameFolderDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(getString(R.string.add_folder_hint))
            setSelection(text.length)
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
                val dir = File(rawPath)
                if (!dir.exists() || !dir.isDirectory) {
                    editText.error = if (!hasAllFilesAccess()) {
                        getString(R.string.add_folder_error_not_readable)
                    } else {
                        getString(R.string.add_folder_error_not_found)
                    }
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
                setGameList(getGameList(refresh = true))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showManageCustomFoldersDialog() {
        val paths = CustomGamePaths.getCustomPaths(this)
        if (paths.isEmpty()) return
        val keep = BooleanArray(paths.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_folders_dialog_title)
            .setMultiChoiceItems(paths.toTypedArray(), keep) { _, which, isChecked ->
                keep[which] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val remaining = paths.filterIndexed { index, _ -> keep[index] }
                CustomGamePaths.setCustomPaths(this, remaining)
                setGameList(getGameList(refresh = true))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderInstallState(state: InstallState) {
        when (state) {
            InstallState.Idle -> dismissProgressDialog()
            is InstallState.Installing -> showProgressDialog(state.progress)
            InstallState.Succeeded -> {
                dismissProgressDialog()
                adapter.notifyDataSetChanged()
                gameList.consumeInstallResult()
            }
            is InstallState.Failed -> {
                dismissProgressDialog()
                AlertDialog.Builder(this).setTitle(R.string.error)
                    .setMessage(state.msgId)
                    .setPositiveButton(R.string.ok) {_, _ -> }
                    .show()
                gameList.consumeInstallResult()
            }
        }
    }

    private fun showProgressDialog(progress: String? = null) {
        if (progressDialog == null) {
            progressDialog = Dialog(this)
            progressDialog!!.apply {
                setTitle(R.string.install_dialog_title)
                setCancelable(false)
                setContentView(R.layout.progress_dialog)
                show()
            }
        }
        progress?.let {
            progressDialog?.findViewById<TextView>(R.id.text)?.text = getString(R.string.install_progress, it)
        }
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }
}
