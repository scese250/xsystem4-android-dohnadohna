package io.github.kichikuou.xsystem4

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.WindowManager
import org.libsdl.app.SDLActivity
import java.io.File

// Intent for this activity must have the following extras:
// - EXTRA_GAME_ROOT (string): A path to the game installation.
// - EXTRA_SAVE_DIR (string): A path to a directory where save files are stored.
// - EXTRA_ENCODING (string): The game's encoding name for the --encoding option.
class XSystem4Activity : SDLActivity() {
    companion object {
        const val EXTRA_GAME_ROOT = "GAME_ROOT"
        const val EXTRA_SAVE_DIR = "SAVE_DIR"
        const val EXTRA_ENCODING = "ENCODING"
        const val COMMAND_OPEN_PLAYING_MANUAL = 0x8000  // xsystem4/src/hll/SystemService.c
    }

    private var activeMessageDialog: DohnaConfirmDialog? = null
    @Volatile
    private var activeMessageUnblock: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (!SDLActivity.mBrokenLibraries) {
            SDLActivity.nativeSetenv("SDL_ANDROID_TRAP_BACK_BUTTON", "1")
        }
        // Workaround for https://github.com/libsdl-org/SDL/issues/8995
        SDLActivity.setWindowStyle(true)
    }

    override fun getLibraries(): Array<String> {
        return arrayOf("SDL2", "xsystem4")
    }

    override fun getArguments(): Array<String> {
        val saveFolder = intent.getStringExtra(EXTRA_SAVE_DIR)!!
        val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
        val encoding = intent.getStringExtra(EXTRA_ENCODING)!!
        val args = mutableListOf("--save-folder", saveFolder, "--save-format=rsm", "--encoding", encoding)
        if (encoding.equals("gbk", ignoreCase = true)) {
            args += chineseFontArgs(Xsys4rc.read(File(gameRoot)))
        }
        args += gameRoot
        return args.toTypedArray()
    }

    // The bundled fonts lack many simplified Chinese characters, so use the system fonts unless
    // the game's .xsys4rc specifies fonts. ":2" selects the simplified Chinese face in the Noto
    // CJK font collections.
    private fun chineseFontArgs(rc: Map<String, String>): List<String> {
        val fontDir = File(Environment.getRootDirectory(), "fonts")
        fun findFont(vararg candidates: String): String? =
            candidates.firstOrNull { File(fontDir, it.substringBefore(':')).isFile }
                ?.let { File(fontDir, it).path }

        val args = mutableListOf<String>()
        if ("font-gothic" !in rc) {
            findFont(
                "NotoSansCJK-Regular.ttc:2",  // Android 7.0+
                "NotoSansSC-Regular.otf",     // Android 6.0
                "NotoSansHans-Regular.otf",   // Android 5.x
            )?.let { args += listOf("--font-gothic", it) }
        }
        if ("font-mincho" !in rc) {
            // Android 9+. Otherwise the bundled HanaMinA, which covers simplified Chinese (but in Japanese shapes).
            findFont("NotoSerifCJK-Regular.ttc:2")?.let { args += listOf("--font-mincho", it) }
        }
        return args
    }

    override fun messageboxShowMessageBox(
        flags: Int,
        title: String?,
        message: String?,
        buttonFlags: IntArray?,
        buttonIds: IntArray?,
        buttonTexts: Array<String?>?,
        colors: IntArray?
    ): Int {
        val safeFlags = buttonFlags ?: IntArray(0)
        val safeIds = buttonIds ?: IntArray(0)
        val safeTexts = buttonTexts ?: emptyArray()
        if (safeFlags.size != safeIds.size || safeIds.size != safeTexts.size) {
            return -1
        }
        if (isFinishing || isDestroyed) {
            return -1
        }

        val lock = Object()
        val result = IntArray(1) { -1 }
        var done = false
        val unblock = {
            synchronized(lock) {
                done = true
                lock.notifyAll()
            }
        }
        activeMessageUnblock = unblock

        val buttons = ArrayList<DohnaConfirmDialog.ButtonSpec>(safeIds.size)
        for (i in safeIds.indices) {
            buttons.add(
                DohnaConfirmDialog.ButtonSpec(
                    id = safeIds[i],
                    text = safeTexts[i] ?: "",
                    flags = safeFlags[i]
                )
            )
        }

        runOnUiThread {
            try {
                if (isFinishing || isDestroyed) {
                    activeMessageUnblock = null
                    unblock()
                    return@runOnUiThread
                }

                val dialog = DohnaConfirmDialog.fromSdlMessageBox(
                    activity = this,
                    sdlFlags = flags,
                    title = title,
                    message = message ?: "",
                    buttons = buttons,
                    onResult = { selectedId ->
                        synchronized(lock) {
                            result[0] = selectedId
                        }
                    }
                )
                activeMessageDialog = dialog
                dialog.setOnDismissListener {
                    if (activeMessageDialog === dialog) {
                        activeMessageDialog = null
                    }
                    if (activeMessageUnblock === unblock) {
                        activeMessageUnblock = null
                    }
                    restoreImmersiveWindow()
                    unblock()
                }
                dialog.show()
            } catch (e: Exception) {
                activeMessageDialog = null
                if (activeMessageUnblock === unblock) {
                    activeMessageUnblock = null
                }
                synchronized(lock) {
                    result[0] = -1
                }
                unblock()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return result[0]
        }

        synchronized(lock) {
            while (!done) {
                try {
                    lock.wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (!isFinishing && !isDestroyed) {
            SDLActivity.setWindowStyle(true)
        }
        return result[0]
    }

    @Suppress("DEPRECATION")
    private fun restoreImmersiveWindow() {
        if (isFinishing || isDestroyed) return
        val decor = window?.decorView ?: return
        decor.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.INVISIBLE
            )
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        SDLActivity.mFullscreenModeActive = true
    }

    override fun onDestroy() {
        try {
            activeMessageDialog?.let { dialog ->
                activeMessageDialog = null
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
            activeMessageUnblock?.invoke()
            activeMessageUnblock = null
            // Let SDL stop and join its native thread before terminating the game process.
            super.onDestroy()
        } finally {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onUnhandledMessage(command: Int, param: Any): Boolean {
        when (command) {
            COMMAND_OPEN_PLAYING_MANUAL -> {
                val gameRoot = intent.getStringExtra(EXTRA_GAME_ROOT)!!
                val manualDir = File(gameRoot, "Manual")
                if (manualDir.isDirectory) {
                    val intent = android.content.Intent(this, ManualActivity::class.java).apply {
                        val url = "file://${manualDir.absolutePath}/index.html"
                        putExtra(ManualActivity.EXTRA_URL, url)
                    }
                    startActivity(intent)
                }
                return true
            }
        }
        return super.onUnhandledMessage(command, param)
    }
}
