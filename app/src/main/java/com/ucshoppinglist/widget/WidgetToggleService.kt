package com.ucshoppinglist.widget

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WidgetToggleService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemId = intent?.getStringExtra(ShoppingWidget.EXTRA_ITEM_ID)
        val done = intent?.getBooleanExtra(ShoppingWidget.EXTRA_DONE, false) ?: false

        if (itemId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Thread {
            try {
                val prefs = getSharedPreferences("shopping_prefs", Context.MODE_PRIVATE)
                val server = prefs.getString("serverHttpBase", "").orEmpty().trimEnd('/')
                val listId = prefs.getString("listId", "").orEmpty()
                if (server.isBlank() || listId.isBlank()) return@Thread

                val body = JSONObject().put("done", done).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$server/api/lists/$listId/items/$itemId")
                    .patch(body)
                    .build()
                client.newCall(req).execute().close()

                // Refresh all widget instances after successful toggle
                ShoppingWidget.updateAllWidgets(this)
            } catch (_: Exception) {
            } finally {
                stopSelf(startId)
            }
        }.start()

        return START_NOT_STICKY
    }
}
