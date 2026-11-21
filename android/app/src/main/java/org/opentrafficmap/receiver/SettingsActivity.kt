package org.opentrafficmap.receiver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.InputStream
import java.text.DateFormat
import java.util.Date
import com.google.android.material.textfield.TextInputEditText
import org.opentrafficmap.receiver.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    /** System file picker for recordings outside the app's own folder (e.g. Download). */
    private val openRecording = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadRecording(uri.lastPathSegment?.substringAfterLast('/') ?: "file") {
            contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open")
        }
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // --- Keep screen on -------------------------------------------------
        binding.swKeepScreenOn.isChecked = Prefs.keepScreenOn(this)
        binding.swKeepScreenOn.setOnCheckedChangeListener { _, c ->
            Prefs.setKeepScreenOn(this, c)
            SettingsBus.keepScreenOnChanged()
        }

        // --- Dark mode ------------------------------------------------------
        binding.swDarkMode.isChecked = Prefs.forceDarkMode(this)
        binding.swDarkMode.setOnCheckedChangeListener { _, c ->
            Prefs.setForceDarkMode(this, c)
            SettingsBus.darkModeChanged(c)
            AppCompatDelegate.setDefaultNightMode(
                if (c) AppCompatDelegate.MODE_NIGHT_YES
                else   AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
            recreate()
        }

        // --- Reset ----------------------------------------------------------
        binding.btnReset.setOnClickListener {
            SettingsBus.resetAll()
            Toast.makeText(this, getString(R.string.reset), Toast.LENGTH_SHORT).show()
        }

        // --- Auto-follow ----------------------------------------------------
        binding.swFollow.isChecked = Prefs.followEnabled(this)
        binding.swFollow.setOnCheckedChangeListener { _, c ->
            Prefs.setFollowEnabled(this, c)
            SettingsBus.followChanged(c)
        }
        binding.swAutoZoom.isChecked = Prefs.autoZoom(this)
        binding.swAutoZoom.setOnCheckedChangeListener { _, c -> Prefs.setAutoZoom(this, c) }

        // --- Geiger-counter audio -------------------------------------------
        binding.swAudio.isChecked = Prefs.audioFeedback(this)
        binding.swAudio.setOnCheckedChangeListener { _, c ->
            Prefs.setAudioFeedback(this, c)
            SettingsBus.audioChanged(c)
        }

        // --- SPATEM traffic light -------------------------------------------
        binding.swSpatLight.isChecked = Prefs.spatLightEnabled(this)
        binding.swSpatLight.setOnCheckedChangeListener { _, c ->
            Prefs.setSpatLightEnabled(this, c)
            SettingsBus.spatLightChanged(c)
        }

        // --- MQTT -----------------------------------------------------------
        loadBrokerRows()
        binding.btnAddBroker.setOnClickListener { addBrokerRow("") }

        binding.edNodeId.setText(Prefs.nodeId(this))
        binding.swMqtt.isChecked = Prefs.mqttEnabled(this)
        binding.swMqtt.setOnCheckedChangeListener { _, c ->
            saveBrokerList()
            Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
            Prefs.setMqttEnabled(this, c)
            SettingsBus.mqttToggle(c)
        }

        // --- MQTT type filter -----------------------------------------------
        binding.btnMqttFilter.setOnClickListener { showMqttFilterDialog() }

        // --- Recording ------------------------------------------------------
        renderRecordingState()
        binding.btnRecord.setOnClickListener {
            val msg = SettingsBus.recordingToggle()
            Toast.makeText(this, msg ?: getString(R.string.rec_status_idle), Toast.LENGTH_LONG).show()
            renderRecordingState()
        }
        binding.btnLoadRecording.setOnClickListener { showLoadRecordingDialog() }

        // --- Marker / path TTL ----------------------------------------------
        binding.edMarkerTtl.setText(Prefs.markerTtlMinutes(this).toString())

        // --- Map prefetch ---------------------------------------------------
        binding.btnDownloadMap.setOnClickListener {
            SettingsBus.downloadVisibleMap()
            finish()
        }

        // --- Own track ------------------------------------------------------
        binding.swOwnTrack.isChecked = Prefs.ownTrackEnabled(this)
        binding.swOwnTrack.setOnCheckedChangeListener { _, c ->
            Prefs.setOwnTrackEnabled(this, c)
            SettingsBus.ownTrackChanged(c)
        }

        // --- BLE coex -------------------------------------------------------
        binding.btnCycle.setOnClickListener {
            val controller = SettingsBus.btController()
            if (controller == null) {
                Toast.makeText(this, getString(R.string.cycle_not_connected), Toast.LENGTH_SHORT).show()
            } else {
                CycleSettingsDialog.show(this, SettingsBus.lastCycleConfig()) { SettingsBus.applyCycle(it) }
            }
        }

        // --- About / GitHub -------------------------------------------------
        binding.btnAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        binding.btnGithub.setOnClickListener { openUrl(getString(R.string.github_url)) }
    }

    override fun onPause() {
        super.onPause()
        saveBrokerList()
        Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
        binding.edMarkerTtl.text.toString().trim().toIntOrNull()
            ?.let { Prefs.setMarkerTtlMinutes(this, it) }
    }

    // ── Broker row management ────────────────────────────────────────────────

    private fun loadBrokerRows() {
        binding.brokerContainer.removeAllViews()
        val brokers = Prefs.mqttBrokerList(this)
        if (brokers.isEmpty()) {
            addBrokerRow("")
        } else {
            brokers.forEach { addBrokerRow(it) }
        }
    }

    private fun addBrokerRow(url: String) {
        val row = layoutInflater.inflate(R.layout.item_broker_entry, binding.brokerContainer, false)
        row.findViewById<TextInputEditText>(R.id.edBrokerEntry).setText(url)
        row.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteEntry)
            .setOnClickListener {
                binding.brokerContainer.removeView(row)
                // Always keep at least one empty row so the field is never fully gone
                if (binding.brokerContainer.childCount == 0) addBrokerRow("")
            }
        binding.brokerContainer.addView(row)
        // Focus the new (empty) field
        if (url.isEmpty()) {
            row.findViewById<TextInputEditText>(R.id.edBrokerEntry).requestFocus()
        }
    }

    private fun saveBrokerList() {
        val urls = mutableListOf<String>()
        for (i in 0 until binding.brokerContainer.childCount) {
            val url = binding.brokerContainer.getChildAt(i)
                .findViewById<TextInputEditText>(R.id.edBrokerEntry)
                ?.text?.toString()?.trim()
            if (!url.isNullOrBlank()) urls.add(url)
        }
        Prefs.setMqttBrokerList(this, urls)
    }

    // ── MQTT filter dialog ───────────────────────────────────────────────────

    private fun showMqttFilterDialog() {
        val allTypes = ItsG5Decoder.MsgType.values()
        val allowed  = Prefs.mqttFilterTypes(this)
        val checked  = allTypes.map { it.name in allowed }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.mqtt_filter_btn)
            .setMultiChoiceItems(allTypes.map { mqttTypeLabel(it) }.toTypedArray(), checked) { _, i, on ->
                checked[i] = on
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newFilter = allTypes.filterIndexed { i, _ -> checked[i] }.map { it.name }.toSet()
                Prefs.setMqttFilterTypes(this, newFilter)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun renderRecordingState() {
        val rec = SettingsBus.recorder()
        val enabled = Prefs.recordingEnabled(this)
        binding.btnRecord.text = getString(if (enabled) R.string.rec_on else R.string.rec_off)
        binding.recStatus.text = when {
            rec?.isRecording == true -> getString(R.string.rec_status_active, rec.file?.absolutePath ?: "?", rec.frameCount)
            enabled -> getString(R.string.rec_status_armed)
            else -> getString(R.string.rec_status_idle)
        }
    }

    // ── load a recording ─────────────────────────────────────────────────────

    /** Own recordings (newest first) plus "Other file…" via the system picker. */
    private fun showLoadRecordingDialog() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".pcap") && f.length() > 24 } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = files.map { "${fmt.format(Date(it.lastModified()))}  ·  ${it.length() / 1024} kB" } +
            getString(R.string.rec_load_other)
        AlertDialog.Builder(this)
            // An AlertDialog shows either a message or a list — never both — so the
            // "no recordings yet" hint goes into the title to keep "Other file…" visible.
            .setTitle(if (files.isEmpty()) R.string.rec_load_none else R.string.rec_load_title)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < files.size) files[i].let { f -> loadRecording(f.name) { f.inputStream() } }
                else openRecording.launch(arrayOf("*/*"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Parses the PCAP off the main thread, then hands the frames to the map/log and closes Settings. */
    private fun loadRecording(name: String, open: () -> InputStream) {
        Toast.makeText(this, getString(R.string.rec_loading, name), Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { open().use { PcapReader.read(it) } }
            runOnUiThread {
                result.onSuccess { frames ->
                    SettingsBus.loadFrames(frames, name)
                    Toast.makeText(this, getString(R.string.rec_loaded, frames.size, name), Toast.LENGTH_LONG).show()
                    finish()
                }.onFailure { e ->
                    Toast.makeText(this, getString(R.string.rec_load_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun mqttTypeLabel(type: ItsG5Decoder.MsgType): String = getString(when (type) {
        ItsG5Decoder.MsgType.UNKNOWN -> R.string.msgtype_unknown
        ItsG5Decoder.MsgType.CAM     -> R.string.msgtype_cam
        ItsG5Decoder.MsgType.DENM    -> R.string.msgtype_denm
        ItsG5Decoder.MsgType.MAPEM   -> R.string.msgtype_mapem
        ItsG5Decoder.MsgType.SPATEM  -> R.string.msgtype_spatem
        ItsG5Decoder.MsgType.IVIM    -> R.string.msgtype_ivim
        ItsG5Decoder.MsgType.SREM    -> R.string.msgtype_srem
        ItsG5Decoder.MsgType.SSEM    -> R.string.msgtype_ssem
        ItsG5Decoder.MsgType.TLM     -> R.string.msgtype_tlm
        ItsG5Decoder.MsgType.RTCMEM  -> R.string.msgtype_rtcmem
    })

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }
}
