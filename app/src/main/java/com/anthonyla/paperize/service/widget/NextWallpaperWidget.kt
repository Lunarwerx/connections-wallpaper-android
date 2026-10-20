package com.anthonyla.paperize.service.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.anthonyla.paperize.R
import com.anthonyla.paperize.service.shortcut.WallpaperShortcutActivity

/** Home-screen button for the next wallpaper; it opens the same invisible entry point as the launcher shortcut. */
class NextWallpaperWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val intent = Intent(context, WallpaperShortcutActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val views = RemoteViews(context.packageName, R.layout.widget_next_wallpaper).apply {
            setOnClickPendingIntent(R.id.widget_next_wallpaper, pendingIntent)
        }
        appWidgetManager.updateAppWidget(appWidgetIds, views)
    }
}
