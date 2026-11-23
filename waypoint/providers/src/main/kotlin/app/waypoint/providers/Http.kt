package app.waypoint.providers

import app.waypoint.trip.ProviderException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Identifies the app to the public services (Transitous asks for it; spec §2.1). No personal
 * details are sent.
 */
const val USER_AGENT = "Waypoint/0.1 (personal multi-stop navigation prototype; Android)"

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    })
    .build()

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Executes the call without blocking a thread; cancels the HTTP call if the coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** GET/POST returning (status, body); network failures become [ProviderException]. */
internal suspend fun OkHttpClient.fetch(request: Request, what: String): Pair<Int, String> {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw ProviderException("$what: no connection (${e.message})", e)
    }
    return response.use { it.code to (it.body?.string() ?: "") }
}

internal suspend fun OkHttpClient.getJson(url: String, what: String): JsonElement {
    val (code, body) = fetch(Request.Builder().url(url).get().build(), what)
    if (code !in 200..299) throw ProviderException("$what: HTTP $code")
    return try {
        lenientJson.parseToJsonElement(body)
    } catch (e: Exception) {
        throw ProviderException("$what: unreadable response", e)
    }
}

// Small helpers for reading loosely-typed JSON.
internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.arr: JsonArray get() = this as? JsonArray ?: JsonArray(emptyList())
internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal val JsonElement?.num: Double? get() = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
internal val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }
internal val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
