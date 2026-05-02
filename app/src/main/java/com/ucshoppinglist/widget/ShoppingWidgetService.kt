package com.ucshoppinglist.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ucshoppinglist.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShoppingWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ShoppingListFactory(applicationContext, intent)
}

class ShoppingListFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private data class WidgetItem(
        val id: String,
        val name: String,
        val quantity: String,
        val done: Boolean
    )

    private var items = emptyList<WidgetItem>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("shopping_prefs", Context.MODE_PRIVATE)
        val server = prefs.getString("serverHttpBase", "").orEmpty().trimEnd('/')
        val listId = prefs.getString("listId", "").orEmpty()
        if (server.isBlank() || listId.isBlank()) return

        try {
            val req = Request.Builder().url("$server/api/lists/$listId").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val root = JSONObject(resp.body?.string().orEmpty())
                val arr = root.optJSONArray("items") ?: return
                items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WidgetItem(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        quantity = o.optString("quantity", "1"),
                        done = o.optBoolean("done", false)
                    )
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount() = items.size

    override fun getViewTypeCount() = 1

    override fun hasStableIds() = true

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    override fun getLoadingView() = null

    override fun getViewAt(position: Int): RemoteViews {
        val item = items.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_item)

        val views = RemoteViews(context.packageName, R.layout.widget_item)

        // Check indicator
        if (item.done) {
            views.setTextViewText(R.id.widget_item_check, "✓")
            views.setTextColor(R.id.widget_item_check, 0xFF4CAF7D.toInt())
            views.setTextColor(R.id.widget_item_name, 0x66FFFFFF)
        } else {
            views.setTextViewText(R.id.widget_item_check, "○")
            views.setTextColor(R.id.widget_item_check, 0xAAFFFFFF.toInt())
            views.setTextColor(R.id.widget_item_name, 0xFFFFFFFF.toInt())
        }

        views.setTextViewText(R.id.widget_item_name, item.name)
        views.setTextViewText(R.id.widget_item_qty, item.quantity)

        // Fill-in intent carries item id + current done state for the toggle
        val fillIn = Intent().apply {
            putExtra(ShoppingWidget.EXTRA_ITEM_ID, item.id)
            putExtra(ShoppingWidget.EXTRA_DONE, item.done)
        }
        views.setOnClickFillInIntent(R.id.widget_item_check, fillIn)
        // Also allow tapping the whole row
        views.setOnClickFillInIntent(R.id.widget_item_name, fillIn)

        return views
    }
}
