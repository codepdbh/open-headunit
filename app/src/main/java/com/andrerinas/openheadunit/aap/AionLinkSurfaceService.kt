package com.andrerinas.openheadunit.aap

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.Surface
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Bound only with our signature permission; the caller explicitly supplies its own video surface. */
class AionLinkSurfaceService : Service() {
    companion object {
        @Volatile var active = false
            private set
        private const val ATTACH = 1
        private const val DETACH = 2
        private const val STATUS = 10
    }

    private var surface: Surface? = null
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            ATTACH -> {
                @Suppress("DEPRECATION")
                val next = message.data.getParcelable<Surface>("surface")
                val client = message.replyTo
                if (next == null || !next.isValid || client == null || surface != null) {
                    next?.release()
                    reply(client, false)
                } else {
                    val token = client.binder
                    val watcher = IBinder.DeathRecipient {
                        Handler(Looper.getMainLooper()).post { if (owner == token) detach() }
                    }
                    try {
                        token.linkToDeath(watcher, 0)
                        owner = token; death = watcher; surface = next
                        App.provide(this).videoDecoder.setSurface(next)
                        active = true
                        scope.launch { advance(App.provide(this@AionLinkSurfaceService).commManager.connectionState.value) }
                        reply(client, true)
                    } catch (_: Exception) {
                        detach(); next.release(); reply(client, false)
                    }
                }
                true
            }
            DETACH -> { if (message.replyTo?.binder == owner) detach(); true }
            else -> false
        }
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        val comm = App.provide(this).commManager
        scope.launch {
            comm.connectionState.collect { state ->
                advance(state)
            }
        }
    }

    private suspend fun advance(state: CommManager.ConnectionState) {
        if (!active) return
        val comm = App.provide(this).commManager
        when (state) {
            is CommManager.ConnectionState.Connected -> comm.startHandshake()
            is CommManager.ConnectionState.HandshakeComplete -> comm.startReading()
            else -> Unit
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { detach(); return false }
    override fun onDestroy() { detach(); scope.cancel(); super.onDestroy() }

    private fun detach() {
        active = false
        owner?.let { token -> death?.let { token.unlinkToDeath(it, 0) } }
        owner = null; death = null
        surface?.let {
            App.provide(this).videoDecoder.detachSurfaceIfCurrent(it, "aionlink_surface_detached")
            it.release()
        }
        surface = null
    }

    private fun reply(target: Messenger?, ok: Boolean) {
        try { target?.send(Message.obtain(null, STATUS).apply { data = Bundle().apply { putBoolean("attached", ok) } }) }
        catch (_: android.os.RemoteException) { detach() }
    }
}
