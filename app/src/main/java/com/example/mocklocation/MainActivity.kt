package com.example.mocklocation

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mocklocation.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LocationConfigStore
    private lateinit var marker: Marker

    private var configs: MutableList<LocationConfig> = mutableListOf()
    private var selected = GeoPoint(37.4220, -122.0841) // default: Googleplex

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MockLocationService.ACTION_STATE) return
            val active = intent.getBooleanExtra(MockLocationService.EXTRA_ACTIVE, false)
            val error = intent.getStringExtra(MockLocationService.EXTRA_ERROR)
            updateStatus(active, error)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = LocationConfigStore(this)
        configs = store.load()

        setupMap()
        setupControls()
        refreshSpinner()
        requestRuntimePermissions()
        moveTo(selected, updateFields = true)
        updateStatus(MockLocationService.running, null)
    }

    // region Map ---------------------------------------------------------------

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(15.0)
        binding.map.controller.setCenter(selected)

        marker = Marker(binding.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            title = "Mock location"
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(m: Marker?) {}
                override fun onMarkerDragEnd(m: Marker?) {
                    m?.position?.let { onPointPicked(it) }
                }
                override fun onMarkerDragStart(m: Marker?) {}
            })
        }
        binding.map.overlays.add(marker)

        val events = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let { onPointPicked(it) }
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        binding.map.overlays.add(MapEventsOverlay(events))
    }

    private fun onPointPicked(p: GeoPoint) {
        selected = p
        marker.position = p
        binding.latInput.setText(formatCoord(p.latitude))
        binding.lngInput.setText(formatCoord(p.longitude))
        binding.map.invalidate()
        if (MockLocationService.running) pushUpdate() // live-follow while active
    }

    private fun moveTo(p: GeoPoint, updateFields: Boolean) {
        selected = p
        marker.position = p
        binding.map.controller.animateTo(p)
        if (updateFields) {
            binding.latInput.setText(formatCoord(p.latitude))
            binding.lngInput.setText(formatCoord(p.longitude))
        }
        binding.map.invalidate()
    }

    // endregion

    // region Controls ----------------------------------------------------------

    private fun setupControls() {
        binding.goButton.setOnClickListener {
            readFieldsIntoSelection()?.let { moveTo(it, updateFields = false) }
        }

        binding.startButton.setOnClickListener {
            val p = readFieldsIntoSelection() ?: return@setOnClickListener
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_LONG).show()
                requestRuntimePermissions()
                return@setOnClickListener
            }
            if (!isMockLocationAppEnabled()) {
                promptEnableMockApp()
                return@setOnClickListener
            }
            selected = p
            startService(MockLocationService.ACTION_START)
        }

        binding.stopButton.setOnClickListener {
            startService(MockLocationService.ACTION_STOP)
        }

        binding.devOptionsButton.setOnClickListener { openDeveloperOptions() }

        binding.saveButton.setOnClickListener { saveCurrentConfig() }
        binding.deleteButton.setOnClickListener { deleteSelectedConfig() }

        binding.savedSpinner.setOnItemSelectedListenerCompat { index ->
            configs.getOrNull(index)?.let {
                binding.nameInput.setText(it.name)
                moveTo(GeoPoint(it.latitude, it.longitude), updateFields = true)
            }
        }
    }

    private fun startService(action: String) {
        val intent = Intent(this, MockLocationService::class.java).apply {
            this.action = action
            putExtra(MockLocationService.EXTRA_LAT, selected.latitude)
            putExtra(MockLocationService.EXTRA_LNG, selected.longitude)
            putExtra(MockLocationService.EXTRA_ACC, 1.0f)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            action != MockLocationService.ACTION_STOP
        ) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun pushUpdate() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_UPDATE
            putExtra(MockLocationService.EXTRA_LAT, selected.latitude)
            putExtra(MockLocationService.EXTRA_LNG, selected.longitude)
            putExtra(MockLocationService.EXTRA_ACC, 1.0f)
        }
        startService(intent)
    }

    private fun readFieldsIntoSelection(): GeoPoint? {
        val lat = binding.latInput.text.toString().trim().toDoubleOrNull()
        val lng = binding.lngInput.text.toString().trim().toDoubleOrNull()
        if (lat == null || lat < -90 || lat > 90) {
            binding.latInput.error = getString(R.string.error_bad_lat)
            return null
        }
        if (lng == null || lng < -180 || lng > 180) {
            binding.lngInput.error = getString(R.string.error_bad_lng)
            return null
        }
        return GeoPoint(lat, lng)
    }

    // endregion

    // region Saved configs -----------------------------------------------------

    private fun saveCurrentConfig() {
        val p = readFieldsIntoSelection() ?: return
        val name = binding.nameInput.text.toString().trim()
            .ifEmpty { "Loc ${configs.size + 1}" }
        configs = store.upsert(LocationConfig(name, p.latitude, p.longitude))
        refreshSpinner()
        Toast.makeText(this, getString(R.string.saved, name), Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedConfig() {
        val index = binding.savedSpinner.selectedItemPosition
        val cfg = configs.getOrNull(index) ?: return
        configs = store.delete(cfg.name)
        refreshSpinner()
        Toast.makeText(this, getString(R.string.deleted, cfg.name), Toast.LENGTH_SHORT).show()
    }

    private fun refreshSpinner() {
        val names = if (configs.isEmpty()) listOf(getString(R.string.no_saved))
        else configs.map { "${it.name}  (${formatCoord(it.latitude)}, ${formatCoord(it.longitude)})" }
        binding.savedSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
    }

    // endregion

    // region Mock-app detection & developer options ----------------------------

    /** Checks whether this app is the selected "Mock location app" in Developer options. */
    private fun isMockLocationAppEnabled(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun promptEnableMockApp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.enable_mock_title)
            .setMessage(R.string.enable_mock_message)
            .setPositiveButton(R.string.open_dev_options) { _, _ -> openDeveloperOptions() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.open_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // endregion

    private fun updateStatus(active: Boolean, error: String?) {
        if (error != null) {
            binding.statusText.text = getString(R.string.status_error, error)
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        binding.statusText.text = if (active) {
            getString(
                R.string.status_active,
                formatCoord(selected.latitude),
                formatCoord(selected.longitude)
            )
        } else {
            getString(R.string.status_idle)
        }
        binding.startButton.isEnabled = !active
        binding.stopButton.isEnabled = active
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        val filter = IntentFilter(MockLocationService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        runCatching { unregisterReceiver(stateReceiver) }
    }

    // Locale.US so the decimal separator is always '.', otherwise parsing the fields back fails.
    private fun formatCoord(v: Double): String = String.format(Locale.US, "%.6f", v)
}
