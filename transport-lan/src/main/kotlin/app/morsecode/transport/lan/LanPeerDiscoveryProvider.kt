package app.morsecode.transport.lan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.morsecode.core.model.NetworkPorts
import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.CancellableOperation
import app.morsecode.core.transfer.session.ControlConnectionListener
import app.morsecode.core.transfer.session.ControlConnectionResult
import app.morsecode.core.transfer.session.ControlSession
import app.morsecode.core.transfer.session.DiscoveredPeer
import app.morsecode.core.transfer.session.DiscoveryDiagnostics
import app.morsecode.core.transfer.session.DiscoveryLease
import app.morsecode.core.transfer.session.DiscoveryListener
import app.morsecode.core.transfer.session.DiscoveryPhase
import app.morsecode.core.transfer.session.DiscoveryRequest
import app.morsecode.core.transfer.session.DiscoveryStartResult
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.PeerAdvertisement
import app.morsecode.core.transfer.session.PeerDiscoveryProvider
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.PeerRegistry
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionHandshakeCodec
import app.morsecode.core.transfer.session.SessionHandshakeDecision
import app.morsecode.core.transfer.session.SessionHandshakeDecodeResult
import app.morsecode.core.transfer.session.SessionHandshakeHeaderResult
import app.morsecode.core.transfer.session.SessionHandshakeMessage
import app.morsecode.core.transfer.session.SessionHandshakeNegotiator
import app.morsecode.core.transfer.session.SessionHello
import app.morsecode.core.transfer.session.SessionPeerProfile
import app.morsecode.core.transfer.session.SessionReject
import app.morsecode.core.transfer.session.TransportEndpoint
import app.morsecode.core.transfer.session.MAX_APP_VERSION_BYTES
import app.morsecode.core.transfer.session.MAX_DISPLAY_NAME_BYTES
import app.morsecode.transport.lan.discovery.LanBeacon
import app.morsecode.transport.lan.discovery.LanBeaconCodec
import app.morsecode.transport.lan.discovery.LanBeaconDecodeResult
import java.io.DataInputStream
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun generatePeerInstanceId(): PeerInstanceId =
    PeerInstanceId(UUID.randomUUID().toString().replace("-", "").lowercase())

private val GROUP_ADDRESS_BYTES: ByteArray = byteArrayOf(239.toByte(), 255.toByte(), 33.toByte(), 45.toByte())

private fun multicastGroup(): InetAddress = InetAddress.getByAddress(GROUP_ADDRESS_BYTES)

private fun daemonThread(name: String, action: () -> Unit): Thread =
    Thread(action, name).apply { isDaemon = true }

private fun boundedExecutor(threadName: String, threads: Int, queueCapacity: Int): ThreadPoolExecutor =
    ThreadPoolExecutor(
        threads,
        threads,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        ThreadFactory { runnable -> daemonThread(threadName, runnable::run) },
        ThreadPoolExecutor.AbortPolicy(),
    )

private fun parseIpv4(value: String): Inet4Address? {
    val components = value.split('.')
    if (components.size != 4) return null
    val bytes = ByteArray(4)
    components.forEachIndexed { index, component ->
        if (component.isEmpty() || component.length > 3 || component.any { it !in '0'..'9' }) return null
        val octet = component.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        bytes[index] = octet.toByte()
    }
    return InetAddress.getByAddress(bytes) as? Inet4Address
}

private fun closeSocket(socket: Socket) {
    try {
        socket.close()
    } catch (_: IOException) {
        // No socket exception detail is retained or surfaced.
    } catch (_: RuntimeException) {
        // A platform socket may already have been closed during teardown.
    }
}

/**
 * Android LAN implementation. Construction is inert; a discovery lease is the
 * sole owner of its sockets, multicast lock, network callback and worker queues.
 */
