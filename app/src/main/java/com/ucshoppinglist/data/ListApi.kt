package com.ucshoppinglist.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ListApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private var httpBaseUrl: String = "http://10.0.2.2:8080"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun updateBaseUrl(baseUrl: String) {
        httpBaseUrl = baseUrl.trim().removeSuffix("/")
    }

    fun createList(title: String, shared: Boolean = true): ListAccess {
        val payload = JSONObject().put("title", title).put("shared", shared)
        val request = Request.Builder()
            .url("$httpBaseUrl/api/lists/create")
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Liste konnte nicht erstellt werden (${response.code})")
            }
            val text = response.body?.string().orEmpty()
            val root = JSONObject(text)
            return ListAccess(
                listId = root.optString("listId"),
                inviteCode = root.optString("inviteCode"),
                title = root.optString("title", "Gemeinsame Einkaufsliste")
            )
        }
    }

    fun joinByCode(inviteCode: String): ListAccess {
        val payload = JSONObject().put("inviteCode", inviteCode.trim().uppercase())
        val request = Request.Builder()
            .url("$httpBaseUrl/api/lists/join")
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Code ungueltig oder Server nicht erreichbar (${response.code})")
            }
            val text = response.body?.string().orEmpty()
            val root = JSONObject(text)
            return ListAccess(
                listId = root.optString("listId"),
                inviteCode = root.optString("inviteCode"),
                title = root.optString("title", "Gemeinsame Einkaufsliste")
            )
        }
    }

    fun requestAccess(deviceName: String, listId: String? = null): AccessRequestStatus {
        val payload = JSONObject().put("deviceName", deviceName)
        if (!listId.isNullOrBlank()) payload.put("listId", listId)

        val request = Request.Builder()
            .url("$httpBaseUrl/api/access/request")
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Freigabeanfrage fehlgeschlagen (${response.code})")
            }
            val text = response.body?.string().orEmpty()
            val root = JSONObject(text)
            return AccessRequestStatus(
                requestId = root.optString("id"),
                pairCode = root.optString("pairCode"),
                status = root.optString("status", "pending"),
                accessToken = root.optString("accessToken")
            )
        }
    }

    fun getAccessRequestStatus(requestId: String): AccessRequestStatus {
        val request = Request.Builder()
            .url("$httpBaseUrl/api/access/request/$requestId")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Anfrage-Status konnte nicht geholt werden (${response.code})")
            }
            val text = response.body?.string().orEmpty()
            val root = JSONObject(text)
            return AccessRequestStatus(
                requestId = root.optString("id"),
                pairCode = root.optString("pairCode"),
                status = root.optString("status", "pending"),
                accessToken = root.optString("accessToken")
            )
        }
    }

    fun redeemAccessToken(accessToken: String): ListAccess {
        val payload = JSONObject().put("accessToken", accessToken)
        val request = Request.Builder()
            .url("$httpBaseUrl/api/access/redeem")
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Zugangstoken ungueltig oder abgelaufen (${response.code})")
            }
            val text = response.body?.string().orEmpty()
            val root = JSONObject(text)
            return ListAccess(
                listId = root.optString("listId"),
                inviteCode = root.optString("inviteCode"),
                title = root.optString("title", "Gemeinsame Einkaufsliste"),
                shared = root.optBoolean("shared", true)
            )
        }
    }
}
