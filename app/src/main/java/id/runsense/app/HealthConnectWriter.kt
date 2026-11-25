package id.runsense.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Percentage
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Menulis data RunSense (asal Intervals.icu) ke Health Connect. Hanya menulis, tidak membaca.
 *
 * Idempoten: tiap record punya clientRecordId tetap ("runsense-...") sehingga sinkron ulang
 * memperbarui record yang sama dan tidak menggandakannya.
 *
 * Sengaja TIDAK ditulis: detak jantung rata-rata sesi (hanya ada rata-rata, bukan sampel, dan
 * Health Connect menyimpan deret sampel) dan tidur (Intervals hanya memberi durasi, tanpa jam
 * tidur/bangun). Menulis angka karangan lebih buruk daripada tidak menulis.
 */
class HealthConnectWriter(private val context: Context) {

    class Result(val sessions: Int, val days: Int)

    companion object {
        private val TYPES = listOf(
            ExerciseSessionRecord::class,
            DistanceRecord::class,
            ElevationGainedRecord::class,
            StepsRecord::class,
            RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class,
            OxygenSaturationRecord::class,
            WeightRecord::class,
            BodyFatRecord::class,
            BloodPressureRecord::class,
            Vo2MaxRecord::class
        )
        val PERMISSIONS: Set<String> = TYPES.map { HealthPermission.getWritePermission(it) }.toSet()
        private const val CHUNK = 500
    }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> =
        client().permissionController.getGrantedPermissions().intersect(PERMISSIONS)

