package app.barq.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import app.barq.engine.ApkBundle
import java.io.IOException

/**
 * Installs a received .apk, or every APK inside a received .apks, through one PackageInstaller session.
 * Nothing is installed without the user: the system shows its own confirmation, and the "install unknown
 * apps" switch for this app must be on first.
 */
object ApkInstaller {
    const val ACTION_RESULT = "app.barq.INSTALL_RESULT"

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user allows this app to install other apps. */
    fun openAllowSourceSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Streams [uri] into a session and commits it. Blocking: call from a background thread. */
    fun install(context: Context, uri: Uri, name: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $name")
            input.use {
                if (name.endsWith(".apks", ignoreCase = true)) {
                    val n = ApkBundle.forEachApk(it) { entry, data -> write(session, entry, data) }
                    if (n == 0) throw IOException("no APK inside $name")
                } else {
                    write(session, "base.apk", it)
                }
            }
            val callback = Intent(ACTION_RESULT).setPackage(context.packageName)
            // The system fills in the status extras, so the PendingIntent has to be mutable.
            val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            runCatching { session.abandon() }
            throw e
        } finally {
            session.close()
        }
    }

    private fun write(session: PackageInstaller.Session, entry: String, data: java.io.InputStream) {
        session.openWrite(entry, 0, -1).use { out ->
            data.copyTo(out, 256 * 1024)
            session.fsync(out)
        }
    }
}
