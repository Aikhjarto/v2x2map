package org.opentrafficmap.receiver.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.*
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records whatever the car's head unit hands to Android Auto (own GPS, wheel speed, compass, gyroscope,
 * accelerometer, odometer, fuel/battery level and range, model) into `car-<time>.csv` next to the other
 * recordings, for as long as the car screen is open. Always on, no setting: it is a probe of what cars
 * deliver.
 *
 * Every car delivers a different subset, so each data kind is requested on its own and failures are
 * written as rows too: a value has a status (ok / unavailable / unimplemented / unknown), a request that
 * throws is noted as `error`. One row per value:
 *     time_ms,kind,status,car_ts_ms,v1,v2,v3,v4
 * kinds: model (manufacturer,name,year), energy_profile (fuel types,ev connector types), speed (raw m/s,
 * display m/s,display unit), mileage (odometer m,display unit), energy (fuel %,battery %,range m,low),
 * location (lat,lon,accuracy m,speed m/s; plus bearing in v4 if no speed), compass (orientations…),
 * gyroscope (rotations…), accelerometer (forces…), error (what,exception), note (text).
 */
class CarDataRecorder(private val carContext: CarContext) {
    private val tag = "CarDataRecorder"
    private val exec = Executors.newSingleThreadExecutor()
    private var out: BufferedWriter? = null
    private var info: CarInfo? = null
    private var sensors: CarSensors? = null

    private val onSpeed = OnCarDataAvailableListener<Speed> { s ->
        row("speed", s.rawSpeedMetersPerSecond, s.rawSpeedMetersPerSecond.value, s.displaySpeedMetersPerSecond.value,
            s.speedDisplayUnit.value)
    }
    private val onMileage = OnCarDataAvailableListener<Mileage> { m ->
        row("mileage", m.odometerMeters, m.odometerMeters.value, m.distanceDisplayUnit.value)
    }
    private val onEnergy = OnCarDataAvailableListener<EnergyLevel> { e ->
        row("energy", e.fuelPercent, e.fuelPercent.value, e.batteryPercent.value, e.rangeRemainingMeters.value,
            e.energyIsLow.value)
    }
    private val onLocation = OnCarDataAvailableListener<CarHardwareLocation> { l ->
        val v = l.location.value
        row("location", l.location, v?.latitude, v?.longitude, v?.takeIf { it.hasAccuracy() }?.accuracy,
            v?.let { if (it.hasSpeed()) it.speed else if (it.hasBearing()) "bearing=${it.bearing}" else null })
    }
    private val onCompass = OnCarDataAvailableListener<Compass> { c -> list("compass", c.orientations) }
    private val onGyro = OnCarDataAvailableListener<Gyroscope> { g -> list("gyroscope", g.rotations) }
    private val onAccel = OnCarDataAvailableListener<Accelerometer> { a -> list("accelerometer", a.forces) }

    fun start() {
        if (out != null) return
        val dir = carContext.getExternalFilesDir(null) ?: carContext.filesDir
        val f = File(dir, "car-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".csv")
        out = try { f.bufferedWriter().also { it.write(HEADER); it.newLine(); it.flush() } }
              catch (e: Exception) { Log.w(tag, "open failed", e); return }
        note("car app API level ${carContext.carAppApiLevel}")
        if (carContext.carAppApiLevel < 3) { note("car hardware needs API level 3: nothing to record"); return }
        val hw = try { carContext.getCarService(CarHardwareManager::class.java) }
                 catch (e: Exception) { error("hardware manager", e); return }
        info = hw.carInfo.also { ci ->
            attempt("model") { ci.fetchModel(exec) { m ->
                row("model", m.name, m.manufacturer.value, m.name.value, m.year.value) } }
            attempt("energy_profile") { ci.fetchEnergyProfile(exec) { p ->
                row("energy_profile", p.fuelTypes, p.fuelTypes.value?.joinToString("|"), p.evConnectorTypes.value?.joinToString("|")) } }
            attempt("speed") { ci.addSpeedListener(exec, onSpeed) }
            attempt("mileage") { ci.addMileageListener(exec, onMileage) }
            attempt("energy") { ci.addEnergyLevelListener(exec, onEnergy) }
        }
        sensors = hw.carSensors.also { cs ->
            attempt("location") { cs.addCarHardwareLocationListener(CarSensors.UPDATE_RATE_FASTEST, exec, onLocation) }
            attempt("compass") { cs.addCompassListener(CarSensors.UPDATE_RATE_UI, exec, onCompass) }
            attempt("gyroscope") { cs.addGyroscopeListener(CarSensors.UPDATE_RATE_UI, exec, onGyro) }
            attempt("accelerometer") { cs.addAccelerometerListener(CarSensors.UPDATE_RATE_UI, exec, onAccel) }
        }
        Log.i(tag, "recording car data to ${f.absolutePath}")
    }

    fun stop() {
        info?.let { ci ->
            runCatching { ci.removeSpeedListener(onSpeed) }; runCatching { ci.removeMileageListener(onMileage) }
            runCatching { ci.removeEnergyLevelListener(onEnergy) }
        }
        sensors?.let { cs ->
            runCatching { cs.removeCarHardwareLocationListener(onLocation) }; runCatching { cs.removeCompassListener(onCompass) }
            runCatching { cs.removeGyroscopeListener(onGyro) }; runCatching { cs.removeAccelerometerListener(onAccel) }
        }
        info = null; sensors = null
        exec.execute { synchronized(this) { runCatching { out?.close() }; out = null } }
        exec.shutdown()
    }

    private fun attempt(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { error(what, e) }   // e.g. not supported by this host, permission missing
    }

    private fun list(kind: String, v: CarValue<List<Float>>) {
        val l = v.value.orEmpty()
        row(kind, v, l.getOrNull(0), l.getOrNull(1), l.getOrNull(2), l.drop(3).takeIf { it.isNotEmpty() }?.joinToString("|"))
    }

    private fun error(what: String, e: Exception) = write("error", "", "", listOf(what, "${e.javaClass.simpleName}: ${e.message}"))
    private fun note(text: String) = write("note", "", "", listOf(text))

    private fun row(kind: String, v: CarValue<*>, vararg values: Any?) =
        write(kind, status(v.status), v.timestampMillis.toString(), values.toList())

    @Synchronized
    private fun write(kind: String, status: String, carTs: String, values: List<Any?>) {
        val w = out ?: return
        try {
            w.write(listOf(System.currentTimeMillis().toString(), kind, status, carTs).plus(values.map { csv(it) }).joinToString(","))
            w.newLine(); w.flush()
        } catch (e: Exception) { Log.w(tag, "write failed", e) }
    }

    private fun csv(v: Any?): String {
        val s = v?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\"" else s
    }

    private fun status(s: Int) = when (s) {
        CarValue.STATUS_SUCCESS -> "ok"
        CarValue.STATUS_UNAVAILABLE -> "unavailable"
        CarValue.STATUS_UNIMPLEMENTED -> "unimplemented"
        else -> "unknown"
    }

    companion object { const val HEADER = "time_ms,kind,status,car_ts_ms,v1,v2,v3,v4" }
}
