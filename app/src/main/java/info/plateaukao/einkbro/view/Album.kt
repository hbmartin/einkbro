package info.plateaukao.einkbro.view

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import info.plateaukao.einkbro.browser.AlbumCallback
import info.plateaukao.einkbro.browser.AlbumController

class Album(
    private var albumController: AlbumController,
    private var albumCallback: AlbumCallback?
) {
    // Stable identity for Compose lazy keys; the album outlives its controller
    // (a hibernated tab swaps its WebView for a placeholder and back).
    val id: Int = nextAlbumId++

    var isLoaded = false

    var isTranslatePage = false

    var albumTitle: String by mutableStateOf("")

    var bitmap: Bitmap? by mutableStateOf(null)

    var isActivated = false

    fun showOrJumpToTop() {
        val callback = albumCallback ?: return
        if (callback.isCurrentAlbum(albumController)) {
            if (callback.isAtTop()) {
                callback.refreshAction()
            } else {
                callback.jumpToTop()
            }
        } else {
            callback.showAlbum(albumController)
        }
    }

    fun remove(showHomePage: Boolean = false) {
        albumCallback?.removeAlbum(albumController, showHomePage)
    }

    fun getUrl(): String = albumController.albumUrl

    fun updateAlbumController(controller: AlbumController) {
        albumController = controller
    }

    fun setAlbumCover(bitmap: Bitmap?) {
        this.bitmap = bitmap
    }

    fun activate() {
        isActivated = true
    }

    fun deactivate() {
        isActivated = false
    }

    companion object {
        private var nextAlbumId = 0
    }
}