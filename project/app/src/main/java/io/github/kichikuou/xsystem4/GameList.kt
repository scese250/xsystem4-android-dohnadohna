package io.github.kichikuou.xsystem4

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class Item(
    val name: String,
    val path: File,
    val homedir: File,
    val savedir: File?,
    val icon: File?,
    val error: String?,
    val encoding: String = "sjis",
    val isDirectExternal: Boolean = false,
    val customPathRoot: String? = null
) {
    companion object {
        fun findIniFile(dir: File): File? {
            return try {
                val system40 = File(dir, "System40.ini")
                if (system40.isFile) return system40
                val aliceStart = File(dir, "AliceStart.ini")
                if (aliceStart.isFile) return aliceStart
                val files = dir.listFiles() ?: return null
                files.firstOrNull { it.isFile && it.name.equals("System40.ini", ignoreCase = true) }
                    ?: files.firstOrNull { it.isFile && it.name.equals("AliceStart.ini", ignoreCase = true) }
            } catch (e: Exception) {
                Log.w("GameList", "Failed to look for ini files in ${dir.path}", e)
                null
            }
        }

        fun fromDirectory(
            dir: File,
            homedir: File,
            context: Context,
            isDirectExternal: Boolean = false,
            customPathRoot: String? = null
        ): Item {
            val iniFile = findIniFile(dir)
            if (iniFile == null) {
                val err = context.getString(R.string.toast_no_ini, dir.path)
                Log.w("GameList", err)
                return Item(dir.name, dir, homedir, null, null, err, isDirectExternal = isDirectExternal, customPathRoot = customPathRoot)
            }
            val icon = findIcon(dir)
            val ini = try {
                System40Ini.parse(iniFile, Xsys4rc.read(dir)["encoding"])
            } catch (e: Exception) {
                Log.e("GameList", "Failed to read ${iniFile.path}", e)
                val err = context.getString(R.string.toast_ini_read_error, iniFile.path)
                return Item(dir.name, dir, homedir, null, icon, err, isDirectExternal = isDirectExternal, customPathRoot = customPathRoot)
            }
            if (ini.gameName == null) {
                val err = context.getString(R.string.toast_no_GameName, iniFile.path)
                Log.w("GameList", err)
                return Item(dir.name, dir, homedir, null, icon, err, isDirectExternal = isDirectExternal, customPathRoot = customPathRoot)
            }
            val savedir = File(dir, ini.SaveFolder ?: "SaveData")
            return Item(ini.gameName, dir, homedir, savedir, icon, null, ini.encoding, isDirectExternal, customPathRoot)
        }

        private fun findIcon(dir: File): File? {
            return try {
                val iconFile = dir.listFiles()?.firstOrNull {
                    it.isFile && it.extension.equals("ico", ignoreCase = true)
                }
                if (iconFile != null) {
                    return iconFile
                }
                val exeFiles = dir.listFiles { file ->
                    file.isFile &&
                            file.extension.equals("exe", ignoreCase = true) &&
                            file.name != "OpenSaveFolder.exe" &&
                            file.name != "ResetConfig.exe" &&
                            file.name != "Uninstaller.exe"
                } ?: return null
                for (exeFile in exeFiles) {
                    val bytes = PEResourceExtractor.create(exeFile)?.extractIcon()
                    if (bytes != null) {
                        val f = File(dir, ".xsystem4.ico")
                        f.writeBytes(bytes)
                        return f
                    }
                }
                null
            } catch (e: Exception) {
                Log.e("GameList", "Failed to extract or write icon", e)
                null
            }
        }
    }

    fun getIconBitmap(reqSize: Int): Bitmap? {
        return try {
            val iconFile = icon ?: return null
            val bytes = iconFile.readBytes()
            val buf = ByteBuffer.wrap(bytes)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            buf.skip(4)
            val numIcons = buf.short.toInt()

            // If the icon contains multiple images, convert it to an icon containing only the image
            // closest to the requested size.
            if (numIcons > 1) {
                val bestMatchEntry = Array(numIcons) {
                    val entryBytes = ByteArray(16)
                    buf.get(entryBytes)
                    entryBytes
                }.minWith(compareBy({
                    // Compare the sizes first,
                    val width = if (it[0].toInt() == 0) 256 else it[0].toInt()
                    kotlin.math.abs(width - reqSize)
                }, {
                    // then pick the one with the highest number of bits per pixel.
                    -it[6]
                }))
                buf.position(4)
                buf.putShort(1)
                buf.put(bestMatchEntry)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w("GameList", "Failed to load icon", e)
            null
        }
    }
}

data class System40Ini(val gameName: String?, val SaveFolder: String?, val encoding: String) {
    companion object {
        // `encoding` is the value of the encoding setting in .xsys4rc. If it is not given, the
        // encoding is detected from the content of the file.
        fun parse(file: File, encoding: String?): System40Ini {
            val bytes = file.readBytes()
            val enc = encoding ?: detectEncoding(bytes)
            val charset = charsetOrNull(enc) ?: Charset.forName("Shift_JIS")
            val regex = Regex("""(\w+)\s*=\s*"(.*)"""")
            var gameName: String? = null
            var SaveFolder: String? = null
            for (line in String(bytes, charset).lines()) {
                regex.matchEntire(line)?.let {
                    when (it.groupValues[1]) {
                        "GameName" -> gameName = it.groupValues[2]
                        "SaveFolder" -> SaveFolder = it.groupValues[2]
                    }
                }
            }
            return System40Ini(gameName, SaveFolder, enc)
        }

        private fun charsetOrNull(name: String): Charset? =
            try {
                Charset.forName(name)
            } catch (e: IllegalArgumentException) {
                null
            }

        // Shift_JIS is tried first, because Shift_JIS text is often also valid GBK (as garbage),
        // but GBK text rarely is valid Shift_JIS.
        private fun detectEncoding(bytes: ByteArray): String =
            if (!canDecode(bytes, "Shift_JIS") && canDecode(bytes, "GBK")) "gbk" else "sjis"

        private fun canDecode(bytes: ByteArray, charsetName: String): Boolean {
            return try {
                val decoder = Charset.forName(charsetName).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes))
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

// Reads string settings (`name = "value"`) from the .xsys4rc file in a game directory.
object Xsys4rc {
    private val regex = Regex("""\s*([\w-]+)\s*=\s*"([^"]*)"\s*(?:(?:;|//).*)?""")

    fun read(gameDir: File): Map<String, String> {
        return try {
            val file = File(gameDir, ".xsys4rc")
            if (!file.isFile) return emptyMap()
            // Setting names are ASCII; values and comments may use a legacy encoding.
            file.useLines(Charsets.ISO_8859_1) { lines ->
                lines.mapNotNull { regex.matchEntire(it) }
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }
        } catch (e: Exception) {
            Log.w("GameList", "Failed to read .xsys4rc", e)
            emptyMap()
        }
    }
}

object CustomGamePaths {
    private const val PREFS_NAME = "xsystem4_prefs"
    private const val KEY_CUSTOM_PATHS = "custom_game_paths"

    fun getCustomPaths(context: Context): List<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CUSTOM_PATHS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w("GameList", "Failed to parse custom game paths", e)
            emptyList()
        }
    }

    fun addCustomPath(context: Context, path: String): Boolean {
        val normalized = canonicalOrAbsolute(File(path))
        val current = getCustomPaths(context).toMutableList()
        if (current.any { canonicalOrAbsolute(File(it)) == normalized }) {
            return false
        }
        current.add(normalized)
        setCustomPaths(context, current)
        return true
    }

    fun removeCustomPath(context: Context, path: String): Boolean {
        val normalized = canonicalOrAbsolute(File(path))
        val current = getCustomPaths(context)
        val filtered = current.filter { canonicalOrAbsolute(File(it)) != normalized }
        if (filtered.size == current.size) {
            return false
        }
        setCustomPaths(context, filtered)
        return true
    }

    fun setCustomPaths(context: Context, paths: List<String>) {
        try {
            val arr = JSONArray()
            val seen = HashSet<String>()
            for (p in paths) {
                val normalized = canonicalOrAbsolute(File(p))
                if (seen.add(normalized)) {
                    arr.put(normalized)
                }
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_PATHS, arr.toString())
                .apply()
        } catch (e: Exception) {
            Log.w("GameList", "Failed to save custom game paths", e)
        }
    }

    fun canonicalOrAbsolute(file: File): String {
        return try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }
    }
}

class GameList(activity: Activity, allowStorageAccess: Boolean = true) {
    companion object {
        fun hasAllFilesPermission(context: Context): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.w("GameList", "Failed to check storage permission", e)
                false
            }
        }

        fun hasIniFile(dir: File): Boolean = Item.findIniFile(dir) != null

        fun isValidGameOrParentDirectory(dir: File): Boolean {
            return try {
                if (hasIniFile(dir)) return true
                dir.listFiles()?.any {
                    !it.name.startsWith(".") && it.isDirectory && hasIniFile(it)
                } ?: false
            } catch (e: Exception) {
                Log.w("GameList", "Failed to validate directory ${dir.path}", e)
                false
            }
        }

        private fun listDirectory(dir: File): Array<File>? {
            return try {
                if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
                    null
                } else {
                    dir.listFiles()
                }
            } catch (e: Exception) {
                Log.w("GameList", "Failed to list directory ${dir.path}", e)
                null
            }
        }
    }

    private val context: Context = activity.applicationContext
    private val items: ArrayList<Item> = arrayListOf()
    private val seenCanonicalPaths = HashSet<String>()

    operator fun get(index: Int): Item = items[index]
    val size: Int get() = items.size

    private fun addDirectoryItem(
        dir: File,
        homedir: File,
        isDirectExternal: Boolean = false,
        customPathRoot: String? = null
    ) {
        try {
            val canonical = CustomGamePaths.canonicalOrAbsolute(dir)
            if (!seenCanonicalPaths.add(canonical)) {
                return
            }
            items.add(Item.fromDirectory(dir, homedir, context, isDirectExternal, customPathRoot))
        } catch (e: Exception) {
            Log.w("GameList", "Failed to add game directory ${dir.path}", e)
        }
    }

    private fun scanGameChildren(
        parent: File,
        homedir: File,
        isDirectExternal: Boolean = false,
        customPathRoot: String? = null
    ) {
        val children = listDirectory(parent) ?: return
        for (child in children.sortedBy { it.name.lowercase() }) {
            try {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory && hasIniFile(child)) {
                    addDirectoryItem(child, homedir, isDirectExternal, customPathRoot)
                }
            } catch (e: Exception) {
                Log.w("GameList", "Failed to inspect ${child.path}", e)
            }
        }
    }

    private fun scanExternalFilesDirs(activity: Activity) {
        val dirs = try {
            activity.getExternalFilesDirs(null).filterNotNull()
        } catch (e: Exception) {
            Log.w("GameList", "Failed to get external files directories", e)
            emptyList()
        }
        for (storagePath in dirs) {
            try {
                val state = Environment.getExternalStorageState(storagePath)
                Log.i("GameList", "${storagePath}: $state")
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                    continue
                }
                val homedir = File(storagePath, ".xsystem4")
                scanGameChildren(storagePath, homedir)

                val files = listDirectory(storagePath)
                if (state == Environment.MEDIA_MOUNTED && files != null && files.isEmpty()) {
                    // Create a dummy file and do a media scan so that `storagePath` is discoverable via MTP.
                    // https://issuetracker.google.com/issues/37071807
                    val dummyFile = File(storagePath, "dummy.txt")
                    dummyFile.writeText("Create a subfolder here that stores game files.")
                    MediaScannerConnection.scanFile(
                        activity,
                        arrayOf(dummyFile.absolutePath),
                        null,
                        null
                    )
                }
            } catch (e: Exception) {
                Log.w("GameList", "Failed to scan external files directory ${storagePath.path}", e)
            }
        }
    }

    @Suppress("DEPRECATION") // getExternalStorageDirectory only has a scoped-storage replacement on
                             // API 29+, and this app is granted MANAGE_EXTERNAL_STORAGE.
    private fun scanSharedStorage() {
        val root = try {
            Environment.getExternalStorageDirectory()
        } catch (e: Exception) {
            Log.w("GameList", "Failed to get external storage directory", e)
            return
        }
        if (root == null) return
        val homedir = File(root, ".xsystem4")

        // /storage/emulated/0/<game>
        scanGameChildren(root, homedir, isDirectExternal = true)
        // /storage/emulated/0/Download/<game>
        scanGameChildren(File(root, "Download"), homedir, isDirectExternal = true)
        // /storage/emulated/0/xsystem4/<game>
        scanGameChildren(File(root, "xsystem4"), homedir, isDirectExternal = true)
    }

    private fun scanCustomPaths() {
        for (customPath in CustomGamePaths.getCustomPaths(context)) {
            try {
                val dir = File(customPath)
                if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
                    continue
                }
                if (hasIniFile(dir)) {
                    val homedir = File(dir.parentFile ?: dir, ".xsystem4")
                    addDirectoryItem(dir, homedir, isDirectExternal = true, customPathRoot = customPath)
                } else {
                    val homedir = File(dir, ".xsystem4")
                    scanGameChildren(dir, homedir, isDirectExternal = true, customPathRoot = customPath)
                }
            } catch (e: Exception) {
                Log.w("GameList", "Failed to scan custom path $customPath", e)
            }
        }
    }

    init {
        if (allowStorageAccess && hasAllFilesPermission(activity)) {
            scanCustomPaths()
            scanSharedStorage()
            scanExternalFilesDirs(activity)
            moveSaveDirectories()
        }
    }

    fun uninstall(item: Item) {
        try {
            if (!item.path.deleteRecursively()) {
                Log.w("GameList", "Failed to delete ${item.path.path}")
            }
        } catch (e: Exception) {
            Log.w("GameList", "Failed to delete ${item.path.path}", e)
        }
        items.remove(item)
    }

    // Move save directories to the new location.
    // TODO: Remove this after some transition period.
    private fun moveSaveDirectories() {
        for (item in items) {
            if (item.error != null) continue
            try {
                val oldPath = File(item.homedir, item.name)
                val files = oldPath.listFiles() ?: continue
                for (file in files) {
                    val newPath = File(item.path, file.name)
                    Log.i("GameList", "Moving save directory: ${file} -> ${newPath}")
                    if (newPath.exists()) {
                        Log.w("GameList", "Removing existing save directory: ${newPath}")
                        newPath.deleteRecursively()
                    }
                    file.renameTo(newPath)
                }
                oldPath.deleteRecursively()
            } catch (e: Exception) {
                Log.w("GameList", "Failed to move save directory for ${item.name}", e)
            }
        }
    }
}
