package app.sense.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Single opaque model artifact. Archives and paths from a download are never extracted. */
object PackFiles {
    fun name(id: String, version: String, sha: String): String {
        require(
            id.matches(Regex("[a-z0-9_-]{1,64}")) &&
                version.matches(Regex("[a-zA-Z0-9._-]{1,32}")) &&
                sha.matches(Regex("[a-fA-F0-9]{64}"))
        )
        return id + "-" + version + "-" + sha.lowercase() + ".model"
    }

    fun verify(file: File, size: Long, sha: String): File {
        require(size in 1..1_000_000_000 && file.isFile && file.length() == size) {
            "Installed pack size mismatch"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        check(PackIntegrity.valid(sha, digest.digest().joinToString("") { "%02x".format(it) })) {
            "Pack integrity failure"
        }
        return file
    }

    fun commit(staging: File, destination: File, size: Long, sha: String): File {
        require(staging.canonicalFile.parentFile == destination.canonicalFile.parentFile) {
            "Pack staging must share destination filesystem"
        }
        verify(staging, size, sha)
        if (destination.exists()) {
            verify(destination, size, sha)
            staging.delete()
        } else Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return destination
    }
}
