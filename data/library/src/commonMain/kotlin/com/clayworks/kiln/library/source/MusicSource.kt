// MusicSource — the Source Protocol contract. Per spec §3.3:
// "No source-specific branching anywhere in the codebase — if
// `if (source is XxxSource)` appears, the interface is wrong."

package com.clayworks.kiln.library.source

import arrow.core.Either
import kotlinx.coroutines.flow.Flow

interface MusicSource {
    val id: SourceId
    val displayName: String
    val capabilities: SourceCapabilities

    suspend fun search(query: String, limit: Int = 50): Flow<SearchResult>

    suspend fun browse(scope: BrowseScope): Flow<MediaItem>

    suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable>

    /**
     * Hint to the source that we want the most up-to-date metadata.
     * Local sources may trigger a rescan; network sources may bypass cache.
     */
    suspend fun refresh(): Either<SourceError, Unit> = Either.Right(Unit)
}
