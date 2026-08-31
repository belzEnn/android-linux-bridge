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

    fun start() {
        if (discovering) return
        discovering = true
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        notifyLog("Searching for computers on the local network")
    }

    fun stop() {
        if (!discovering) return
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        discovering = false
    }

    fun resolve(computer: DiscoveredComputer, onResolved: (String, Int) -> Unit) {
        onResolved(computer.host, computer.port)
    }

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            notifyLog("Computer search failed ($errorCode)")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            resolveDiscoveredService(serviceInfo)
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            computers.remove(serviceInfo.serviceName)
            publishComputers()
        }
    }

    private fun resolveDiscoveredService(serviceInfo: NsdServiceInfo) {
        if (!resolving.add(serviceInfo.serviceName)) return
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(resolvingService: NsdServiceInfo, errorCode: Int) {
                resolving.remove(resolvingService.serviceName)
                notifyLog("Could not read discovered computer ($errorCode)")
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                resolving.remove(resolvedService.serviceName)
                addResolvedComputer(resolvedService)
            }
        })
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

    private fun publishComputers() = mainHandler.post {
        onComputersChanged(computers.values.sortedBy { it.computerName })
    }

    private fun notifyLog(message: String) = mainHandler.post { onLog(message) }

    private companion object {
        const val SERVICE_TYPE = "_albridge._tcp."
        const val PROTOCOL_VERSION = "1"
    }
}
