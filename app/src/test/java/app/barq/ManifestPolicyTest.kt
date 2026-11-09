package app.barq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the Play-policy facts about the manifest so a later edit cannot quietly undo them. */
class ManifestPolicyTest {
    private val manifest: String = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        .map { File(it) }.first { it.exists() }.readText()

    private fun permissions(): Set<String> =
        Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1].substringAfterLast('.') }.toSet()

    @Test fun noBlanketPackageVisibilityOrStorageAccess() {
        val p = permissions()
        assertFalse("QUERY_ALL_PACKAGES needs a Play exception; <queries> is used instead", "QUERY_ALL_PACKAGES" in p)
        assertFalse("MANAGE_EXTERNAL_STORAGE is not needed: files go through MediaStore", "MANAGE_EXTERNAL_STORAGE" in p)
        assertFalse("WRITE_EXTERNAL_STORAGE is not needed: files go through MediaStore", "WRITE_EXTERNAL_STORAGE" in p)
    }

    @Test fun launcherAppsAreDeclaredThroughQueries() {
        val q = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.groupValues?.get(1)
        assertTrue("a <queries> block is required", q != null)
        assertTrue(q!!.contains("android.intent.action.MAIN") && q.contains("android.intent.category.LAUNCHER"))
    }

    @Test fun transferServiceDeclaresBothTypesAndTheirPermissions() {
        val service = Regex("<service[^>]*TransferService[^>]*/>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value ?: error("TransferService not declared")
        assertTrue(service.contains("dataSync|connectedDevice"))
        assertTrue(service.contains("android:exported=\"false\""))
        val p = permissions()
        assertTrue("FOREGROUND_SERVICE_DATA_SYNC" in p && "FOREGROUND_SERVICE_CONNECTED_DEVICE" in p)
    }

    @Test fun installResultReceiverIsNotExported() {
        val r = Regex("<receiver[^>]*InstallReceiver[^>]*/>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value ?: error("InstallReceiver not declared")
        assertTrue(r.contains("android:exported=\"false\""))
    }
}