public class LanPeerDiscoveryProvider(
    context: Context,
    private val clock: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() },
    private val peerInstanceIdFactory: () -> PeerInstanceId = ::generatePeerInstanceId,
) : PeerDiscoveryProvider {
    private val appContext: Context = context.applicationContext
    private var activeLease: LanDiscoveryLease? = null

    override val transport: TransportKind = TransportKind.LAN

    @Synchronized
    override fun start(request: DiscoveryRequest, listener: DiscoveryListener): DiscoveryStartResult {
        if (activeLease?.isClosed == false) {
            return DiscoveryStartResult.Failed(SessionFailure(SessionFailureCode.DISCOVERY_ALREADY_ACTIVE))
        }
        if (!isPartARequest(request)) {
            return DiscoveryStartResult.Failed(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        val safeRequest = try {
            defensiveCopy(request)
        } catch (_: RuntimeException) {
            return DiscoveryStartResult.Failed(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        val localPeerId = try {
            peerInstanceIdFactory()
        } catch (_: RuntimeException) {
            return DiscoveryStartResult.Failed(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        val lease = try {
            LanDiscoveryLease(
                context = appContext,
                request = safeRequest,
                listener = listener,
                clock = clock,
                peerInstanceId = localPeerId,
                onClosed = { closedLease -> clearLease(closedLease) },
            )
        } catch (_: RuntimeException) {
            return DiscoveryStartResult.Failed(SessionFailure(SessionFailureCode.INVALID_REQUEST))
        }
        activeLease = lease
        val failure = lease.start()
        if (failure != null) {
            clearLease(lease)
            return DiscoveryStartResult.Failed(SessionFailure(failure))
        }
        return DiscoveryStartResult.Started(lease)
    }

    @Synchronized
    private fun clearLease(lease: LanDiscoveryLease) {
        if (activeLease === lease) activeLease = null
    }

    private fun isPartARequest(request: DiscoveryRequest): Boolean {
        val capabilities = request.capabilities
        return request.displayName.isNotBlank() &&
            request.appVersion.isNotBlank() &&
            request.displayName.length <= MAX_DISPLAY_NAME_BYTES &&
            request.appVersion.length <= MAX_APP_VERSION_BYTES &&
            TransportKind.LAN in capabilities.supportedTransports &&
            SessionFeature.CONTROL_HANDSHAKE in capabilities.features &&
            SessionFeature.FILE_PAYLOAD !in capabilities.features &&
            SessionFeature.RESUME !in capabilities.features &&
            SessionFeature.SECURE_SESSION !in capabilities.features &&
            capabilities.encryption == EncryptionCapability.NONE
    }

    private fun defensiveCopy(request: DiscoveryRequest): DiscoveryRequest {
        val copiedCapabilities = request.capabilities.copy(
            supportedTransports = request.capabilities.supportedTransports.toSet(),
            features = request.capabilities.features.toSet(),
        )
        return request.copy(capabilities = copiedCapabilities)
    }

    private class LanDiscoveryLease(
        private val context: Context,
        request: DiscoveryRequest,
        private val listener: DiscoveryListener,
        private val clock: MonotonicClock,
        peerInstanceId: PeerInstanceId,
        private val onClosed: (LanDiscoveryLease) -> Unit,
    ) : DiscoveryLease {
        private val closed = AtomicBoolean(false)
        private val networkDirty = AtomicBoolean(true)
        private val desiredNetwork = AtomicReference<NetworkTarget?>(null)
        private val callbackDrops = AtomicLong(0L)
        private val acceptedBeacons = AtomicLong(0L)
        private val rejectedBeacons = AtomicLong(0L)
        private val networkChanges = AtomicLong(0L)
        private val controlPermits = Semaphore(MAX_CONTROL_SESSIONS, true)
        private val activeOperations = Collections.newSetFromMap(ConcurrentHashMap<ControlAttempt, Boolean>())
        private val activeSessions = Collections.newSetFromMap(ConcurrentHashMap<LanControlSession, Boolean>())
        private val inFlightSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
        private val incomingPermits = ConcurrentHashMap<Socket, AtomicBoolean>()
        private val peerNetworks = ConcurrentHashMap<PeerInstanceId, Network>()
        private val registry = PeerRegistry()
        private val registryLock = Any()
        private val signal = Object()
        private val localProfile = SessionPeerProfile(
            peerInstanceId = peerInstanceId,
            displayName = request.displayName,
            appVersion = request.appVersion,
            protocolRange = request.protocolRange,
            capabilities = request.capabilities,
        )

        @Volatile
        private var phase: DiscoveryPhase = DiscoveryPhase.STARTING

        @Volatile
        private var lastFailure: SessionFailureCode? = null

        @Volatile
        private var startedElapsedMillis: Long = 0L

        @Volatile
        private var connectivityManager: ConnectivityManager? = null

        @Volatile
        private var networkCallback: ConnectivityManager.NetworkCallback? = null

        @Volatile
        private var networkCallbackRegistered: Boolean = false

        @Volatile
        private var multicastLock: WifiManager.MulticastLock? = null

        @Volatile
        private var controlServer: ServerSocket? = null

        @Volatile
        private var multicastSocket: MulticastSocket? = null

        @Volatile
        private var multicastInterface: NetworkInterface? = null

        @Volatile
        private var boundNetwork: NetworkTarget? = null

        @Volatile
        private var discoveryThread: Thread? = null

        @Volatile
        private var acceptThread: Thread? = null

        @Volatile
        private var callbackExecutor: ThreadPoolExecutor? = null

        @Volatile
        private var controlExecutor: ThreadPoolExecutor? = null

        override val localPeerInstanceId: PeerInstanceId = peerInstanceId

        val isClosed: Boolean get() = closed.get()

        /** Called only by the provider after an explicit start request. */
        fun start(): SessionFailureCode? {
            startedElapsedMillis = nowElapsedMillis()
            callbackExecutor = boundedExecutor(
                threadName = "morsec-lan-callback",
                threads = 1,
                queueCapacity = MAX_CALLBACK_QUEUE,
            )
            controlExecutor = boundedExecutor(
                threadName = "morsec-lan-control",
                threads = MAX_CONTROL_SESSIONS,
                queueCapacity = MAX_PENDING_CONTROL_TASKS,
            )
            notifyDiscoveryChanged()

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                stop(SessionFailureCode.MULTICAST_PERMISSION_MISSING)
                return SessionFailureCode.MULTICAST_PERMISSION_MISSING
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                stop(SessionFailureCode.MULTICAST_LOCK_FAILED)
                return SessionFailureCode.MULTICAST_LOCK_FAILED
            }
            try {
                val lock = wifiManager.createMulticastLock("Morsecode.LAN.discovery")
                lock.setReferenceCounted(false)
                lock.acquire()
                multicastLock = lock
            } catch (_: RuntimeException) {
                stop(SessionFailureCode.MULTICAST_LOCK_FAILED)
                return SessionFailureCode.MULTICAST_LOCK_FAILED
            }

            try {
                val server = ServerSocket()
                controlServer = server
                server.reuseAddress = false
                server.bind(InetSocketAddress(NetworkPorts.PEER_CONTROL), MAX_CONTROL_SESSIONS)
                server.soTimeout = SOCKET_POLL_TIMEOUT_MILLIS
            } catch (_: BindException) {
                stop(SessionFailureCode.CONTROL_PORT_CONFLICT)
                return SessionFailureCode.CONTROL_PORT_CONFLICT
            } catch (_: IOException) {
                stop(SessionFailureCode.CONTROL_PORT_CONFLICT)
                return SessionFailureCode.CONTROL_PORT_CONFLICT
            } catch (_: SecurityException) {
                stop(SessionFailureCode.CONTROL_PORT_CONFLICT)
                return SessionFailureCode.CONTROL_PORT_CONFLICT
            }

            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (manager == null) {
                stop(SessionFailureCode.NETWORK_UNAVAILABLE)
                return SessionFailureCode.NETWORK_UNAVAILABLE
            }
            connectivityManager = manager
            val callback = createNetworkCallback()
            networkCallback = callback
            try {
                val request = NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build()
                manager.registerNetworkCallback(request, callback)
                networkCallbackRegistered = true
                refreshNetworkTarget()
            } catch (_: RuntimeException) {
                stop(SessionFailureCode.NETWORK_BIND_FAILED)
                return SessionFailureCode.NETWORK_BIND_FAILED
            }

            phase = DiscoveryPhase.WAITING_FOR_NETWORK
            discoveryThread = daemonThread("morsec-lan-discovery", ::discoveryLoop).also { it.start() }
            acceptThread = daemonThread("morsec-lan-accept", ::acceptLoop).also { it.start() }
            notifyDiscoveryChanged()
            return null
        }

        override fun snapshot(): List<DiscoveredPeer> = snapshotAndPruneRoutes()

        override fun diagnostics(): DiscoveryDiagnostics = DiscoveryDiagnostics(
            phase = phase,
            peerCount = snapshotAndPruneRoutes().size,
            beaconsAccepted = acceptedBeacons.get(),
            beaconsRejected = rejectedBeacons.get(),
            callbacksDropped = callbackDrops.get(),
            networkChanges = networkChanges.get(),
            lastFailure = lastFailure,
        )

        override fun connectSelected(
            peerInstanceId: PeerInstanceId,
            listener: ControlConnectionListener,
        ): CancellableOperation {
            if (closed.get()) {
                postControlFailure(listener, SessionFailureCode.OPERATION_CANCELLED)
                return NoopOperation
            }
            val selected = try {
                synchronized(registryLock) {
                    registry.selected(peerInstanceId, nowElapsedMillis())
                }
            } catch (_: IllegalArgumentException) {
                null
            }
            val selectedNetwork = peerNetworks[peerInstanceId]
            if (selected == null || selectedNetwork == null || selected.advertisement.transport != TransportKind.LAN ||
                selectedNetwork != boundNetwork?.network || boundNetwork != desiredNetwork.get()
            ) {
                postControlFailure(listener, SessionFailureCode.PEER_EXPIRED)
                return NoopOperation
            }
            if (!reserveControlCapacity()) {
                postControlFailure(listener, SessionFailureCode.CONTROL_CAPACITY_REACHED)
                return NoopOperation
            }
            val attempt = ControlAttempt(listener)
            activeOperations += attempt
            if (closed.get()) {
                attempt.cancel()
                return attempt
            }
            val task = FutureTask<Unit>({
                runOutboundHandshake(attempt, selected, selectedNetwork)
                Unit
            })
            attempt.setFuture(task)
            try {
                requireNotNull(controlExecutor).execute(task)
            } catch (_: RejectedExecutionException) {
                attempt.fail(SessionFailureCode.OPERATION_QUEUE_FULL)
            } catch (_: RuntimeException) {
                attempt.fail(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
            }
            return attempt
        }

        override fun close() {
            stop(null)
        }

        private fun createNetworkCallback(): ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshNetworkTarget()
                override fun onLost(network: Network) = refreshNetworkTarget()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    refreshNetworkTarget()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    refreshNetworkTarget()
            }

        private fun refreshNetworkTarget() {
            if (closed.get()) return
            val manager = connectivityManager ?: return
            val target = try {
                manager.allNetworks.asSequence()
                    .mapNotNull { network ->
                        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                            return@mapNotNull null
                        }
                        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        if (!isWifi && !isEthernet) return@mapNotNull null
                        val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
                        val interfaceName = properties.interfaceName ?: return@mapNotNull null
                        val networkInterface = try {
                            NetworkInterface.getByName(interfaceName)
                        } catch (_: SocketException) {
                            null
                        } ?: return@mapNotNull null
                        if (!networkInterface.isUp || !networkInterface.supportsMulticast()) return@mapNotNull null
                        NetworkTarget(network, interfaceName, if (isWifi) 0 else 1)
                    }
                    .minWithOrNull(compareBy<NetworkTarget>({ it.priority }, { it.interfaceName }))
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            val previous = desiredNetwork.getAndSet(target)
            if (previous != target) {
                networkChanges.incrementAndGet()
                networkDirty.set(true)
                synchronized(signal) { signal.notifyAll() }
            }
        }

        private fun discoveryLoop() {
            var nextBeaconElapsedMillis = 0L
            val receiveBuffer = ByteArray(LanBeaconCodec.MAX_DATAGRAM_SIZE_BYTES + 1)
            while (!closed.get()) {
                val now = safeNowOrStop() ?: return
                if (now - startedElapsedMillis >= MAX_DISCOVERY_LEASE_MILLIS) {
                    stop(SessionFailureCode.DISCOVERY_LEASE_EXPIRED)
                    return
                }
                if (networkDirty.getAndSet(false)) {
                    val failure = bindCurrentNetwork()
                    if (failure != null) {
                        stop(failure)
                        return
                    }
                }
                val socket = multicastSocket
                if (socket == null) {
                    waitForSignal(SOCKET_POLL_TIMEOUT_MILLIS.toLong())
                    continue
                }
                if (now >= nextBeaconElapsedMillis) {
                    try {
                        val bytes = LanBeaconCodec.encode(LanBeacon(localProfile))
                        val packet = DatagramPacket(bytes, bytes.size, multicastGroup(), NetworkPorts.DISCOVERY_BEACON)
                        socket.send(packet)
                    } catch (_: IOException) {
                        if (networkDirty.get()) continue
                        stop(SessionFailureCode.NETWORK_BIND_FAILED)
                        return
                    } catch (_: RuntimeException) {
                        stop(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
                        return
                    }
                    nextBeaconElapsedMillis = now + NetworkPorts.BEACON_INTERVAL_MILLIS
                }

                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                try {
                    socket.receive(packet)
                    if (packet.length > LanBeaconCodec.MAX_DATAGRAM_SIZE_BYTES) {
                        rejectedBeacons.incrementAndGet()
                    } else {
                        receiveBeacon(packet)
                    }
                } catch (_: SocketTimeoutException) {
                    // The bounded timeout lets the lease observe cancellation and network changes.
                } catch (_: SocketException) {
                    if (closed.get()) return
                    if (networkDirty.get()) continue
                    stop(SessionFailureCode.NETWORK_BIND_FAILED)
                    return
                } catch (_: IOException) {
                    if (!closed.get()) stop(SessionFailureCode.NETWORK_BIND_FAILED)
                    return
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun bindCurrentNetwork(): SessionFailureCode? {
            val target = desiredNetwork.get()
            if (boundNetwork != target) {
                synchronized(registryLock) {
                    registry.clear()
                    peerNetworks.clear()
                }
                activeOperations.toList().forEach { it.fail(SessionFailureCode.NETWORK_UNAVAILABLE) }
                incomingPermits.keys.toList().forEach { socket ->
                    closeSocket(socket)
                    inFlightSockets.remove(socket)
                    releaseIncomingPermit(socket)
                }
                activeSessions.toList().forEach { it.close() }
            }
            boundNetwork = target
            closeMulticastSocket()
            if (target == null) {
                phase = DiscoveryPhase.WAITING_FOR_NETWORK
                notifyDiscoveryChanged()
                return null
            }
            return try {
                val networkInterface = NetworkInterface.getByName(target.interfaceName)
                    ?: return SessionFailureCode.NETWORK_BIND_FAILED
                val socket = MulticastSocket(null)
                multicastSocket = socket
                socket.reuseAddress = false
                target.network.bindSocket(socket)
                socket.bind(InetSocketAddress(NetworkPorts.DISCOVERY_BEACON))
                socket.soTimeout = SOCKET_POLL_TIMEOUT_MILLIS
                socket.timeToLive = 1
                socket.networkInterface = networkInterface
                multicastInterface = networkInterface
                val group = multicastGroup()
                socket.joinGroup(group)
                phase = DiscoveryPhase.SCANNING
                notifyDiscoveryChanged()
                null
            } catch (_: BindException) {
                SessionFailureCode.DISCOVERY_PORT_CONFLICT
            } catch (_: IOException) {
                SessionFailureCode.NETWORK_BIND_FAILED
            } catch (_: SecurityException) {
                SessionFailureCode.NETWORK_BIND_FAILED
            } catch (_: RuntimeException) {
                SessionFailureCode.NETWORK_BIND_FAILED
            }
        }

        @Suppress("DEPRECATION")
        private fun closeMulticastSocket() {
            val socket = multicastSocket
            val networkInterface = multicastInterface
            multicastSocket = null
            multicastInterface = null
            if (socket != null && !socket.isClosed) {
                if (networkInterface != null) {
                    try {
                        socket.leaveGroup(multicastGroup())
                    } catch (_: IOException) {
                        // Socket close below is the authoritative release path.
                    } catch (_: RuntimeException) {
                        // A concurrently changing network can invalidate the interface.
                    }
                }
                try {
                    socket.close()
                } catch (_: RuntimeException) {
                    // Close remains best-effort and idempotent.
                }
            }
        }

        private fun receiveBeacon(packet: DatagramPacket) {
            val source = packet.address as? Inet4Address
            val route = boundNetwork
            if (source == null || packet.port != NetworkPorts.DISCOVERY_BEACON ||
                route == null || route != desiredNetwork.get()
            ) {
                rejectedBeacons.incrementAndGet()
                return
            }
            val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val beacon = when (val decoded = LanBeaconCodec.decode(bytes)) {
                is LanBeaconDecodeResult.Success -> decoded.beacon
                is LanBeaconDecodeResult.Invalid -> {
                    rejectedBeacons.incrementAndGet()
                    return
                }
            }
            val advertisedId = beacon.profile.peerInstanceId
            if (advertisedId == localPeerInstanceId) return
            val ipAddress = source.hostAddress?.substringBefore('%') ?: run {
                rejectedBeacons.incrementAndGet()
                return
            }
            val advertisement = try {
                PeerAdvertisement(
                    peerInstanceId = advertisedId,
                    displayName = beacon.profile.displayName,
                    appVersion = beacon.profile.appVersion,
                    transport = TransportKind.LAN,
                    endpoint = TransportEndpoint(TransportKind.LAN.id, ipAddress),
                    protocolRange = beacon.profile.protocolRange,
                    capabilities = beacon.profile.capabilities,
                )
            } catch (_: IllegalArgumentException) {
                rejectedBeacons.incrementAndGet()
                return
            }
            try {
                synchronized(registryLock) {
                    val observedAt = nowElapsedMillis()
                    val peers = registry.observe(advertisement, observedAt)
                    peerNetworks[advertisedId] = route.network
                    prunePeerRoutes(peers)
                }
                acceptedBeacons.incrementAndGet()
                notifyDiscoveryChanged()
            } catch (_: IllegalArgumentException) {
                rejectedBeacons.incrementAndGet()
            }
        }

        private fun acceptLoop() {
            val server = controlServer ?: return
            while (!closed.get()) {
                try {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    socket.soTimeout = CONTROL_TIMEOUT_MILLIS
                    if (!reserveControlCapacity()) {
                        closeSocket(socket)
                        continue
                    }
                    inFlightSockets += socket
                    incomingPermits[socket] = AtomicBoolean(false)
                    submitIncoming(socket)
                } catch (_: SocketTimeoutException) {
                    // Periodic wake-up for explicit lease cancellation.
                } catch (_: SocketException) {
                    if (closed.get()) return
                    stop(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
                    return
                } catch (_: IOException) {
                    if (!closed.get()) stop(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
                    return
                } catch (_: RuntimeException) {
                    if (!closed.get()) stop(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
                    return
                }
            }
        }

        private fun submitIncoming(socket: Socket) {
            if (closed.get()) {
                inFlightSockets.remove(socket)
                closeSocket(socket)
                releaseIncomingPermit(socket)
                return
            }
            val task = FutureTask<Unit>({
                runIncomingHandshake(socket)
                Unit
            })
            try {
                requireNotNull(controlExecutor).execute(task)
            } catch (_: RejectedExecutionException) {
                inFlightSockets.remove(socket)
                closeSocket(socket)
                releaseIncomingPermit(socket)
            } catch (_: RuntimeException) {
                inFlightSockets.remove(socket)
                closeSocket(socket)
                releaseIncomingPermit(socket)
            }
        }

        private fun runIncomingHandshake(socket: Socket) {
            try {
                if (closed.get()) {
                    closeSocket(socket)
                    inFlightSockets.remove(socket)
                    releaseIncomingPermit(socket)
                    return
                }
                val message = readHandshake(socket)
                val hello = message as? SessionHello ?: throw HandshakeProtocolException(
                    SessionFailureCode.HANDSHAKE_INVALID,
                )
                val remoteAddress = (socket.inetAddress as? Inet4Address)?.hostAddress
                    ?: throw HandshakeProtocolException(SessionFailureCode.PEER_IDENTITY_MISMATCH)
                val discovered = synchronized(registryLock) {
                    registry.selected(hello.senderPeerInstanceId, nowElapsedMillis())
                } ?: throw HandshakeProtocolException(SessionFailureCode.PEER_IDENTITY_MISMATCH)
                if (discovered.advertisement.endpoint.opaqueValue != remoteAddress ||
                    profileFromAdvertisement(discovered.advertisement) != hello.profile ||
                    TransportKind.LAN !in hello.profile.capabilities.supportedTransports ||
                    peerNetworks[hello.senderPeerInstanceId] != boundNetwork?.network ||
                    boundNetwork != desiredNetwork.get()
                ) {
                    throw HandshakeProtocolException(SessionFailureCode.PEER_IDENTITY_MISMATCH)
                }
                when (val decision = SessionHandshakeNegotiator.accept(localProfile, hello)) {
                    is SessionHandshakeDecision.Rejected -> {
                        writeHandshake(socket, decision.response)
                        throw HandshakeProtocolException(decision.failure.code)
                    }
                    is SessionHandshakeDecision.Accepted -> {
                        writeHandshake(socket, decision.response)
                        deliverIncomingSession(socket, decision.session)
                    }
                }
            } catch (failure: HandshakeProtocolException) {
                closeSocket(socket)
                inFlightSockets.remove(socket)
                releaseIncomingPermit(socket)
                recordFailure(failure.failureCode)
            } catch (_: SocketTimeoutException) {
                closeSocket(socket)
                inFlightSockets.remove(socket)
                releaseIncomingPermit(socket)
                recordFailure(SessionFailureCode.CONTROL_TIMEOUT)
            } catch (_: IOException) {
                closeSocket(socket)
                inFlightSockets.remove(socket)
                releaseIncomingPermit(socket)
                recordFailure(SessionFailureCode.CONTROL_CONNECT_FAILED)
            } catch (_: RuntimeException) {
                closeSocket(socket)
                inFlightSockets.remove(socket)
                releaseIncomingPermit(socket)
                recordFailure(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
            }
        }

        private fun runOutboundHandshake(
            attempt: ControlAttempt,
            selected: DiscoveredPeer,
            network: Network,
        ) {
            if (closed.get() || network != boundNetwork?.network || boundNetwork != desiredNetwork.get()) {
                attempt.fail(SessionFailureCode.PEER_EXPIRED)
                return
            }
            val address = parseIpv4(selected.advertisement.endpoint.opaqueValue)
            if (address == null) {
                attempt.fail(SessionFailureCode.PEER_EXPIRED)
                return
            }
            val socket = Socket()
            if (!attempt.attach(socket)) return
            try {
                socket.tcpNoDelay = true
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(address, NetworkPorts.PEER_CONTROL), CONTROL_TIMEOUT_MILLIS)
                socket.soTimeout = CONTROL_TIMEOUT_MILLIS
                val hello = SessionHello(
                    attemptId = SessionId(UUID.randomUUID().toString().replace("-", "")),
                    senderPeerInstanceId = localPeerInstanceId,
                    targetPeerInstanceId = selected.peerInstanceId,
                    profile = localProfile,
                )
                writeHandshake(socket, hello)
                when (val response = readHandshake(socket)) {
                    is app.morsecode.core.transfer.session.SessionAccept -> {
                        if (profileFromAdvertisement(selected.advertisement) != response.profile) {
                            attempt.fail(SessionFailureCode.PEER_IDENTITY_MISMATCH)
                            return
                        }
                        when (val decision = SessionHandshakeNegotiator.verifyAccepted(
                            local = localProfile,
                            expectedSelectedPeer = selected.peerInstanceId,
                            sentHello = hello,
                            accept = response,
                        )) {
                            is SessionHandshakeDecision.Rejected -> attempt.fail(decision.failure.code)
                            is SessionHandshakeDecision.Accepted -> {
                                val session = createControlSession(socket, decision.session, attempt::releasePermit)
                                attempt.completeConnected(session)
                            }
                        }
                    }
                    is SessionReject -> {
                        if (response.attemptId != hello.attemptId ||
                            response.senderPeerInstanceId != selected.peerInstanceId ||
                            response.targetPeerInstanceId != localPeerInstanceId
                        ) {
                            attempt.fail(SessionFailureCode.PEER_IDENTITY_MISMATCH)
                        } else {
                            attempt.fail(SessionFailureCode.HANDSHAKE_REJECTED)
                        }
                    }
                    else -> attempt.fail(SessionFailureCode.HANDSHAKE_INVALID)
                }
            } catch (failure: HandshakeProtocolException) {
                attempt.fail(failure.failureCode)
            } catch (_: SocketTimeoutException) {
                attempt.fail(SessionFailureCode.CONTROL_TIMEOUT)
            } catch (_: IOException) {
                attempt.fail(SessionFailureCode.CONTROL_CONNECT_FAILED)
            } catch (_: RuntimeException) {
                attempt.fail(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
            }
        }

        private fun deliverIncomingSession(socket: Socket, negotiated: app.morsecode.core.transfer.session.NegotiatedSession) {
            if (closed.get()) {
                inFlightSockets.remove(socket)
                closeSocket(socket)
                releaseIncomingPermit(socket)
                return
            }
            val permitReleased = incomingPermits[socket] ?: AtomicBoolean(false)
            val session = createControlSession(socket, negotiated) {
                if (permitReleased.compareAndSet(false, true)) releaseControlCapacity()
            }
            incomingPermits.remove(socket, permitReleased)
            if (!postCallback(
                    action = {
                        if (closed.get()) session.close()
                        else listener.onIncomingControlSession(ControlConnectionResult.Connected(session))
                    },
                    onFailure = { session.close() },
                )
            ) {
                session.close()
            }
        }

        private fun createControlSession(
            socket: Socket,
            negotiated: app.morsecode.core.transfer.session.NegotiatedSession,
            releasePermit: () -> Unit,
        ): LanControlSession {
            lateinit var session: LanControlSession
            session = LanControlSession(socket, negotiated) {
                activeSessions.remove(session)
                inFlightSockets.remove(socket)
                releasePermit()
            }
            activeSessions += session
            inFlightSockets.remove(socket)
            if (closed.get()) session.close()
            return session
        }

        private fun writeHandshake(socket: Socket, message: SessionHandshakeMessage) {
            val frame = SessionHandshakeCodec.encode(message)
            socket.getOutputStream().apply {
                write(frame)
                flush()
            }
        }

        private fun readHandshake(socket: Socket): SessionHandshakeMessage {
            val input = DataInputStream(socket.getInputStream())
            val headerBytes = ByteArray(SessionHandshakeCodec.HEADER_SIZE_BYTES)
            input.readFully(headerBytes)
            val header = when (val decoded = SessionHandshakeCodec.decodeHeader(headerBytes)) {
                is SessionHandshakeHeaderResult.Valid -> decoded.header
                is SessionHandshakeHeaderResult.Invalid -> throw HandshakeProtocolException(decoded.failure.code)
            }
            val frame = ByteArray(SessionHandshakeCodec.HEADER_SIZE_BYTES + header.payloadLength)
            headerBytes.copyInto(frame)
            input.readFully(frame, SessionHandshakeCodec.HEADER_SIZE_BYTES, header.payloadLength)
            return when (val decoded = SessionHandshakeCodec.decode(frame)) {
                is SessionHandshakeDecodeResult.Success -> decoded.message
                is SessionHandshakeDecodeResult.Invalid -> throw HandshakeProtocolException(decoded.failure.code)
            }
        }

        private fun snapshotAndPruneRoutes(): List<DiscoveredPeer> = synchronized(registryLock) {
            val peers = registry.snapshot(nowElapsedMillis())
            prunePeerRoutes(peers)
            peers
        }

        private fun prunePeerRoutes(peers: List<DiscoveredPeer>) {
            val retained = peers.mapTo(hashSetOf()) { it.peerInstanceId }
            peerNetworks.keys.toList().filterNot(retained::contains).forEach { peerNetworks.remove(it) }
        }

        private fun notifyDiscoveryChanged() {
            val currentPhase = phase
            val currentSnapshot = try {
                snapshotAndPruneRoutes()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
            val currentDiagnostics = try {
                DiscoveryDiagnostics(
                    phase = currentPhase,
                    peerCount = currentSnapshot.size,
                    beaconsAccepted = acceptedBeacons.get(),
                    beaconsRejected = rejectedBeacons.get(),
                    callbacksDropped = callbackDrops.get(),
                    networkChanges = networkChanges.get(),
                    lastFailure = lastFailure,
                )
            } catch (_: IllegalArgumentException) {
                return
            }
            postCallback(
                action = { listener.onDiscoveryChanged(currentPhase, currentSnapshot, currentDiagnostics) },
                onFailure = {},
            )
        }

        private fun postControlFailure(listener: ControlConnectionListener, code: SessionFailureCode) {
            postCallback(
                action = { listener.onResult(ControlConnectionResult.Failed(SessionFailure(code))) },
                onFailure = {},
            )
        }

        private fun recordFailure(code: SessionFailureCode) {
            if (closed.get()) return
            lastFailure = code
            notifyDiscoveryChanged()
        }

        private fun postCallback(action: () -> Unit, onFailure: () -> Unit): Boolean {
            val executor = callbackExecutor ?: run {
                callbackDrops.incrementAndGet()
                onFailure()
                return false
            }
            return try {
                executor.execute {
                    try {
                        action()
                    } catch (_: RuntimeException) {
                        callbackDrops.incrementAndGet()
                        onFailure()
                    }
                }
                true
            } catch (_: RejectedExecutionException) {
                callbackDrops.incrementAndGet()
                onFailure()
                false
            } catch (_: RuntimeException) {
                callbackDrops.incrementAndGet()
                onFailure()
                false
            }
        }

        private fun reserveControlCapacity(): Boolean = controlPermits.tryAcquire()

        private fun releaseIncomingPermit(socket: Socket) {
            val released = incomingPermits.remove(socket) ?: return
            if (released.compareAndSet(false, true)) releaseControlCapacity()
        }

        private fun releaseControlCapacity() {
            controlPermits.release()
        }

        private fun safeNowOrStop(): Long? = try {
            nowElapsedMillis()
        } catch (_: RuntimeException) {
            stop(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
            null
        }

        private fun nowElapsedMillis(): Long {
            val now = clock.nowMillis()
            require(now >= 0L) { "monotonic clock returned a negative value" }
            return now
        }

        private fun waitForSignal(timeoutMillis: Long) {
            try {
                synchronized(signal) {
                    if (!closed.get() && !networkDirty.get()) signal.wait(timeoutMillis)
                }
            } catch (_: InterruptedException) {
                if (!closed.get()) stop(SessionFailureCode.INTERNAL_TRANSPORT_FAILURE)
            }
        }

        private fun signalWorkers() {
            synchronized(signal) { signal.notifyAll() }
        }

        private fun stop(failureCode: SessionFailureCode?) {
            if (!closed.compareAndSet(false, true)) return
            if (failureCode != null) lastFailure = failureCode
            phase = if (failureCode == null || failureCode == SessionFailureCode.DISCOVERY_LEASE_EXPIRED) {
                DiscoveryPhase.STOPPED
            } else {
                DiscoveryPhase.FAILED
            }
            synchronized(registryLock) {
                registry.clear()
                peerNetworks.clear()
            }
            boundNetwork = null
            notifyDiscoveryChanged()
            signalWorkers()

            activeOperations.toList().forEach { it.cancel() }
            incomingPermits.keys.toList().forEach { socket ->
                closeSocket(socket)
                inFlightSockets.remove(socket)
                releaseIncomingPermit(socket)
            }
            inFlightSockets.toList().forEach { closeSocket(it) }
            activeSessions.toList().forEach { it.close() }
            closeMulticastSocket()
            try {
                controlServer?.close()
            } catch (_: IOException) {
                // Closing the lease is best-effort and never exposes socket details.
            }
            controlServer = null

            if (networkCallbackRegistered) {
                try {
                    val manager = connectivityManager
                    val callback = networkCallback
                    if (manager != null && callback != null) manager.unregisterNetworkCallback(callback)
                } catch (_: RuntimeException) {
                    // A network callback can already have been unregistered by the platform.
                }
                networkCallbackRegistered = false
            }
            val lock = multicastLock
            multicastLock = null
            if (lock?.isHeld == true) {
                try {
                    lock.release()
                } catch (_: RuntimeException) {
                    // The lock is owned by this lease and release is idempotent here.
                }
            }
            controlExecutor?.shutdownNow()
            callbackExecutor?.shutdownNow()
            discoveryThread?.interrupt()
            acceptThread?.interrupt()
            onClosed(this)
        }

        private fun profileFromAdvertisement(advertisement: PeerAdvertisement): SessionPeerProfile =
            SessionPeerProfile(
                peerInstanceId = advertisement.peerInstanceId,
                displayName = advertisement.displayName,
                appVersion = advertisement.appVersion,
                protocolRange = advertisement.protocolRange,
                capabilities = advertisement.capabilities,
            )

        private inner class ControlAttempt(
            private val resultListener: ControlConnectionListener,
        ) : CancellableOperation {
            private val state = AtomicInteger(ATTEMPT_PENDING)
            private val permitReleased = AtomicBoolean(false)

            @Volatile
            private var socket: Socket? = null

            @Volatile
            private var future: FutureTask<Unit>? = null

            fun setFuture(task: FutureTask<Unit>) {
                future = task
                if (state.get() == ATTEMPT_CANCELLED) task.cancel(true)
            }

            fun attach(value: Socket): Boolean {
                if (state.get() != ATTEMPT_PENDING || closed.get()) {
                    closeSocket(value)
                    return false
                }
                socket = value
                inFlightSockets += value
                if (state.get() != ATTEMPT_PENDING || closed.get()) {
                    closeSocket(value)
                    inFlightSockets.remove(value)
                    return false
                }
                return true
            }

            fun releasePermit() {
                if (permitReleased.compareAndSet(false, true)) releaseControlCapacity()
            }

            fun fail(code: SessionFailureCode) {
                if (!state.compareAndSet(ATTEMPT_PENDING, ATTEMPT_COMPLETED)) return
                activeOperations.remove(this)
                socket?.let {
                    inFlightSockets.remove(it)
                    closeSocket(it)
                }
                releasePermit()
                recordFailure(code)
                postControlFailure(resultListener, code)
            }

            fun completeConnected(session: LanControlSession) {
                if (!state.compareAndSet(ATTEMPT_PENDING, ATTEMPT_COMPLETED)) {
                    session.close()
                    return
                }
                activeOperations.remove(this)
                if (closed.get() || !postCallback(
                        action = {
                            if (closed.get()) session.close()
                            else resultListener.onResult(ControlConnectionResult.Connected(session))
                        },
                        onFailure = { session.close() },
                    )
                ) {
                    session.close()
                }
            }

            override fun cancel() {
                if (!state.compareAndSet(ATTEMPT_PENDING, ATTEMPT_CANCELLED)) return
                future?.cancel(true)
                socket?.let {
                    inFlightSockets.remove(it)
                    closeSocket(it)
                }
                activeOperations.remove(this)
                releasePermit()
            }
        }

        private inner class LanControlSession(
            private val socket: Socket,
            override val negotiated: app.morsecode.core.transfer.session.NegotiatedSession,
            private val onClosed: () -> Unit,
        ) : ControlSession {
            private val closedSession = AtomicBoolean(false)

            override fun close() {
                if (!closedSession.compareAndSet(false, true)) return
                closeSocket(socket)
                onClosed()
            }

            override fun toString(): String = "LanControlSession([redacted])"
        }

        private data class NetworkTarget(
            val network: Network,
            val interfaceName: String,
            val priority: Int,
        )

        private class HandshakeProtocolException(val failureCode: SessionFailureCode) : IOException()

        private object NoopOperation : CancellableOperation {
            override fun cancel() = Unit
        }

        private companion object {
            const val MAX_DISCOVERY_LEASE_MILLIS: Long = 5 * 60 * 1_000L
            const val MAX_CONTROL_SESSIONS: Int = 4
            const val MAX_PENDING_CONTROL_TASKS: Int = 4
            const val MAX_CALLBACK_QUEUE: Int = 32
            const val SOCKET_POLL_TIMEOUT_MILLIS: Int = 250
            const val CONTROL_TIMEOUT_MILLIS: Int = 5_000
            const val ATTEMPT_PENDING: Int = 0
            const val ATTEMPT_COMPLETED: Int = 1
            const val ATTEMPT_CANCELLED: Int = 2
        }
    }

}
