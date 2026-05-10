package com.geode.launcher.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Handles extraction of bundled Geode assets (the .so and resources)
 * that are shipped inside the APK instead of downloaded at runtime.
 *
 * Asset layout inside the APK:
 *   assets/bootstrap/Geode.android64.so   – the loader binary
 *   assets/bootstrap/Geode.android32.so   – 32-bit variant (optional)
 *   assets/bootstrap/resources.zip        – geode.loader resource pack
 *
 * Files are extracted to the same paths that ReleaseManager would produce,
 * so the rest of the launcher works without modification.
 */
object BootstrapUtils {
    private const val TAG = "GeodeBootstrap"
    private const val BOOTSTRAP_DIR = "bootstrap"
    private const val PREFS_NAME = "bootstrap_prefs"
    private const val KEY_EXTRACTED_VERSION = "extracted_version"

    // Bump this whenever the bundled assets change so they get re-extracted.
    private const val BUNDLE_VERSION = "4.10.2-bundled"

    fun extractIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val extractedVersion = prefs.getString(KEY_EXTRACTED_VERSION, null)

        if (extractedVersion == BUNDLE_VERSION &&
            LaunchUtils.isGeodeInstalled(context) &&
            areResourcesExtracted(context)
        ) {
            Log.d(TAG, "Bundled assets already extracted at version $BUNDLE_VERSION, skipping.")
            return
        }

        Log.i(TAG, "Extracting bundled Geode assets (version $BUNDLE_VERSION)…")

        try {
            extractSo(context)
            extractResources(context)

            prefs.edit().putString(KEY_EXTRACTED_VERSION, BUNDLE_VERSION).apply()
            Log.i(TAG, "Bundled assets extracted successfully.")
        } catch (e: Exception) {
            // Non-fatal: launcher will fall back to the normal network download path.
            Log.e(TAG, "Failed to extract bundled assets, will rely on network download.", e)
        }
    }

    // ── .so extraction ───────────────────────────────────────────────────────

    private fun extractSo(context: Context) {
        val assetName = "$BOOTSTRAP_DIR/${LaunchUtils.geodeFilename}"

        val assets = context.assets
        val available = assets.list(BOOTSTRAP_DIR) ?: emptyArray()
        if (!available.contains(LaunchUtils.geodeFilename)) {
            Log.w(TAG, "No bundled .so found for ${LaunchUtils.geodeFilename}, skipping .so extraction.")
            return
        }

        // Destination: externalMediaDirs[0]/Geode.android64.so
        // (same path that getGeodeOutputPath() in ReleaseManager uses)
        val baseDir = LaunchUtils.getBaseDirectory(context)
        val destFile = File(baseDir, LaunchUtils.geodeFilename)
        destFile.parentFile?.mkdirs()

        Log.d(TAG, "Extracting $assetName → ${destFile.absolutePath}")

        assets.open(assetName).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 128 * 1024)
            }
        }

        Log.i(TAG, "Extracted ${destFile.length()} bytes → ${destFile.name}")
    }

    // ── resources extraction ──────────────────────────────────────────────────

    private fun areResourcesExtracted(context: Context): Boolean {
        val resourcesDir = File(LaunchUtils.getBaseDirectory(context), "game/geode/resources/geode.loader/")
        return resourcesDir.exists() && (resourcesDir.listFiles()?.isNotEmpty() == true)
    }

    private fun extractResources(context: Context) {
        val assetName = "$BOOTSTRAP_DIR/resources.zip"

        val assets = context.assets
        val available = assets.list(BOOTSTRAP_DIR) ?: emptyArray()
        if (!available.contains("resources.zip")) {
            Log.w(TAG, "No bundled resources.zip found, skipping resources extraction.")
            return
        }

        val destDir = File(LaunchUtils.getBaseDirectory(context), "game/geode/resources/geode.loader/")
        Log.d(TAG, "Extracting $assetName → ${destDir.absolutePath}")

        // Remove stale resources before extraction
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        destDir.mkdirs()

        assets.open(assetName).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            zip.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        Log.i(TAG, "Resources extracted to ${destDir.absolutePath}")
    }
}
