package io.github.belzenn.androidlinuxbridge.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

data class DiscoveredComputer(
    val serviceName: String,
    val computerName: String,
    val distribution: String,
    val host: String,
    val port: Int
)

class ComputerDiscoveryManager(
    context: Context,
    private val onComputersChanged: (List<DiscoveredComputer>) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val computers = linkedMapOf<String, DiscoveredComputer>()
    private val resolving = mutableSetOf<String>()
    private var discovering = false
    private var generation = 0
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discovering) return
        discovering = true
        val current = ++generation
        val callback = createListener(current)
        listener = callback
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (exception: RuntimeException) {
            discovering = false
            notifyLog("Computer search unavailable: ${exception.message}")
        }
        notifyLog("Searching for computers on the local network")
    }

    fun stop() {
        generation++
        listener?.let { callback -> runCatching { nsdManager.stopServiceDiscovery(callback) } }
        listener = null
        discovering = false
        computers.clear()
        resolving.clear()
    }

    fun resolve(computer: DiscoveredComputer, onResolved: (String, Int) -> Unit) {
        onResolved(computer.host, computer.port)
    }

    private fun createListener(current: Int) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            mainHandler.post { if (current == generation) {
                discovering = false
                notifyLog("Computer search failed ($errorCode)")
            } }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            mainHandler.post { if (current == generation) discovering = false }
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            mainHandler.post { if (current == generation) resolveDiscoveredService(serviceInfo, current) }
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            mainHandler.post { if (current == generation) {
                computers.remove(serviceInfo.serviceName)
                publishComputers()
            } }
        }
    }

    private fun resolveDiscoveredService(serviceInfo: NsdServiceInfo, current: Int) {
        if (!resolving.add(serviceInfo.serviceName)) return
        try { nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(resolvingService: NsdServiceInfo, errorCode: Int) {
                mainHandler.post { if (current == generation) {
                    resolving.remove(resolvingService.serviceName)
                    notifyLog("Could not read discovered computer ($errorCode)")
                } }
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                mainHandler.post { if (current == generation) {
                    resolving.remove(resolvedService.serviceName)
                    addResolvedComputer(resolvedService)
                } }
            }
        }) } catch (exception: RuntimeException) {
            resolving.remove(serviceInfo.serviceName)
            notifyLog("Could not resolve computer: ${exception.message}")
        }
    }

    private fun addResolvedComputer(serviceInfo: NsdServiceInfo) {
        val host = serviceInfo.host?.hostAddress ?: return
        if (serviceInfo.port !in 1..65535) return
        val attributes = serviceInfo.attributes
        val version = attributes["protocol_version"]?.decodeToString()
        if (version != PROTOCOL_VERSION) return
        val computer = DiscoveredComputer(
            serviceName = serviceInfo.serviceName,
            computerName = attributes["computer_name"]?.decodeToString()
                ?: serviceInfo.serviceName,
            distribution = attributes["distribution"]?.decodeToString() ?: "Linux",
            host = host,
            port = serviceInfo.port
        )
        computers[computer.serviceName] = computer
        publishComputers()
    }

    private fun publishComputers() {
        onComputersChanged(computers.values.sortedBy { it.computerName })
    }

    private fun notifyLog(message: String) = mainHandler.post { onLog(message) }

    private companion object {
        const val SERVICE_TYPE = "_albridge._tcp."
        const val PROTOCOL_VERSION = "2"
    }
}
