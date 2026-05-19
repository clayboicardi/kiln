// LocalLibrarySource — the MVP MusicSource implementation, backed by SQLDelight.
// Per scaffold prep §3. Library scanner is a separate concern (LibraryScanner
// interface) — this class only reads from the indexed database.

package com.clayworks.kiln.library.source

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import arrow.core.Either
import com.clayworks.kiln.data.library.db.KilnDatabase
import com.clayworks.kiln.library.source.internal.sanitizeFtsQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform

class LocalLibrarySource(
    private val db: KilnDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : MusicSource {
    override val id = SourceId("local")
    override val displayName = "Local Library"
    override val capabilities = LocalSourceCapabilities

    override suspend fun search(query: String, limit: Int): Flow<SearchResult> {
        val ftsQuery = sanitizeFtsQuery(query)
        return db.track_searchQueries
            .searchTracks(ftsQuery, limit.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .transform { rows -> rows.forEach { emit(it.toSearchResult()) } }
    }

    override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = when (scope) {
        is BrowseScope.AllTracks -> db.trackQueries
            .selectAll(scope.pageSize.toLong(), scope.pageOffset.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .transform { rows -> rows.forEach { emit(it.toMediaItem()) } }

        is BrowseScope.TracksOfAlbum -> db.trackQueries
            .selectByAlbum(scope.albumId.value)
            .asFlow()
            .mapToList(ioDispatcher)
            .transform { rows -> rows.forEach { emit(it.toMediaItem()) } }

        is BrowseScope.TracksOfArtist -> db.trackQueries
            .selectByArtist(scope.artistId.value, scope.pageSize.toLong(), scope.pageOffset.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .transform { rows -> rows.forEach { emit(it.toMediaItem()) } }

        is BrowseScope.RecentlyAdded -> db.trackQueries
            .selectRecentlyAdded(scope.pageSize.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .transform { rows -> rows.forEach { emit(it.toMediaItem()) } }

        is BrowseScope.RecentlyPlayed -> db.trackQueries
            .selectRecentlyPlayed(scope.pageSize.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .transform { rows -> rows.forEach { emit(it.toMediaItem()) } }

        is BrowseScope.MostPlayed -> db.trackQueries
            .selectMostPlayed(scope.pageSize.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .transform { rows -> rows.forEach { emit(it.toMediaItem()) } }

        // TODO MVP Session 4-7 follow-up: AllAlbums (needs Album → MediaItem mapper),
        // AllArtists (needs Artist → MediaItem mapper), AllPlaylists, AlbumsOfArtist,
        // TracksOfPlaylist. For now return empty flows so the BrowseScope sealed
        // interface is exhaustively matched (compile-time checked).
        is BrowseScope.AllAlbums,
        is BrowseScope.AllArtists,
        is BrowseScope.AllPlaylists,
        is BrowseScope.AlbumsOfArtist,
        is BrowseScope.TracksOfPlaylist -> emptyFlow()
    }

    override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
        Either.catch {
            val trackId = itemId.value.toLongOrNull()
                ?: return Either.Left(SourceError.ItemNotFound(itemId))
            val track = db.trackQueries.selectById(trackId).executeAsOneOrNull()
                ?: return Either.Left(SourceError.ItemNotFound(itemId))
            track.toPlayable(sourceId = id)
        }.mapLeft { SourceError.IoError(it) }

    // refresh() defaulted to Either.Right(Unit) from interface. Scanner integration
    // lands at MVP Session 4-7 follow-up (LibraryScanner interface + Android
    // MediaStore + JVM filesystem walker impls).
}

