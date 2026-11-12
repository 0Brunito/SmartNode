package com.example.smartnode.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.util.UUID

class SmartNodeRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val brokerUrl: String = "tcp://test.mosquitto.org:1883"
) {
    private val dao = AppDatabase.get(context).smartNodeDao()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var client: MqttClient? = null

    val latestTelemetry: Flow<TelemetryEntity?> = dao.observeLatestTelemetry()
    val pendingCommandCount: Flow<Int> = dao.observePendingCommandCount()

    private val _firebaseConnected = MutableStateFlow(false)
    val firebaseConnected: StateFlow<Boolean> = _firebaseConnected

    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected

    // Modo offline: sin MQTT (control) o sin Firebase (historial)
    val isOffline: Flow<Boolean> = combine(mqttConnected, firebaseConnected) { mqtt, fb ->
        !mqtt || !fb
    }

    private val firebaseRoot: DatabaseReference? by lazy {
        try { FirebaseDatabase.getInstance().getReference("smartnode") }
        catch (_: Exception) { null }
    }

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch(Dispatchers.IO) { syncPendingData() }
            }
        })

        try {
            FirebaseDatabase.getInstance().getReference(".info/connected")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        _firebaseConnected.value = snapshot.getValue(Boolean::class.java) == true
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
        } catch (_: Exception) {
            // Firebase mal configurado: el indicador queda en "desconectado", la app sigue viva
        }

        // Monitoreo del estado MQTT (plan de contingencia, sin crashear)
        scope.launch {
            while (true) {
                _mqttConnected.value = client?.isConnected == true
                delay(3000)
            }
        }
    }

    fun connect() {
        scope.launch(Dispatchers.IO) {
            try {
                client = MqttClient(brokerUrl, "smartnode-${UUID.randomUUID()}", null).apply {
                    setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String) {
                            subscribe(TOPIC_TELEMETRY, 1)
                        }

                        override fun connectionLost(cause: Throwable?) {}

                        override fun messageArrived(topic: String, message: MqttMessage) {
                            // Payload cifrado por el ESP32: Base64(IV + AES-CBC)
                            val json = CryptoUtil.decrypt(message.toString()) ?: return
                            val obj = JSONObject(json)
                            val reading = TelemetryEntity(
                                temperature = obj.getDouble("temp"),
                                humidity = obj.getDouble("hum"),
                                isSynced = true
                            )
                            scope.launch(Dispatchers.IO) {
                                dao.insertTelemetry(reading)
                                logTelemetry(reading) // historial en Firebase
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })
                    connect(MqttConnectOptions().apply { isAutomaticReconnect = true })
                }
            } catch (_: MqttException) {
                client = null
            }
        }
    }

    fun sendCommand(payload: String) {
        scope.launch(Dispatchers.IO) {
            val mqtt = client
            if (mqtt?.isConnected == true) {
                try {
                    val encrypted = CryptoUtil.encrypt(payload)
                    if (encrypted.isNotEmpty()) {
                        mqtt.publish(TOPIC_COMMAND, MqttMessage(encrypted.toByteArray()).apply { qos = 1 })
                        logCommand(payload, System.currentTimeMillis()) // historial en Firebase
                        return@launch
                    }
                } catch (_: MqttException) { /* cae a persistencia */ }
            }
            dao.insertCommand(CommandEntity(payload = payload))
        }
    }

    suspend fun syncPendingData() = withContext(Dispatchers.IO) {
        val mqtt = client ?: return@withContext
        if (!mqtt.isConnected) return@withContext

        for (cmd in dao.getUnsyncedCommands()) {
            try {
                val encrypted = CryptoUtil.encrypt(cmd.payload)
                if (encrypted.isEmpty()) return@withContext
                mqtt.publish(TOPIC_COMMAND, MqttMessage(encrypted.toByteArray()).apply { qos = 1 })
                logCommand(cmd.payload, cmd.timestamp)
                dao.markCommandSynced(cmd.id)
            } catch (_: MqttException) {
                return@withContext // sin red: la cola queda intacta
            }
        }
        // Telemetría guardada sin confirmar: se sube a Firebase y se marca sincronizada.
        dao.getUnsyncedTelemetry().forEach {
            logTelemetry(it)
            dao.markTelemetrySynced(it.id)
        }
    }

    private fun logTelemetry(t: TelemetryEntity) {
        firebaseRoot?.child("telemetry")?.push()
            ?.setValue(mapOf("temp" to t.temperature, "hum" to t.humidity, "ts" to t.timestamp))
    }

    private fun logCommand(payload: String, ts: Long) {
        firebaseRoot?.child("commands")?.push()
            ?.setValue(mapOf("payload" to payload, "ts" to ts))
    }

    private fun isNetworkAvailable(): Boolean =
        cm.activeNetwork
            ?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    companion object {
        const val TOPIC_COMMAND = "smartnode/cmd"
        const val TOPIC_TELEMETRY = "smartnode/telemetry"
    }
}
