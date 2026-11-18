package app.obscura.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.obscura.camera.ui.CameraPreview
import app.obscura.camera.ui.CameraScreen
import app.obscura.camera.ui.CameraViewModel
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt
import app.obscura.camera.ui.UiEvent
import app.obscura.camera.ui.components.press
import kotlin.math.atan2

class MainActivity : ComponentActivity(), SensorEventListener {

    private val vm: CameraViewModel by viewModels()
    private lateinit var sensors: SensorManager
    private var gravity: Sensor? = null
    private var roll = 0f

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermission(it) }

    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                vm.setDeviceOrientation(((deg + 45) / 90 * 90) % 360)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.onPermission(true) else permission.launch(Manifest.permission.CAMERA)

        // Opened as an editor from a gallery app ("Edit with Obscura"): go to the light studio.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_EDIT) intent.data?.let(vm::editExternal)

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                vm.events.collect { e ->
                    when (e) {
                        is UiEvent.ViewImage -> runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                        is UiEvent.ShareImage -> runCatching {
                            startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, e.uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "שיתוף תמונה"))
                        }
                        is UiEvent.ShareJson -> runCatching {
                            startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, e.uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "שיתוף דוח החיישן"))
                        }
                    }
                }
            }
            // Hebrew interface: the whole app is right to left. Instruments opt back into LTR.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                if (!state.hasPermission) {
                    PermissionScreen { permission.launch(Manifest.permission.CAMERA) }
                } else {
                    CameraScreen(state, vm) { m ->
                        CameraPreview(
                            look = state.look, histogramOn = state.histogramOn,
                            onTexture = vm::onTextureAvailable, onTextureGone = vm::onTextureDestroyed,
                            onHistogram = vm::onHistogram, modifier = m,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        orientation.enable()
        gravity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        vm.onPause()
        orientation.disable()
        sensors.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y) = event.values[0] to event.values[1]
        val raw = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        roll += 0.18f * (raw - roll)
        vm.onRoll(roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ob.Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Txt("OBSCURA", Ob.Wordmark)
            Spacer(Modifier.height(18.dp))
            Txt("מצלמה צריכה אור, והרשאה.", Ob.Body.copy(color = Ob.Ash))
            Spacer(Modifier.height(28.dp))
            Txt(
                "אישור גישה למצלמה", Ob.LabelHe.copy(color = Ob.Ink),
                Modifier.background(Ob.Ivory, RoundedCornerShape(50)).press(onGrant).padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}
