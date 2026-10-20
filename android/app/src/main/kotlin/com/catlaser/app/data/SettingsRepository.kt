package com.catlaser.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "catlaser_settings")

/**
 * Persists host/IP, UDP port and the joystick mirroring.
 * The default host is the device's mDNS name -- it does not work on
 * every Android device; in that case enter the IP address.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val HOST_KEY = stringPreferencesKey("host")
        private val UDP_PORT_KEY = intPreferencesKey("udp_port")
        private val FLIP_X_KEY = booleanPreferencesKey("flip_x")
        private val FLIP_Y_KEY = booleanPreferencesKey("flip_y")
        private val SENSOR_KEY = stringPreferencesKey("sensor_cfg")
        const val DEFAULT_HOST = "catlaser.local"
        const val DEFAULT_UDP_PORT = 4210
    }

    val hostFlow: Flow<String> = context.dataStore.data.map { it[HOST_KEY] ?: DEFAULT_HOST }
    val udpPortFlow: Flow<Int> = context.dataStore.data.map { it[UDP_PORT_KEY] ?: DEFAULT_UDP_PORT }
    val flipXFlow: Flow<Boolean> = context.dataStore.data.map { it[FLIP_X_KEY] ?: false }
    val flipYFlow: Flow<Boolean> = context.dataStore.data.map { it[FLIP_Y_KEY] ?: false }

    val sensorConfigFlow: Flow<String> = context.dataStore.data.map { it[SENSOR_KEY] ?: "" }
    suspend fun setSensorConfig(json: String) { context.dataStore.edit { it[SENSOR_KEY] = json } }

    suspend fun setHost(host: String) { context.dataStore.edit { it[HOST_KEY] = host } }
    suspend fun setUdpPort(port: Int) { context.dataStore.edit { it[UDP_PORT_KEY] = port } }
    suspend fun setFlipX(v: Boolean) { context.dataStore.edit { it[FLIP_X_KEY] = v } }
    suspend fun setFlipY(v: Boolean) { context.dataStore.edit { it[FLIP_Y_KEY] = v } }
}
