// JNA Structure mirroring libFLAC's FLAC__StreamMetadata_StreamInfo
// (FLAC/format.h). Used by the metadata callback to read STREAMINFO fields.
//
// JNA computes the struct's field offsets + total size automatically based on
// the field-type sequence (declared in getFieldOrder()). C alignment rules
// place 4 bytes of padding between bits_per_sample (offset 24) and the uint64
// total_samples (offset 32) — JNA handles this automatically.

package com.clayworks.kiln.audio.playback.nativeio

import com.sun.jna.Pointer
import com.sun.jna.Structure

@Structure.FieldOrder(
    "min_blocksize",
    "max_blocksize",
    "min_framesize",
    "max_framesize",
    "sample_rate",
    "channels",
    "bits_per_sample",
    "total_samples",
    "md5sum",
)
internal open class FlacStreamMetadataStreamInfo : Structure {
    @JvmField var min_blocksize: Int = 0
    @JvmField var max_blocksize: Int = 0
    @JvmField var min_framesize: Int = 0
    @JvmField var max_framesize: Int = 0
    @JvmField var sample_rate: Int = 0
    @JvmField var channels: Int = 0
    @JvmField var bits_per_sample: Int = 0
    @JvmField var total_samples: Long = 0
    @JvmField var md5sum: ByteArray = ByteArray(16)

    constructor() : super()
    constructor(p: Pointer) : super(p) {
        read()
    }
}
