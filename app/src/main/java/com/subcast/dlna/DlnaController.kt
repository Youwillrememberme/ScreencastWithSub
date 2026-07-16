package com.subcast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import org.jupnp.transport.impl.jetty.JettyStreamClientImpl
import org.jupnp.transport.impl.jetty.StreamClientConfigurationImpl
import org.jupnp.transport.spi.NetworkAddressFactory
import org.jupnp.transport.spi.StreamClient
import org.jupnp.transport.spi.StreamServer
import org.jupnp.transport.Router
import org.jupnp.transport.impl.MulticastReceiverConfigurationImpl
import org.jupnp.transport.impl.MulticastReceiverImpl
import org.jupnp.transport.spi.DatagramProcessor
import org.jupnp.transport.spi.InitializationException
import org.jupnp.transport.spi.MulticastReceiver
import org.jupnp.transport.impl.DatagramIOConfigurationImpl
import org.jupnp.transport.impl.DatagramIOImpl
import org.jupnp.transport.spi.DatagramIO
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
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
 *
 * NB: UpnpServiceImpl's constructor does NOT start the stack -- it only stores
 * the configuration and leaves `registry`/`controlPoint`/`router` null and
 * `isRunning=false`. `startup()` is what binds the SSDP multicast socket,
 * creates the registry/control-point, and fires the initial M-SEARCH. Skip it
 * and `service.registry.addListener(...)` NPEs on a null registry; that NPE
 * is swallowed by runCatching and discovery silently never happens.
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
    private var searchJob: Job? = null

    private val registryListener = object : RegistryListener {
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) = refresh()
        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) = refresh()
        override fun remoteDeviceUpdated(registry: Registry, device: RemoteDevice) {}
        override fun remoteDeviceDiscoveryStarted(registry: Registry, device: RemoteDevice) {
            Log.d(TAG, "discovery started: ${device.identity.udn}")
        }
        override fun remoteDeviceDiscoveryFailed(registry: Registry, device: RemoteDevice, ex: Exception) {
            // Most common cause: device descriptor XML unreachable (wrong subnet /
            // AP isolation). Surfacing it is the only way to debug "can't find TV".
            Log.w(TAG, "discovery failed: ${device.identity.udn}", ex)
        }
        override fun localDeviceAdded(registry: Registry, device: LocalDevice) {
            Log.d(TAG, "local device added: ${device.identity.udn}")
        }
        override fun localDeviceRemoved(registry: Registry, device: LocalDevice) {
            Log.d(TAG, "local device removed: ${device.identity.udn}")
        }
        override fun beforeShutdown(registry: Registry) {}
        override fun afterShutdown() {}
    }

    fun bind() {
        scope.launch {
            // Fully wrap init so any failure logs instead of crashing the app.
            val outcome = runCatching {
                // Enable jUPnP's slf4j debug logs (SOAP requests/responses) to see
                // the TV's real 500 response body when query/control actions fail.
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "DEBUG")
                // Force the IPv4 stack. With cellular IPv6 interfaces present
                // (rmnet_data*), Java/jUPnP otherwise bind the SSDP multicast
                // socket to [::]:1900 (IPv6), which the IPv4 TV never receives --
                // discovery silently finds nothing even though the TV answers
                // SSDP from 192.168.110.2. Must be set before any socket is
                // created, i.e. before UpnpServiceImpl startup.
                System.setProperty("java.net.preferIPv4Stack", "true")
                System.setProperty("java.net.preferIPv4Addresses", "true")
                multicastLock = (context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createMulticastLock("subcast-dlna").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                // DesktopModeUpnpServiceConfiguration skips jUPnP's Android guard
                // (it has no published Android module for 2.7.1 -- see the class).
                val service = UpnpServiceImpl(DesktopModeUpnpServiceConfiguration())
                // startup() is mandatory: it binds the SSDP multicast socket,
                // builds the registry/control-point, and fires the first M-SEARCH.
                service.startup()
                service.registry.addListener(registryListener)
                upnp = service
                service.controlPoint.search()
                refresh()
            }
            outcome.onSuccess {
                _ready.value = true
                Log.i(TAG, "DLNA ready; listening for renderers")
                // SSDP is lossy UDP -- a single M-SEARCH can be missed, and some
                // TVs wake slowly. Re-broadcast for ~60s to fill the list.
                searchJob?.cancel()
                searchJob = scope.launch {
                    repeat(6) {
                        delay(10_000)
                        runCatching { upnp?.controlPoint?.search() }
                        Log.i(TAG, "diag: after search regN=${upnp?.registry?.devices?.size}")
                    }
                }
            }.onFailure { Log.e(TAG, "DLNA init failed", it) }
        }
    }

    fun unbind() {
        searchJob?.cancel()
        searchJob = null
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
            // Per-device isolation: a renderer with a malformed descriptor
            // (e.g. null friendlyName) must not blank the whole list.
            reg.devices.mapNotNull { dev ->
                runCatching {
                    if (dev.findService(UDAServiceType("AVTransport", 1)) != null) {
                        RendererDevice(dev.identity.udn.toString(), dev.details.friendlyName)
                    } else null
                }.onFailure { Log.w(TAG, "skip device ${dev.identity.udn}", it) }
                    .getOrNull()
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
        val dev = liveDevice(device.udn)
        if (dev == null) { Log.w(TAG, "state: device ${device.udn} not in registry"); return PlaybackState.UNKNOWN }
        val svc = avTransportService(dev)
        if (svc == null) { Log.w(TAG, "state: no AVTransport service"); return PlaybackState.UNKNOWN }
        val inv = actionInvocation(svc, "GetTransportInfo") ?: return PlaybackState.UNKNOWN
        inv.setInput("InstanceID", "0")
        val r = runAction(inv)
        if (!r.ok) { Log.w(TAG, "state: GetTransportInfo failed"); return PlaybackState.UNKNOWN }
        val s = r.invocation?.getOutput("CurrentTransportState")?.value?.toString()
        Log.d(TAG, "state: CurrentTransportState=$s")
        return when (s) {
            "PLAYING" -> PlaybackState.PLAYING
            "PAUSED_PLAYBACK" -> PlaybackState.PAUSED
            "STOPPED" -> PlaybackState.STOPPED
            "TRANSITIONING" -> PlaybackState.TRANSITIONING
            "NO_MEDIA_PRESENT" -> PlaybackState.NO_MEDIA_PRESENT
            else -> { Log.w(TAG, "state: unmapped transport state '$s' -> UNKNOWN"); PlaybackState.UNKNOWN }
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
                override fun failure(inv: ActionInvocation<*>?, op: UpnpResponse?, msg: String?) {
                    Log.w(TAG, "action failed: ${inv?.action?.name} status=${op?.statusCode} ${op?.statusMessage} msg=$msg")
                    cont.resume(ActionResult(false, inv))
                }
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

/**
 * jUPnP 2.7.1 service configuration that bypasses the Android-runtime guard,
 * without reflection.
 *
 * `DefaultUpnpServiceConfiguration`'s real constructor
 * `(int streamPort, int multicastPort, boolean applyAndroidCheck)` throws
 * `Error("Unsupported runtime environment, use org.jupnp.android.AndroidUpnpServiceConfiguration")`
 * when `applyAndroidCheck && ModelUtil.ANDROID_RUNTIME`. The public no-arg
 * constructor passes that flag as `true`, so it always throws on Android.
 * The protected `(boolean applyAndroidCheck)` constructor forwards its arg as
 * that same flag -- so `super(false)` skips the throw and runs the normal
 * (desktop) init path. This is the escape hatch the unpublished-for-2.7.1
 * `AndroidUpnpServiceConfiguration` would have used.
 *
 * Why not reflection instead: modern ART removed `java.lang.reflect.Field.modifiers`
 * and blocks `sun.misc.Unsafe` via hidden-API, so neither final-stripping nor
 * Unsafe could clear `ModelUtil.ANDROID_RUNTIME` on the target device. This
 * subclass needs none of that.
 *
 * The desktop network/transport paths work on Android for SSDP discovery +
 * SOAP control; we hold the WifiManager multicast lock ourselves.
 */
internal class DesktopModeUpnpServiceConfiguration : DefaultUpnpServiceConfiguration {
    constructor() : super(false)

    /**
     * Disable the stream *server*. A pure control point (SSDP discovery +
     * SOAP control) never needs to host an HTTP endpoint: discovery is UDP
     * multicast, and SOAP actions are outbound HTTP POSTs whose responses
     * come back over the same client socket. The only consumer of a stream
     * server is GenA event-callback delivery, which we don't use -- the UI
     * polls position via GetPositionInfo.
     *
     * The default createStreamServer instantiates JettyServletContainer, whose
     * <clinit> resolves org.eclipse.jetty.server.Server. jUPnP 2.7.1 ships no
     * transport config except Jetty, and we deliberately don't add jetty-server
     * (weight + Android quirks) -- so leaving the default would NoClassDefFoundError
     * inside UpnpServiceImpl.startup() -> RouterImpl.startAddressBasedTransports()
     * and abort the whole stack. Returning null is safe: RouterImpl null-checks
     * the result (logs "Configuration did not create a StreamServer for: ...")
     * and continues to bring up the multicast receiver + stream *client*.
     *
     * The stream *client* (createStreamClient -> JettyStreamClientImpl) still
     * works because we ship jetty-client, which is the only Jetty module it
     * references (org.eclipse.jetty.client / .http / .util.thread).
     */
    override fun createStreamServer(networkAddressFactory: NetworkAddressFactory): StreamServer<*>? = null

    /**
     * DefaultUpnpServiceConfiguration's `configuration` (StreamClientConfiguration)
     * field is vestigial: no constructor ever assigns it, so the stock
     * createStreamClient() hands null to JettyTransportConfiguration, which NPEs
     * on `config.getTimeoutSeconds()` inside UpnpServiceImpl.startup() ->
     * RouterImpl.enable(). The never-published-for-2.7.1 AndroidUpnpServiceConfiguration
     * overrides this same method; we do the equivalent -- build the Jetty HTTP
     * client (the thing that actually sends SOAP SetAVTransportURI/Play/etc.) with
     * a fresh config. The single-arg ctor inherits jUPnP's own 10s default timeout.
     */
    override fun createStreamClient(): StreamClient<*> {
        val config = StreamClientConfigurationImpl(getSyncProtocolExecutorService())
        return JettyStreamClientImpl(config)
    }

    /**
     * Use an IPv4-bound multicast receiver. The stock MulticastReceiverImpl uses
     * `new MulticastSocket(port)`, which on a dual-stack Android device with
     * cellular IPv6 binds [::]:port (IPv6) -- the IPv4 TV never receives our
     * SSDP M-SEARCH and discovery silently finds nothing. (java.net
     * .preferIPv4Stack is ignored by Android's network stack, so we fix it
     * here instead.)
     */
    override fun createMulticastReceiver(networkAddressFactory: NetworkAddressFactory): MulticastReceiver<*> {
        Log.i(TAG, "diag: createMulticastReceiver called")
        return IPv4MulticastReceiver(
            MulticastReceiverConfigurationImpl(
                networkAddressFactory.multicastGroup,
                networkAddressFactory.multicastPort
            )
        )
    }

    /**
     * Stock DatagramIOImpl.init() never calls setNetworkInterface(), so the
     * M-SEARCH multicast leaves on the default-route interface -- with cellular
     * present that's the IPv6 rmnet, and the IPv4 TV never receives it. Force
     * the multicast output interface to the one carrying bindAddr (wlan0).
     */
    override fun createDatagramIO(networkAddressFactory: NetworkAddressFactory): DatagramIO<*> {
        return IPv4DatagramIO(DatagramIOConfigurationImpl())
    }
}

/**
 * DatagramIOImpl that sets the multicast output interface (stock never does),
 * so M-SEARCH actually leaves on wlan0 toward the IPv4 TV.
 */
private class IPv4DatagramIO(
    configuration: DatagramIOConfigurationImpl
) : DatagramIOImpl(configuration) {
    override fun init(bindAddr: InetAddress, port: Int, r: Router, dp: DatagramProcessor) {
        super.init(bindAddr, port, r, dp)
        try {
            val nif = NetworkInterface.getByInetAddress(bindAddr)
            if (nif != null) socket.networkInterface = nif
            Log.i(TAG, "diag: DatagramIO bindAddr=$bindAddr port=$port nif=${nif?.name} socketLocal=${socket.localAddress} v4=${socket.localAddress is java.net.Inet4Address} v6=${socket.localAddress is java.net.Inet6Address}")
        } catch (e: Exception) {
            Log.e(TAG, "diag: DatagramIO setNI failed", e)
        }
    }
}

/**
 * MulticastReceiverImpl whose init() binds an IPv4 wildcard socket instead of
 * the stock `new MulticastSocket(port)` (which binds IPv6 [::] on dual-stack
 * Android). Everything else mirrors the stock implementation.
 */
private class IPv4MulticastReceiver(
    configuration: MulticastReceiverConfigurationImpl
) : MulticastReceiverImpl(configuration) {
    override fun init(
        ni: NetworkInterface,
        r: Router,
        naf: NetworkAddressFactory,
        dp: DatagramProcessor
    ) {
        Log.i(TAG, "diag: IPv4MulticastReceiver.init called")
        try {
            router = r
            networkAddressFactory = naf
            datagramProcessor = dp
            multicastInterface = ni
            val port = configuration.port
            multicastAddress = InetSocketAddress(configuration.group, port)
            val ipv4 = java.util.Collections.list(ni.inetAddresses).firstOrNull { it is java.net.Inet4Address }
                ?: InetAddress.getByName("0.0.0.0")
            Log.i(TAG, "diag: getByName(0.0.0.0)=${InetAddress.getByName("0.0.0.0").javaClass.simpleName} ni-ipv4=$ipv4")
            socket = MulticastSocket(InetSocketAddress(ipv4, port))
            Log.i(TAG, "diag: socket local=${socket.localAddress}/${socket.localPort} bound=${socket.isBound} v4=${socket.localAddress is java.net.Inet4Address} v6=${socket.localAddress is java.net.Inet6Address}")
            Log.i(TAG, "diag: IPv4 socket bound, joining group ${configuration.group}:${port} on ${ni.name}")
            socket.reuseAddress = true
            socket.receiveBufferSize = 32768
            socket.joinGroup(multicastAddress, ni)
            Log.i(TAG, "diag: joined group OK")
        } catch (e: Exception) {
            Log.e(TAG, "diag: IPv4 init FAILED", e)
            throw InitializationException("Could not init IPv4 multicast receiver: $e", e)
        }
    }
}
