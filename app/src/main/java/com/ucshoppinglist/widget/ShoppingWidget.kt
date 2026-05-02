package com.ucshoppinglist.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.ucshoppinglist.R

class ShoppingWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.ucshoppinglist.WIDGET_TOGGLE"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_DONE = "done"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ShoppingWidget::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, ShoppingWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("shopping_prefs", Context.MODE_PRIVATE)
            val title = prefs.getString("listTitle", "Einkaufsliste").orEmpty()

            val views = RemoteViews(context.packageName, R.layout.widget_shopping)
            views.setTextViewText(R.id.widget_title, title)

            // RemoteAdapter for the list
            val serviceIntent = Intent(context, ShoppingWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // Template PendingIntent for item-row clicks (toggle)
            val toggleIntent = Intent(context, ShoppingWidget::class.java).apply {
                action = ACTION_TOGGLE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list, togglePendingIntent)

            // Refresh button
            val refreshIntent = Intent(context, ShoppingWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
            val currentDone = intent.getBooleanExtra(EXTRA_DONE, false)
            val toggleIntent = Intent(context, WidgetToggleService::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_DONE, !currentDone)
            }
            context.startService(toggleIntent)
        }
    }
}
