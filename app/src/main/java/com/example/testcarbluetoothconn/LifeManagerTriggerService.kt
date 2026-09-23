package com.example.testcarbluetoothconn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LifeManagerTriggerService {

    private const val TAG = "LifeManagerCarTrigger"

    // כתובת שרת ה-Render המעודכנת
    private const val WEBHOOK_URL = "https://lifemanager-gx9j.onrender.com/api/triggers/car-entry"

    // הדבק כאן את ה-UUID שהעתקת מתוך תפריט ההמבורגר של LifeManager:
    private const val USER_API_KEY = "e7f5863e-f7a8-4717-838e-7465f8fd71b8"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * פונקציה לשליחת טריגר כניסה לרכב לשרת LifeManager.
     * הקריאה מתבצעת ברקע (Dispatchers.IO) כדי לא לתקוע את המכשיר.
     */
    fun sendCarEntryTrigger(deviceName: String?, deviceAddress: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // הרכבת גוף ההודעה בפורמט JSON
                val payload = JSONObject().apply {
                    put("device_name", deviceName ?: "Car Bluetooth")
                    put("device_address", deviceAddress ?: "Unknown")
                    put("timestamp", System.currentTimeMillis())
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toString().toRequestBody(mediaType)

                // בניית בקשת HTTP POST עם מפתח ה-API האישי של המשתמש
                val request = Request.Builder()
                    .url(WEBHOOK_URL)
                    .addHeader("Authorization", "Bearer $USER_API_KEY")
                    .post(requestBody)
                    .build()

                // ביצוע הקריאה ברקע
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (response.isSuccessful) {
                        Log.i(TAG, "Car entry triggered successfully: $responseBody")
                    } else {
                        Log.e(TAG, "Server responded with error ${response.code}: $responseBody")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network exception while triggering car entry", e)
            }
        }
    }
}