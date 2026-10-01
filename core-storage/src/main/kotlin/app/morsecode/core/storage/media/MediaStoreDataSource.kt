package app.morsecode.core.storage.media

import app.morsecode.core.model.MediaItem

/** The MediaStore queries consumed by [app.morsecode.core.storage.DefaultMediaRepository]. */
internal interface MediaStoreDataSource {
    fun images(): List<MediaItem>

    fun videos(): List<MediaItem>

    fun audio(): List<MediaItem>

    fun documents(): List<MediaItem>

    fun byId(id: String): MediaItem?
}
