package com.subcast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.controlpoint.ActionCallback
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.Service
import org.jupnp.model.types.UDAServiceType
import org.jupnp.model.types.UDN
import org.jupnp.registry.Registry
import org.jupnp.registry.RegistryListener
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "SubCast/Dlna"

/**
 * DLNA control point: discovers MediaRenderers and drives AVTransport +
 * RenderingControl actions over SOAP. Backed by jUPnP 2.7.x (Java 7 bytecode,
 * Android-compatible; 3.x targets Java 11+ and crashes on Android).
 *
 * Init runs off the main thread and is fully wrapped so any DLNA failure
 * (missing class, network) logs instead of crashing the app.
 */
class DlnaController(private val context: Context) {

    private val handler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "DLNA uncaught exception", t)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)

    private val _devices = MutableStateFlow<List<RendererDevice>>(emptyList())
    val devices: StateFlow<List<RendererDevice>> = _devices

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    @Volatile
    private var upnp: UpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val registryListener = object : RegistryListener {
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) = refresh()
        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) = refresh()
        override fun remoteDeviceUpdated(registry: Registry, device: RemoteDevice) {}
        override fun remoteDeviceDiscoveryStarted(registry: Registry, device: RemoteDevice) {}
        override fun remoteDeviceDiscoveryFailed(registry: Registry, device: RemoteDevice, ex: Exception) {}
        override fun localDeviceAdded(registry: Registry, device: LocalDevice) {}
        override fun localDeviceRemoved(registry: Registry, device: LocalDevice) {}
        override fun beforeShutdown(registry: Registry) {}
        override fun afterShutdown() {}
    }

    fun bind() {
        scope.launch {
            // Fully wrap init so any failure logs instead of crashing the app.
            val outcome = runCatching {
                multicastLock = (context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createMulticastLock("subcast-dlna").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                val service = UpnpServiceImpl(DefaultUpnpServiceConfiguration())
                service.registry.addListener(registryListener)
                upnp = service
                service.controlPoint.search()
                refresh()
            }
            outcome.onSuccess { _ready.value = true }
                .onFailure { Log.e(TAG, "DLNA init failed", it) }
        }
    }

    fun unbind() {
        runCatching { upnp?.shutdown() }
        runCatching { multicastLock?.release() }
        multicastLock = null
        upnp = null
        _ready.value = false
    }

    fun search() {
        runCatching { upnp?.controlPoint?.search() }
    }

    private fun refresh() {
        val reg = upnp?.registry ?: return
        runCatching {
            reg.devices.mapNotNull { dev ->
                if (dev.findService(UDAServiceType("AVTransport", 1)) != null) {
                    RendererDevice(dev.identity.udn.toString(), dev.details.friendlyName)
                } else null
            }
        }.onSuccess { _devices.value = it }
            .onFailure { Log.e(TAG, "refresh failed", it) }
    }

    private fun liveDevice(udn: String): Device<*, *, *>? =
        upnp?.registry?.getDevice(UDN.valueOf(udn), true)

    private fun avTransportService(device: Device<*, *, *>): Service<*, *>? =
        device.findService(UDAServiceType("AVTransport", 1))

    private fun renderingControlService(device: Device<*, *, *>): Service<*, *>? =
        device.findService(UDAServiceType("RenderingControl", 1))

    private fun actionInvocation(service: Service<*, *>, name: String): ActionInvocation<*>? =
        service.getAction(name)?.let { ActionInvocation(it) }

    // ---------------- AVTransport ----------------

    suspend fun cast(device: RendererDevice, url: String, title: String, mimeType: String): Boolean {
        val svc = avTransportService(liveDevice(device.udn) ?: return false) ?: return false
        val meta = DidlBuilder.build(url, title, mimeType)

        val setUri = actionInvocation(svc, "SetAVTransportURI") ?: return false
        setUri.setInput("InstanceID", "0")
        setUri.setInput("CurrentURI", url)
        setUri.setInput("CurrentURIMetaData", meta)
        if (!runAction(setUri).ok) return false

        val play = actionInvocation(svc, "Play") ?: return false
        play.setInput("InstanceID", "0")
        play.setInput("Speed", "1")
        return runAction(play).ok
    }

    suspend fun play(device: RendererDevice): Boolean = transport(device, "Play", withSpeed = true)
    suspend fun pause(device: RendererDevice): Boolean = transport(device, "Pause")
    suspend fun stop(device: RendererDevice): Boolean = transport(device, "Stop")

    private suspend fun transport(device: RendererDevice, action: String, withSpeed: Boolean = false): Boolean {
        val svc = avTransportService(liveDevice(device.udn) ?: return false) ?: return false
        val inv = actionInvocation(svc, action) ?: return false
        inv.setInput("InstanceID", "0")
        if (withSpeed) inv.setInput("Speed", "1")
        return runAction(inv).ok
    }

    suspend fun seek(device: RendererDevice, targetMs: Long): Boolean {
        val svc = avTransportService(liveDevice(device.udn) ?: return false) ?: return false
        val inv = actionInvocation(svc, "Seek") ?: return false
        inv.setInput("InstanceID", "0")
        inv.setInput("Unit", "REL_TIME")
        inv.setInput("Target", formatTime(targetMs))
        return runAction(inv).ok
    }

    suspend fun position(device: RendererDevice): PositionInfo {
        val svc = avTransportService(liveDevice(device.udn) ?: return PositionInfo(0, 0)) ?: return PositionInfo(0, 0)
        val inv = actionInvocation(svc, "GetPositionInfo") ?: return PositionInfo(0, 0)
        inv.setInput("InstanceID", "0")
        val r = runAction(inv)
        if (!r.ok) return PositionInfo(0, 0)
        val rel = r.invocation?.getOutput("RelTime")?.value?.toString() ?: "0:00:00"
        val dur = r.invocation?.getOutput("TrackDuration")?.value?.toString() ?: "0:00:00"
        return PositionInfo(parseTime(rel), parseTime(dur))
    }

    suspend fun state(device: RendererDevice): PlaybackState {
        val svc = avTransportService(liveDevice(device.udn) ?: return PlaybackState.UNKNOWN) ?: return PlaybackState.UNKNOWN
        val inv = actionInvocation(svc, "GetTransportInfo") ?: return PlaybackState.UNKNOWN
        inv.setInput("InstanceID", "0")
        val r = runAction(inv)
        if (!r.ok) return PlaybackState.UNKNOWN
        val s = r.invocation?.getOutput("CurrentTransportState")?.value?.toString() ?: return PlaybackState.UNKNOWN
        return when (s) {
            "PLAYING" -> PlaybackState.PLAYING
            "PAUSED_PLAYBACK" -> PlaybackState.PAUSED
            "STOPPED" -> PlaybackState.STOPPED
            "TRANSITIONING" -> PlaybackState.TRANSITIONING
            "NO_MEDIA_PRESENT" -> PlaybackState.NO_MEDIA_PRESENT
            else -> PlaybackState.UNKNOWN
        }
    }

    // ---------------- RenderingControl ----------------

    suspend fun setVolume(device: RendererDevice, volume: Int): Boolean {
        val svc = renderingControlService(liveDevice(device.udn) ?: return false) ?: return false
        val inv = actionInvocation(svc, "SetVolume") ?: return false
        inv.setInput("InstanceID", "0")
        inv.setInput("Channel", "Master")
        inv.setInput("DesiredVolume", volume.coerceIn(0, 100).toString())
        return runAction(inv).ok
    }

    suspend fun getVolume(device: RendererDevice): Int {
        val svc = renderingControlService(liveDevice(device.udn) ?: return 0) ?: return 0
        val inv = actionInvocation(svc, "GetVolume") ?: return 0
        inv.setInput("InstanceID", "0")
        inv.setInput("Channel", "Master")
        val r = runAction(inv)
        if (!r.ok) return 0
        return r.invocation?.getOutput("CurrentVolume")?.value?.toString()?.toIntOrNull() ?: 0
    }

    // ---------------- internals ----------------

    private data class ActionResult(val ok: Boolean, val invocation: ActionInvocation<*>? = null)

    private suspend fun runAction(invocation: ActionInvocation<*>): ActionResult =
        suspendCancellableCoroutine { cont ->
            val cp = upnp?.controlPoint
            if (cp == null) {
                cont.resume(ActionResult(false, invocation))
                return@suspendCancellableCoroutine
            }
            cp.execute(object : ActionCallback(invocation) {
                override fun success(inv: ActionInvocation<*>?) = cont.resume(ActionResult(true, inv))
                override fun failure(inv: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) =
                    cont.resume(ActionResult(false, inv))
            })
        }

    /** "H:MM:SS.mmm" - the REL_TIME format DLNA renderers expect. */
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        val ms3 = ms % 1000
        return String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, ms3)
    }

    private fun parseTime(s: String): Long {
        val m = Regex("(\\d+):(\\d+):(\\d+)(?:\\.(\\d+))?").find(s) ?: return 0L
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val frac = m.groupValues[4]
        val ms = if (frac.isEmpty()) 0L else (frac + "000").substring(0, 3).toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + ms
    }
}