    /** payload: {acts:[[ms,kind,min,km,elevM,lid,name]], wellness:[{id,restingHR,hrv,steps,spO2}]} */
    suspend fun write(payload: JSONObject): Result {
        val granted = grantedPermissions()
        fun can(c: kotlin.reflect.KClass<out Record>) = HealthPermission.getWritePermission(c) in granted

        val zone = ZoneId.systemDefault()
        val version = System.currentTimeMillis() / 1000
        fun meta(id: String) = Metadata.unknownRecordingMethod(
            clientRecordId = id,
            clientRecordVersion = version
        )

        val records = ArrayList<Record>()
        var sessions = 0
        var days = 0

        val acts = payload.optJSONArray("acts")
        if (acts != null) for (i in 0 until acts.length()) {
            val a = acts.getJSONArray(i)
            val startMs = a.getLong(0)
            val kind = a.getString(1)
            val minutes = a.getDouble(2)
            val km = a.optDouble(3, 0.0)
            val elev = a.optDouble(4, 0.0)
            val lid = a.getString(5)
            val name = a.optString(6, "")
            if (minutes < 1.0) continue

            val start = Instant.ofEpochMilli(startMs)
            val end = start.plusSeconds((minutes * 60).toLong())
            val so = zone.rules.getOffset(start)
            val eo = zone.rules.getOffset(end)
            val key = "runsense-$lid"
            val exerciseType = when (kind.lowercase()) {
                "run", "running" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                "walk", "walking" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                "ride", "cycling", "bike" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                "swim", "swimming" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING
                "hike", "hiking" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
                "rowing", "row" -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
                "elliptical" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
                "strength", "weight_training" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
                "jump_rope", "jumprope", "rope_skipping" -> ExerciseSessionRecord.EXERCISE_TYPE_JUMP_ROPE
                else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
            }

            if (can(ExerciseSessionRecord::class)) {
                records += ExerciseSessionRecord(
                    startTime = start, startZoneOffset = so,
                    endTime = end, endZoneOffset = eo,
                    metadata = meta(key),
                    exerciseType = exerciseType,
                    title = name.ifBlank { kind.replaceFirstChar { it.uppercase() } }
                )
                sessions++
            }
            if (km > 0 && can(DistanceRecord::class)) {
                records += DistanceRecord(
                    startTime = start, startZoneOffset = so,
                    endTime = end, endZoneOffset = eo,
                    distance = Length.meters(km * 1000.0),
                    metadata = meta("$key-dist")
                )
            }
            if (elev > 0 && can(ElevationGainedRecord::class)) {
                records += ElevationGainedRecord(
                    startTime = start, startZoneOffset = so,
                    endTime = end, endZoneOffset = eo,
                    elevation = Length.meters(elev),
                    metadata = meta("$key-elev")
                )
            }
        }

        val well = payload.optJSONArray("wellness")
        if (well != null) for (i in 0 until well.length()) {
            val w = well.getJSONObject(i)
            val date = runCatching { LocalDate.parse(w.getString("id")) }.getOrNull() ?: continue
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
            // Pembacaan harian tidak punya jam; ditaruh pukul 06:00 lokal sebagai perkiraan.
            val at = date.atTime(LocalTime.of(6, 0)).atZone(zone).toInstant()
            val off = zone.rules.getOffset(at)
            var any = false

            val steps = w.optLong("steps", 0)
            if (steps > 0 && can(StepsRecord::class)) {
                records += StepsRecord(
                    startTime = dayStart, startZoneOffset = zone.rules.getOffset(dayStart),
                    endTime = dayEnd, endZoneOffset = zone.rules.getOffset(dayEnd),
                    count = steps, metadata = meta("runsense-steps-$date")
                )
                any = true
            }
            val rhr = w.optDouble("restingHR", 0.0)
            if (rhr in 30.0..200.0 && can(RestingHeartRateRecord::class)) {
                records += RestingHeartRateRecord(
                    time = at, zoneOffset = off,
                    beatsPerMinute = rhr.toLong(), metadata = meta("runsense-rhr-$date")
                )
                any = true
            }
            val hrv = w.optDouble("hrv", 0.0)
            if (hrv > 0 && can(HeartRateVariabilityRmssdRecord::class)) {
                records += HeartRateVariabilityRmssdRecord(
                    time = at, zoneOffset = off,
                    heartRateVariabilityMillis = hrv, metadata = meta("runsense-hrv-$date")
                )
                any = true
            }
            val spo2 = w.optDouble("spO2", 0.0)
            if (spo2 in 50.0..100.0 && can(OxygenSaturationRecord::class)) {
                records += OxygenSaturationRecord(
                    time = at, zoneOffset = off,
                    percentage = Percentage(spo2), metadata = meta("runsense-spo2-$date")
                )
                any = true
            }
            val weight = w.optDouble("weight", 0.0)
            if (weight in 20.0..350.0 && can(WeightRecord::class)) {
                records += WeightRecord(time = at, zoneOffset = off, weight = Mass.kilograms(weight), metadata = meta("runsense-weight-$date"))
                any = true
            }
            val bodyFat = w.optDouble("bodyFat", 0.0)
            if (bodyFat in 2.0..75.0 && can(BodyFatRecord::class)) {
                records += BodyFatRecord(time = at, zoneOffset = off, percentage = Percentage(bodyFat), metadata = meta("runsense-bodyfat-$date"))
                any = true
            }
            val systolic = w.optDouble("systolic", 0.0)
            val diastolic = w.optDouble("diastolic", 0.0)
            if (systolic in 60.0..280.0 && diastolic in 30.0..180.0 && systolic > diastolic && can(BloodPressureRecord::class)) {
                records += BloodPressureRecord(time = at, zoneOffset = off, systolic = Pressure.millimetersOfMercury(systolic), diastolic = Pressure.millimetersOfMercury(diastolic), metadata = meta("runsense-bp-$date"))
                any = true
            }
            val vo2 = w.optDouble("vo2max", w.optDouble("vo2Max", 0.0))
            if (vo2 in 10.0..100.0 && can(Vo2MaxRecord::class)) {
                records += Vo2MaxRecord(time = at, zoneOffset = off, vo2MillilitersPerMinuteKilogram = vo2, measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER, metadata = meta("runsense-vo2max-$date"))
                any = true
            }
            if (any) days++
        }

        val hc = client()
        records.chunked(CHUNK).forEach { hc.insertRecords(it) }
        return Result(sessions, days)
    }
}
