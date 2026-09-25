package com.neop2p.data.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.torproject.jni.TorService

/**
 * Binds tor-android's TorService. Control is over the service's Unix
 * ControlSocket; the HTTP tunnel port is read from the service once a circuit
 * is up. There is no TCP control port and no cookie to manage.
 */
@Singleton
class TorControlImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : TorControl {

    private val _events = MutableSharedFlow<TorControlEvent>(extraBufferCapacity = 32)
    override val events: Flow<TorControlEvent> = _events

    @Volatile private var service: TorService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: "tor_error"
            _events.tryEmit(TorControlEvent.Failure(msg))
        }
    }

    override suspend fun start() {
        TorService.getTorrc(context).writeText(TorrcBuilder.build())
        ContextCompat.registerReceiver(
            context,
            errorReceiver,
            IntentFilter(TorService.ACTION_ERROR),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val intent = Intent(context, TorService::class.java)
        context.startService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val svc = service
                val conn = svc?.torControlConnection
                if (conn != null) {
                    val port = runCatching { svc.httpTunnelPort }.getOrDefault(0)
                    val phase = runCatching { conn.getInfo("status/bootstrap-phase") }.getOrNull()
                    val percent = phase
                        ?.let { Regex("PROGRESS=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    if (percent != null) _events.emit(TorControlEvent.Bootstrap(percent))
                    if (port > 0 && (percent == null || percent >= 100)) {
                        _events.emit(TorControlEvent.Ready(port))
                        return@withContext
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
            _events.emit(TorControlEvent.Failure("tor_start_timeout"))
        }
    }

    override suspend fun stop() {
        runCatching { context.unbindService(serviceConnection) }
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
        runCatching { context.unregisterReceiver(errorReceiver) }
        service = null
    }

    private companion object {
        const val START_TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
