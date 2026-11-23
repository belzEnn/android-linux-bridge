package io.github.belzenn.androidlinuxbridge.features.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.belzenn.androidlinuxbridge.BridgeState
import io.github.belzenn.androidlinuxbridge.R
import io.github.belzenn.androidlinuxbridge.settings.ConnectionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class AudioTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening = false
    private var switching = false
    private var onPc = false
    private var a2dp: BluetoothProfile? = null
    private var profileListener: BluetoothProfile.ServiceListener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onStartListening() {
        super.onStartListening()
        if (listening) return
        listening = true
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    override fun onStopListening() {
        stopListening()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (switching) return
        val computer = ConnectionSettings.bondedComputer(this) ?: return
        switching = true
        render(available = true)
        scope.launch {
            try {
                onPc = AudioRoute.toggle(applicationContext, computer)
            } catch (_: TimeoutCancellationException) {
                showError(getString(R.string.audio_tile_timeout))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showError(exception.message ?: getString(R.string.audio_tile_failed))
            } finally {
                switching = false
                if (listening) render(available = ConnectionSettings.bondedComputer(this@AudioTileService) != null)
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val computer = ConnectionSettings.bondedComputer(this)
        if (computer != null && listening && profileListener == null) watchProfile()
        if (computer == null) {
            onPc = false
        } else if (a2dp != null) {
            onPc = try {
                a2dp?.getConnectionState(computer) == BluetoothProfile.STATE_CONNECTED
            } catch (_: SecurityException) {
                false
            }
        }
        render(available = computer != null)
    }

    private fun watchProfile() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profileListener !== this) {
                    adapter.closeProfileProxy(profile, proxy)
                    return
                }
                a2dp = proxy
                refresh()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profileListener === this) {
                    a2dp = null
                    onPc = false
                    refresh()
                }
            }
        }
        profileListener = listener
        val registered = try {
            adapter.getProfileProxy(applicationContext, listener, BluetoothProfile.A2DP)
        } catch (_: SecurityException) {
            false
        }
        if (!registered) profileListener = null
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        unregisterReceiver(receiver)
        profileListener = null
        a2dp?.let { proxy ->
            getSystemService(BluetoothManager::class.java)?.adapter
                ?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        }
        a2dp = null
    }

    private fun render(available: Boolean) {
        qsTile?.apply {
            label = getString(R.string.audio_tile_label)
            state = when {
                !available || switching -> Tile.STATE_UNAVAILABLE
                onPc -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(when {
                    switching -> R.string.audio_tile_switching
                    !available -> R.string.audio_tile_setup
                    onPc -> R.string.audio_tile_pc
                    else -> R.string.audio_tile_phone
                })
            }
            updateTile()
        }
    }

    private fun showError(message: String) {
        BridgeState.addLog("Audio control: $message")
        Toast.makeText(this, getString(R.string.audio_tile_error, message), Toast.LENGTH_SHORT).show()
    }
}
