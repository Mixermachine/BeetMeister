package de.aarondietz.beetmeister.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BeetLogExporter {
    fun createLogEmailIntent(context: Context): Intent? {
        val logDir = File(context.cacheDir, "logs")
        if (!logDir.exists() || !logDir.isDirectory) {
            return null
        }

        val logFiles = logDir.listFiles { file ->
            file.isFile && (file.name == "beet.log" || file.name.matches(Regex("beet\\.\\d+\\.log")))
        } ?: emptyArray()

        if (logFiles.isEmpty()) {
            return null
        }

        val zipFile = File(logDir, "beet-logs.zip")
        if (zipFile.exists()) {
            zipFile.delete()
        }

        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (file in logFiles) {
                    FileInputStream(file).use { fis ->
                        val entry = ZipEntry(file.name)
                        zos.putNextEntry(entry)
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            BeetLog.e("BeetLogExporter", "Failed to zip log files", e)
            return null
        }

        val authority = "${context.packageName}.fileprovider"
        val zipUri = try {
            FileProvider.getUriForFile(context, authority, zipFile)
        } catch (e: Exception) {
            BeetLog.e("BeetLogExporter", "Failed to get FileProvider URI", e)
            return null
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val formattedDate = dateFormat.format(Date())
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }

        val subject = "BeetMeister Logs - $formattedDate - ${Build.MODEL} - v$versionName"

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("info@aarondietz.de"))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, zipUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
