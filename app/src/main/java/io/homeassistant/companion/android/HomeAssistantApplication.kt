package io.homeassistant.companion.android

import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import dagger.hilt.android.HiltAndroidApp
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.sensors.LastUpdateManager
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.sensors.SensorReceiver
import io.homeassistant.companion.android.websocket.WebsocketBroadcastReceiver
import io.homeassistant.companion.android.widgets.button.ButtonWidget
import io.homeassistant.companion.android.widgets.entity.EntityWidget
import io.homeassistant.companion.android.widgets.media_player_controls.MediaPlayerControlsWidget
import io.homeassistant.companion.android.widgets.template.TemplateWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
open class HomeAssistantApplication : Application() {

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    @Inject
    lateinit var prefsRepository: PrefsRepository

    override fun onCreate() {
        super.onCreate()

        ioScope.launch {
            initCrashReporting(
                applicationContext,
                prefsRepository.isCrashReporting()
            )
        }

        // This will make sure we start/stop when we actually need too.
        registerReceiver(
            WebsocketBroadcastReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        )

        val sensorReceiver = SensorReceiver()
        // This will cause the sensor to be updated every time the OS broadcasts that a cable was plugged/unplugged.
        // This should be nearly instantaneous allowing automations to fire immediately when a phone is plugged
        // in or unplugged. Updates will also be triggered when the system reports low battery and when it recovers.
        registerReceiver(
            sensorReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
        )

        // This will cause interactive and power save to update upon a state change
        registerReceiver(
            sensorReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        )

        // Update Quest only sensors when the device is a Quest
        if (Build.MODEL == "Quest") {
            registerReceiver(
                sensorReceiver,
                IntentFilter().apply {
                    addAction("com.oculus.intent.action.MOUNT_STATE_CHANGED")
                }
            )
        }

        // Update doze mode immediately on supported devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerReceiver(
                sensorReceiver,
                IntentFilter().apply {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            )
        }

        // This will trigger an update any time the wifi state has changed
        registerReceiver(
            sensorReceiver,
            IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
        )

        // This will cause the phone state sensor to be updated every time the OS broadcasts that a call triggered.
        registerReceiver(
            sensorReceiver,
            IntentFilter().apply {
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            }
        )

        // Listen for bluetooth state changes
        registerReceiver(
            sensorReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        // Listen to changes to the audio input/output on the device
        registerReceiver(
            sensorReceiver,
            IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            }
        )

        // Add extra audio receiver for devices that support it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerReceiver(
                sensorReceiver,
                IntentFilter().apply {
                    addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
                    addAction(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
                }
            )
        }

        // Add receiver for DND changes on devices that support it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerReceiver(
                sensorReceiver,
                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            )
        }

        // Register for all saved user intents
        val sensorDao = AppDatabase.getInstance(applicationContext).sensorDao()
        val allSettings = sensorDao.getSettings(LastUpdateManager.lastUpdate.id)
        for (setting in allSettings) {
            if (setting.value != "" && setting.value != "SensorWorker") {
                registerReceiver(
                    sensorReceiver,
                    IntentFilter(setting.value)
                )
            }
        }

        // Register for changes to the managed profile availability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(
                sensorReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                }
            )
        }

        // Update widgets when the screen turns on, updates are skipped if widgets were not added
        val buttonWidget = ButtonWidget()
        val entityWidget = EntityWidget()
        val mediaPlayerWidget = MediaPlayerControlsWidget()
        val templateWidget = TemplateWidget()

        val screenIntentFilter = IntentFilter()
        screenIntentFilter.addAction(Intent.ACTION_SCREEN_ON)
        screenIntentFilter.addAction(Intent.ACTION_SCREEN_OFF)

        registerReceiver(buttonWidget, screenIntentFilter)
        registerReceiver(entityWidget, screenIntentFilter)
        registerReceiver(mediaPlayerWidget, screenIntentFilter)
        registerReceiver(templateWidget, screenIntentFilter)
    }
}
