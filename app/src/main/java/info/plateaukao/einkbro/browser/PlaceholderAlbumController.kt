package info.plateaukao.einkbro.browser

import android.os.Bundle
import info.plateaukao.einkbro.view.Album

/**
 * Lightweight stand-in for a tab whose EBWebView has not been created yet
 * (lazy restore) or has been hibernated. Holds everything TabManager needs to
 * materialize a real EBWebView when the tab is first activated.
 */
class PlaceholderAlbumController(
    title: String,
    url: String,
    val incognito: Boolean = false,
    /** In-memory back/forward history captured by WebView.saveState at hibernation. */
    val savedState: Bundle? = null,
    albumCallback: AlbumCallback? = null,
    existingAlbum: Album? = null,
) : AlbumController {

    // Hibernation adopts the live tab's Album so the tab keeps its Compose
    // identity, title, and favicon; lazy restore starts a fresh one.
    override val album: Album =
        existingAlbum?.also { it.updateAlbumController(this) } ?: Album(this, albumCallback)

    override var albumTitle: String
        get() = album.albumTitle
        set(value) {
            album.albumTitle = value
        }

    override val albumUrl: String get() = initAlbumUrl

    override var initAlbumUrl: String = url

    // Translate/AI tabs are never placeholder-ized: they cannot be rebuilt from a URL.
    override var isTranslatePage: Boolean = false
    override var isAIPage: Boolean = false

    // showAlbum materializes a real EBWebView before activating; these exist
    // only so a placeholder is safe to touch through the interface.
    override fun activate() = album.activate()

    override fun deactivate() = album.deactivate()

    override fun pauseWebView() = Unit

    override fun resumeWebView() = Unit

    init {
        if (existingAlbum == null) {
            album.albumTitle = title
        }
        album.isLoaded = false
    }
}
