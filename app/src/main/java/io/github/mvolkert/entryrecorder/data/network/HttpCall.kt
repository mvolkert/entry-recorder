package io.github.mvolkert.entryrecorder.data.network

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

private const val TAG = "HttpCall"

/**
 * Enqueues [request] on OkHttp's dispatcher and suspends instead of blocking a thread, so the calling
 * coroutine can actually abort it. `Call.execute()` parks a `Dispatchers.IO` thread and ignores
 * cancellation completely, which is why cancelling a recording download or a server probe kept the
 * socket transferring until the response finished on its own.
 *
 * The caller owns the returned [Response] and has to close it; prefer [withCancellableResponse] when the
 * whole body is consumed inside one block.
 */
suspend fun OkHttpClient.executeCancellable(request: Request): Response =
    newCall(request).awaitResponse()

/**
 * [executeCancellable] plus a guaranteed close, the suspending counterpart of `use { }`. Use it for
 * request/response pairs whose body is fully read inside [handler].
 *
 * Cancellation covers the body read as well as the connection: a blocking `InputStream.read` cannot
 * notice a cancelled coroutine by itself, so the caller's job aborts the transfer from the cancelling
 * thread, which is what makes OkHttp fail the in-flight read immediately instead of streaming bytes to
 * nobody.
 *
 * Callers that keep a `Result<T>` contract have to rethrow the [kotlinx.coroutines.CancellationException]
 * this can throw; folding it into `Result.failure` would turn "the user cancelled" into a request that
 * failed and could legitimately be retried.
 */
suspend fun <T> OkHttpClient.withCancellableResponse(
    request: Request,
    handler: suspend (Response) -> T,
): T {
    val call = newCall(request)
    val response = call.awaitResponse()
    val abortHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
    try {
        // Cover the case where cancellation landed while the response was being acquired: the caller must
        // see a CancellationException, not a request that quietly went ahead anyway.
        currentCoroutineContext().ensureActive()
        return handler(response)
    } finally {
        abortHook?.dispose()
        closeQuietly(response, request)
    }
}

/**
 * Awaits the response of an already-built [Call] without blocking a thread; cancelling the caller
 * cancels the call, and a call that is cancelled mid-transfer throws `IOException("Canceled")` on any
 * blocking body read, so the bytes stop arriving as soon as nobody wants them.
 */
suspend fun Call.awaitResponse(): Response {
    val delivered = CompletableDeferred<Response>()
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // `complete` is false once the waiter has gone, so a response that arrived too late is
                // closed here instead of leaking its connection back to the pool un-drained.
                if (!delivered.complete(response)) {
                    closeQuietly(response, call.request())
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                delivered.completeExceptionally(e)
            }
        }
    )
    return try {
        delivered.await()
    } catch (e: Throwable) {
        // Anything that leaves here without handing over a response — a cancelled caller, a dispatcher
        // rejection — has to stop the transfer rather than let it run to completion unwatched.
        cancel()
        throw e
    }
}

/**
 * A close that fails means the connection is already unusable, which is not worth failing a call over —
 * but it is worth one log line, otherwise a leak stays invisible.
 */
internal fun closeQuietly(response: Response, request: Request) {
    closeQuietly(response, "${request.method} ${request.url}")
}

private fun closeQuietly(response: Response, description: String) {
    try {
        response.close()
    } catch (e: IOException) {
        Log.w(TAG, "Could not close $description: ${e.message}")
    }
}
