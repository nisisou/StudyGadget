package com.example.studycompanion.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.studycompanion.MainActivity
import com.example.studycompanion.R
import com.example.studycompanion.character.CharacterRasterizer
import com.example.studycompanion.character.CharacterStore
import com.example.studycompanion.core.Logger
import kotlinx.coroutines.*

/** Render only on events. ViewFlipper runs the cached frames inside the launcher. */
class CharacterWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REFRESH, NEXT, MODE, PAUSE -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, CharacterWidgetProvider::class.java))
                if (ids.isEmpty()) return
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (intent.action != REFRESH && id !in ids) return
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                when (intent.action) {
                    NEXT -> prefs.edit().putInt("variant_$id",(prefs.getInt("variant_$id",0)+1).and(Int.MAX_VALUE)).apply()
                    MODE -> prefs.edit().putInt("mode_$id",WidgetMotion.nextMode(prefs.getInt("mode_$id",-1))).apply()
                    PAUSE -> prefs.edit().putBoolean("paused_$id",!prefs.getBoolean("paused_$id",false)).apply()
                }
                enqueue(context, if (intent.action==REFRESH) ids else intArrayOf(id), advance = intent.action==REFRESH)
            }
            else -> super.onReceive(context,intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = enqueue(context,ids,true)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = enqueue(context,intArrayOf(id),false)
    override fun onDeleted(context: Context, ids: IntArray) {
        val editor=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
        ids.forEach { id -> latest.remove(id); listOf("mode_","variant_","paused_").forEach { editor.remove("$it$id") } }
        editor.apply()
    }

    private fun enqueue(context: Context, ids: IntArray, advance: Boolean) {
        val pending=goAsync()
        val deadline=System.nanoTime()+6_000_000_000L
        val app=context.applicationContext
        val ticket=sequence.incrementAndGet()
        ids.forEach { latest[it]=ticket }
        scope.launch {
            try {
                val active=AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app,CharacterWidgetProvider::class.java)).toSet()
                val targets=ids.filter { it in active && latest[it]==ticket }
                if (targets.isEmpty()) return@launch
                val snapshot=CharacterStore(app).snapshot()
                val renderer=CharacterRasterizer(snapshot.bytes)
                for (id in targets) {
                    if (latest[id]!=ticket) continue
                    val prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                    if (advance) prefs.edit().putInt("variant_$id",(prefs.getInt("variant_$id",0)+1).and(Int.MAX_VALUE)).apply()
                    val mode=prefs.getInt("mode_$id",-1)
                    val now=System.currentTimeMillis()
                    val start=prefs.getLong("liveStart",0)
                    val state=WidgetMotion.state(mode,start,prefs.getLong("completedAt",0),now)
                    val recipes=snapshot.recipes.filter { it.state==state }
                    val variant=prefs.getInt("variant_$id",0).coerceAtLeast(0)%recipes.size
                    val recipe=recipes[variant]
                    val paused=prefs.getBoolean("paused_$id",false)
                    val views=RemoteViews(app.packageName,if(paused) R.layout.character_widget_static else R.layout.character_widget)
                    views.setTextViewText(R.id.widget_mode,(if(mode==-1) "自動 · " else "")+WidgetMotion.labels[state])
                    views.setTextViewText(R.id.widget_detail,"動作 ${variant+1}/${recipes.size}  ·  タップで変更")
                    views.setTextViewText(R.id.widget_pause,if(paused) "再生" else "静止")
                    val live=start>0 && now>=start && now-start<12*60*60*1000L
                    views.setViewVisibility(R.id.widget_elapsed,if(live) View.VISIBLE else View.GONE)
                    if(live) views.setChronometer(R.id.widget_elapsed,SystemClock.elapsedRealtime()-(now-start),"学習 %s",true)
                    views.removeAllViews(R.id.widget_frames)
                    // 24 * 128 * 160 * 4 = 1.875 MiB, bounded independently of widget size.
                    repeat(if(paused) 1 else FRAME_COUNT) { frame ->
                        if(latest[id]!=ticket) return@repeat
                        val pixels=renderer.render(recipe,frame.toFloat()/FRAME_COUNT) { latest[id]==ticket && System.nanoTime()<deadline }
                        val bitmap=Bitmap.createBitmap(pixels,128,160,Bitmap.Config.ARGB_8888)
                        val child=RemoteViews(app.packageName,R.layout.character_widget_frame)
                        child.setImageViewBitmap(R.id.widget_frame,bitmap)
                        child.setContentDescription(R.id.widget_frame,"キャラクター：${WidgetMotion.labels[state]}")
                        child.setOnClickPendingIntent(R.id.widget_frame,action(app,id,NEXT))
                        views.addView(R.id.widget_frames,child)
                    }
                    if(!paused) views.setInt(R.id.widget_frames,"setFlipInterval",(4200f/recipe.speed/FRAME_COUNT).toInt().coerceIn(80,900))
                    views.setOnClickPendingIntent(R.id.widget_frames,action(app,id,NEXT))
                    views.setOnClickPendingIntent(R.id.widget_mode,action(app,id,MODE))
                    views.setOnClickPendingIntent(R.id.widget_pause,action(app,id,PAUSE))
                    views.setOnClickPendingIntent(R.id.widget_detail,action(app,id,NEXT))
                    views.setOnClickPendingIntent(R.id.widget_title,PendingIntent.getActivity(app,id,
                        Intent(app,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    if(latest[id]==ticket) AppWidgetManager.getInstance(app).updateAppWidget(id,views)
                }
            } catch(e: Exception) {
                Logger.e("CharacterWidget","Widget update failed",e)
                // Keep an actionable widget even if a model or host rejects the preview.
                ids.filter { latest[it]==ticket }.forEach { id ->
                    runCatching {
                        val fallback=RemoteViews(app.packageName,R.layout.character_widget_static)
                        val image=RemoteViews(app.packageName,R.layout.character_widget_frame)
                        image.setImageViewResource(R.id.widget_frame,R.drawable.widget_character_preview)
                        fallback.addView(R.id.widget_frames,image)
                        fallback.setTextViewText(R.id.widget_detail,"表示を再試行するにはタップ")
                        fallback.setOnClickPendingIntent(R.id.widget_detail,action(app,id,REFRESH))
                        AppWidgetManager.getInstance(app).updateAppWidget(id,fallback)
                    }
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        private const val PREFS="character_widget"
        private const val REFRESH="com.example.studycompanion.widget.REFRESH"
        private const val NEXT="com.example.studycompanion.widget.NEXT"
        private const val MODE="com.example.studycompanion.widget.MODE"
        private const val PAUSE="com.example.studycompanion.widget.PAUSE"
        private const val FRAME_COUNT=24
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default.limitedParallelism(1))
        private val sequence=java.util.concurrent.atomic.AtomicLong()
        private val latest=java.util.concurrent.ConcurrentHashMap<Int,Long>()
        private fun action(context: Context,id: Int,name: String): PendingIntent = PendingIntent.getBroadcast(context,id,
            Intent(context,CharacterWidgetProvider::class.java).setAction(name)
                .setData(Uri.parse("studywidget://$id/${name.substringAfterLast('.')}"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun refresh(context: Context) {
            if(AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,CharacterWidgetProvider::class.java)).isNotEmpty())
                context.sendBroadcast(Intent(context,CharacterWidgetProvider::class.java).setAction(REFRESH))
        }
        fun setLive(context: Context,subject: String?,startMillis: Long = 0) {
            val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            val completedAt=when {
                subject!=null -> 0L
                prefs.getLong("liveStart",0)>0 -> System.currentTimeMillis()
                else -> prefs.getLong("completedAt",0)
            }
            prefs.edit().putLong("liveStart",if(subject==null) 0 else startMillis)
                .putLong("completedAt",completedAt).apply()
            refresh(context)
        }
        fun pin(context: Context): Boolean {
            val manager=AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context,CharacterWidgetProvider::class.java),null,null)
        }
    }
}
