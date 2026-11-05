package net.hawyn.ablefisp

import android.net.Uri

/**
 * A completed output in the host's private storage, exposed through the feature provider.
 * Each generation has a different URI; later edits never overwrite an earlier result.
 * The host owns retention and may copy the URIs using its ContentResolver.
 * Grant read URI permission explicitly when forwarding either URI to another app.
 */
data class AblEfispOutput(
    val imageUri: Uri,
    val reportUri: Uri?,
    val sha256: String,
    val size: Long,
    val source: ImageSource?,
    val sessionId: String,
    val fileName: String = "efisp.img",
)
