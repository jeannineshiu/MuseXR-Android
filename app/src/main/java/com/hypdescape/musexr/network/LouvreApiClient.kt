package com.hypdescape.musexr.network

import android.graphics.Bitmap
import android.util.Base64
import com.hypdescape.musexr.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

data class AskResponse(val mode: String, val answer: String, val exhibit: String)

class LouvreApiClient(
    // Set via `backend_base_url` in local.properties (git-ignored) -> BuildConfig.
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    suspend fun ask(question: String, image: Bitmap): AskResponse =
        withContext(Dispatchers.IO) {
            val requestBody =
                JSONObject()
                    .apply {
                        put("question", question)
                        put("image_base64", image.toBase64Jpeg())
                    }
                    .toString()
                    .toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url("$baseUrl/ask").post(requestBody).build()

            client.newCall(request).executeSuspend().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Request to /ask failed: HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                AskResponse(
                    mode = json.optString("mode"),
                    answer = json.optString("answer"),
                    exhibit = json.optString("exhibit"),
                )
            }
        }

    private fun Bitmap.toBase64Jpeg(): String {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun Call.executeSuspend(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response)
                    }
                }
            )
        }
}
