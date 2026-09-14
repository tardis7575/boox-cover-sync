package tw.mustp.booxcoversync.image

import java.io.IOException

sealed class ImageProcessingException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    class InvalidImage(message: String, cause: Throwable? = null) :
        ImageProcessingException(message, cause)

    class ImageTooLarge(message: String, cause: Throwable? = null) :
        ImageProcessingException(message, cause)

    class WriteFailed(message: String, cause: Throwable? = null) :
        ImageProcessingException(message, cause)
}
