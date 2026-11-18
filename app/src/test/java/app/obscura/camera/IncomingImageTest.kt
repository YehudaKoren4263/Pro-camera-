package app.obscura.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingImageTest {
    private val pkg = "app.obscura.camera"
    private fun ok(s: String?, a: String?, t: String?) = IncomingImage.acceptable(s, a, t, pkg)

    @Test fun galleryContentUriIsAccepted() {
        assertTrue(ok("content", "media", "image/jpeg"))
        assertTrue(ok("content", "com.miui.gallery.open", "image/heic"))
    }

    @Test fun fileUrisAreRefused() {
        // e.g. file:///data/data/app.obscura.camera/... : our own private files.
        assertFalse(ok("file", null, "image/jpeg"))
        assertFalse(ok("FILE", "", "image/png"))
    }

    @Test fun ourOwnProviderIsRefused() {
        assertFalse(ok("content", pkg, "image/jpeg"))
        assertFalse(ok("content", "$pkg.fileprovider", "image/jpeg"))
    }

    @Test fun nonImagesAndMissingDataAreRefused() {
        assertFalse(ok("content", "media", "application/zip"))
        assertFalse(ok("content", "media", null))
        assertFalse(ok(null, null, null))
        assertFalse(ok("http", "evil.example", "image/jpeg"))
    }
}
