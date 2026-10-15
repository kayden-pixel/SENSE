package app.sense

import app.sense.data.awaitBody
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

class HttpCancellationTest {
    @Test
    fun cancellationRemainsAttachedWhileResponseBodyIsStalled() = runBlocking {
        val server = ServerSocket(0)
        val headers = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val worker =
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        val crlf = 13.toChar().toString() + 10.toChar()
                        socket
                            .getOutputStream()
                            .write(
                                ("HTTP/1.1 200 OK" +
                                        crlf +
                                        "Content-Length: 100" +
                                        crlf +
                                        crlf +
                                        "x")
                                    .toByteArray()
                            )
                        socket.getOutputStream().flush()
                        headers.countDown()
                        while (socket.getInputStream().read() != -1) {}
                    }
                } finally {
                    finished.countDown()
                }
            }
        try {
            val call =
                client.newCall(
                    Request.Builder().url("http://127.0.0.1:" + server.localPort).build()
                )
            val task =
                launch(Dispatchers.IO) {
                    call.awaitBody {
                        reading.countDown()
                        it.body!!.string()
                    }
                }
            assertTrue(withContext(Dispatchers.IO) { headers.await(3, TimeUnit.SECONDS) })
            assertTrue(withContext(Dispatchers.IO) { reading.await(3, TimeUnit.SECONDS) })
            withTimeout(2000) { task.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(withContext(Dispatchers.IO) { finished.await(2, TimeUnit.SECONDS) })
        } finally {
            server.close()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            worker.join(2000)
        }
    }
}
