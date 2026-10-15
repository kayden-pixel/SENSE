package app.sense.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

internal object SenseHttp {
    val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
}

/** Keeps cancellation attached until the response body is consumed, not just its headers. */
internal suspend fun <T> Call.awaitBody(read: (Response) -> T): T =
    suspendCancellableCoroutine { c ->
        c.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (c.isActive) c.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(read)
                        if (c.isActive) c.resume(result)
                    } catch (e: Exception) {
                        if (c.isActive) c.resumeWithException(e)
                    }
                }
            }
        )
    }
