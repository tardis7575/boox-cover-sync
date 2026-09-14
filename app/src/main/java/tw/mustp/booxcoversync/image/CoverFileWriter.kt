package tw.mustp.booxcoversync.image

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes the current cover via a same-directory temporary file and rename.
 *
 * Public Pictures is attempted first because BOOX's system receiver needs to
 * read the absolute path. If Android 11 scoped storage rejects that location,
 * the app-specific external Pictures directory is used as an app-writable
 * fallback; callers must broadcast the returned file's absolute path.
 */
object CoverFileWriter {

    private const val DIRECTORY_NAME = "BookCover"
    private const val LEGACY_FILE_NAME = "current.jpg"
    private const val SLOT_A_FILE_NAME = "current-a.jpg"
    private const val SLOT_B_FILE_NAME = "current-b.jpg"
    private const val JPEG_QUALITY = 90
    private val sequence = AtomicLong()
    private val lastTargetByDirectory = mutableMapOf<String, String>()

    @JvmStatic
    @Synchronized
    @Throws(ImageProcessingException::class)
    fun writeJpegAtomically(context: Context, bitmap: android.graphics.Bitmap): File {
        val errors = mutableListOf<Throwable>()
        val candidates = listOfNotNull(
            publicPicturesDirectory(),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            context.filesDir.resolve(Environment.DIRECTORY_PICTURES),
        )

        for (base in candidates.distinctBy { it.absolutePath }) {
            try {
                return writeToDirectory(base, bitmap)
            } catch (error: IOException) {
                errors += error
            } catch (error: SecurityException) {
                errors += error
            }
        }

        throw ImageProcessingException.WriteFailed(
            "Unable to write cover to Pictures/BookCover",
            errors.lastOrNull(),
        )
    }

    private fun publicPicturesDirectory(): File? = try {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    } catch (_: RuntimeException) {
        null
    }

    private fun writeToDirectory(base: File, bitmap: android.graphics.Bitmap): File {
        val directory = base.resolve(DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create cover directory: ${directory.absolutePath}")
        }

        val target = chooseTarget(directory)
        val temporary = directory.resolve(
            ".${target.name}.tmp-${android.os.Process.myPid()}-${sequence.incrementAndGet()}",
        )
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IOException("Bitmap compression failed")
                }
                output.fd.sync()
            }
            atomicReplace(temporary, target)
            lastTargetByDirectory[directory.absolutePath] = target.name

            // Older builds used current.jpg. Remove only that app-owned legacy
            // name after the new slot is safely in place, so generated files
            // remain bounded and BOOX receives a cache-busting path.
            directory.resolve(LEGACY_FILE_NAME).delete()
            cleanupTemporaryFiles(directory)
            return target
        } catch (error: IOException) {
            temporary.delete()
            throw error
        } catch (error: RuntimeException) {
            temporary.delete()
            throw IOException("Unable to write cover image", error)
        }
    }

    private fun chooseTarget(directory: File): File {
        val slotA = directory.resolve(SLOT_A_FILE_NAME)
        val slotB = directory.resolve(SLOT_B_FILE_NAME)
        val previous = lastTargetByDirectory[directory.absolutePath]

        // Fill a missing slot first. Once both exist, always choose the slot
        // opposite the last successful path so consecutive broadcasts differ.
        if (!slotA.exists()) return slotA
        if (!slotB.exists()) return slotB
        return when (previous) {
            SLOT_A_FILE_NAME -> slotB
            SLOT_B_FILE_NAME -> slotA
            else -> if (slotA.lastModified() <= slotB.lastModified()) slotA else slotB
        }
    }

    private fun cleanupTemporaryFiles(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile && file.name.startsWith(".current-") && file.name.contains(".tmp-")
            }
            .forEach { it.delete() }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (_: UnsupportedOperationException) {
            // Android filesystems may not expose ATOMIC_MOVE through NIO.
        } catch (_: IOException) {
            // Fall back to java.io.File.renameTo below.
        }

        if (!source.renameTo(target)) {
            throw IOException("Unable to atomically replace ${target.absolutePath}")
        }
    }
}
