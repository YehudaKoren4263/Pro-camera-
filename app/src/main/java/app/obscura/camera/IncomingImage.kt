package app.obscura.camera

/**
 * Which images other apps may hand us to edit ("Edit with Obscura").
 *
 * Only `content://` URIs from another app's provider are accepted. A `file://` URI, or one
 * pointing at our own provider, would make us open files with *our* permissions (our private
 * storage) on another app's behalf, and saving would copy them into the public gallery: the
 * classic confused-deputy hole. The type must be an image.
 */
object IncomingImage {
    fun acceptable(scheme: String?, authority: String?, mimeType: String?, ownPackage: String): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        val a = authority?.lowercase() ?: return false
        if (a.isBlank() || a == ownPackage.lowercase() || a.startsWith(ownPackage.lowercase() + ".")) return false
        return mimeType != null && mimeType.lowercase().startsWith("image/")
    }
}
