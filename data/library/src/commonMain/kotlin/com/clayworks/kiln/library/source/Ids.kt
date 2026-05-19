// Value-class IDs for the Source Protocol. Compile-time type safety without
// runtime cost. Per spec §3.3 + Bus-Factor-of-One pattern (passing the wrong
// ID type to the wrong function should be a compile error).

package com.clayworks.kiln.library.source

@JvmInline
value class SourceId(val value: String)

@JvmInline
value class ItemId(val value: String)

@JvmInline
value class AlbumId(val value: Long)

@JvmInline
value class ArtistId(val value: Long)

@JvmInline
value class PlaylistId(val value: Long)

@JvmInline
value class TrackId(val value: Long)
