package com.splatt.elite.network

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SplattStatus(
    val state: Int = 0,
    val shotX: Float = 0.0f,
    val shotY: Float = 0.0f,
    val time: Long = 0,
    val x: Float = 0.0f,
    val y: Float = 0.0f,
    val v: Int = 0,
    val s: Int = 0,
    val c: Int = 0,
    val f: Int = 0,
    val host: String = ""
)

class SplattApiClient(private var baseUrl: String = "http://192.168.4.1") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    fun updateBaseUrl(newIp: String) {
        val cleanIp = newIp.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        baseUrl = "http://$cleanIp"
    }

    fun getBaseUrl(): String = baseUrl

    fun fetchStatus(onSuccess: (SplattStatus) -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/status")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onFailure(IOException("Unexpected code $response"))
                    return
                }
                val body = response.body?.string() ?: ""
                try {
                    val status = parseStatusJson(body)
                    onSuccess(status)
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
        })
    }

    fun executeGet(endpoint: String, queryParams: Map<String, String> = emptyMap(), onComplete: (Boolean) -> Unit) {
        val urlBuilder = StringBuilder("$baseUrl$endpoint")
        if (queryParams.isNotEmpty()) {
            urlBuilder.append("?")
            queryParams.forEach { (key, value) ->
                urlBuilder.append("$key=$value&")
            }
            urlBuilder.deleteAt(urlBuilder.length - 1)
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SplattApiClient", "Error calling $endpoint: ${e.message}")
                onComplete(false)
            }

            override fun onResponse(call: Call, response: Response) {
                onComplete(response.isSuccessful)
                response.close()
            }
        })
    }

    private fun parseStatusJson(json: String): SplattStatus {
        // A simple, robust manual JSON parser for the SplattStatus struct to avoid GSON dependencies.
        val clean = json.replace("{", "").replace("}", "").replace("\"", "")
        val pairs = clean.split(",")
        val map = mutableMapOf<String, String>()
        for (pair in pairs) {
            val parts = pair.split(":")
            if (parts.size >= 2) {
                val key = parts[0].trim()
                val value = parts.subList(1, parts.size).joinToString(":").trim()
                map[key] = value
            }
        }

        return SplattStatus(
            state = map["state"]?.toIntOrNull() ?: 0,
            shotX = map["shot_x"]?.toFloatOrNull() ?: 0.0f,
            shotY = map["shot_y"]?.toFloatOrNull() ?: 0.0f,
            time = map["time"]?.toLongOrNull() ?: 0,
            x = map["x"]?.toFloatOrNull() ?: 0.0f,
            y = map["y"]?.toFloatOrNull() ?: 0.0f,
            v = map["v"]?.toIntOrNull() ?: 0,
            s = map["s"]?.toIntOrNull() ?: 0,
            c = map["c"]?.toIntOrNull() ?: 0,
            host = map["host"] ?: ""
        )
    }
}
