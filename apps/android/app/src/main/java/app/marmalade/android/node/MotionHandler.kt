package app.marmalade.android.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import app.marmalade.android.PermissionRequester
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

class MotionHandler(private val appContext: Context) : SensorEventListener {

    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var currentSteps: Float = 0f

    init {
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensurePermission(): Boolean {
        if (hasPermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.ACTIVITY_RECOGNITION))
        return results[Manifest.permission.ACTIVITY_RECOGNITION] == true
    }

    suspend fun handleActivity(): InvokeResult {
        if (!ensurePermission()) {
            return InvokeResult.error(
                code = "MOTION_PERMISSION_REQUIRED",
                message = "MOTION_PERMISSION_REQUIRED: grant Activity Recognition permission"
            )
        }
        val payload = buildJsonObject {
            put("activity", JsonPrimitive("still")) // Activity Recognition API placeholder
        }
        return InvokeResult.ok(payload.toString())
    }

    suspend fun handlePedometer(): InvokeResult {
        if (!ensurePermission()) {
            return InvokeResult.error(
                code = "MOTION_PERMISSION_REQUIRED",
                message = "MOTION_PERMISSION_REQUIRED: grant Activity Recognition permission"
            )
        }
        val payload = buildJsonObject {
            put("steps", JsonPrimitive(currentSteps.toInt()))
        }
        return InvokeResult.ok(payload.toString())
    }

    fun close() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            currentSteps = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
