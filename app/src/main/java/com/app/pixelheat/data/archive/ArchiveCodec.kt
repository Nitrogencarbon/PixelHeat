package com.app.pixelheat.data.archive

import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.LifetimeStatsEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.data.prefs.CollectedEstimate
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ZoneSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.BufferedInputStream
import java.io.InputStream

class ArchiveFormatException(message: String) : IllegalArgumentException(message)

/** Everything the backup file holds. Settings, consent and permissions are not part of it. */
data class ArchiveContents(
    val exportedAt: Long,
    val samples: List<ThermalSampleEntity>,
    val events: List<HardwareEventEntity>,
    val appUsage: List<AppUsageEntity>,
    val lifetime: LifetimeStatsEntity?,
    val estimate: CollectedEstimate,
)

private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Versioned JSON for the backup file. Unknown future fields are ignored; a newer format is refused. */
object ArchiveCodec {
    const val FORMAT = 1

    /** Bytes read ahead to check the header before decoding the whole file. */
    private const val HEADER_BYTES = 4096
    private val FORMAT_FIELD = Regex(""""format"\s*:\s*(\d+)""")

    fun encode(contents: ArchiveContents): String {
        val out = StringBuilder()
        ArchiveWriter(out).apply {
            begin(contents.exportedAt)
            array("samples", contents.samples, ::sample)
            array("events", contents.events, ::event)
            array("appUsage", contents.appUsage, ::usage)
            end(contents.lifetime, contents.estimate)
        }
        return out.toString()
    }

    fun decode(text: String): ArchiveContents = decode(text.byteInputStream())

    /** Decodes from a stream, so a large backup is never held as one string or JSON tree. */
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(input: InputStream): ArchiveContents {
        val stream = BufferedInputStream(input, HEADER_BYTES)
        stream.mark(HEADER_BYTES)
        val head = ByteArray(HEADER_BYTES)
        val read = stream.readNBytes(head, 0, HEADER_BYTES)
        stream.reset()
        val header = String(head, 0, read, Charsets.UTF_8)
        if (!header.trimStart().startsWith("{")) throw ArchiveFormatException("This file is not a Pixel Heat backup.")
        val format = FORMAT_FIELD.find(header)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ArchiveFormatException("This file is not a Pixel Heat backup.")
        if (format != FORMAT) {
            throw ArchiveFormatException("This backup uses format $format, which this version cannot read.")
        }
        if ("\"samples\"" !in header) throw ArchiveFormatException("This backup contains no measurements.")
        val record = try {
            json.decodeFromStream(ArchiveRecord.serializer(), stream)
        } catch (e: ArchiveFormatException) {
            throw e
        } catch (_: OutOfMemoryError) {
            throw ArchiveFormatException("This backup is too large to open on this phone.")
        } catch (_: Exception) {
            throw ArchiveFormatException("This backup is damaged and cannot be read.")
        }
        return record.toContents()
    }
}

/**
 * Writes the same JSON as [ArchiveCodec.encode], one record at a time, so an export never holds
 * the whole history as a single string. The field order puts `samples` inside the header that
 * [ArchiveCodec.decode] checks.
 */
class ArchiveWriter(private val out: Appendable) {
    private var firstItem = true

    fun begin(exportedAt: Long) {
        out.append("{\"format\":").append(ArchiveCodec.FORMAT.toString())
        out.append(",\"exportedAt\":").append(exportedAt.toString())
    }

    fun beginArray(name: String) {
        out.append(",\"").append(name).append("\":[")
        firstItem = true
    }

    fun endArray() {
        out.append(']')
    }

    fun sample(e: ThermalSampleEntity) = item(json.encodeToString(SampleRecord.serializer(), e.toRecord()))

    fun event(e: HardwareEventEntity) = item(json.encodeToString(EventRecord.serializer(), e.toRecord()))

    fun usage(e: AppUsageEntity) = item(json.encodeToString(UsageRecord.serializer(), e.toRecord()))

    fun end(lifetime: LifetimeStatsEntity?, estimate: CollectedEstimate) {
        if (lifetime != null) {
            out.append(",\"lifetime\":").append(json.encodeToString(LifetimeRecord.serializer(), lifetime.toRecord()))
        }
        out.append(",\"estimate\":").append(json.encodeToString(EstimateRecord.serializer(), estimate.toRecord()))
        out.append('}')
    }

    fun <T> array(name: String, items: List<T>, write: (T) -> Unit) {
        beginArray(name)
        items.forEach(write)
        endArray()
    }

    private fun item(encoded: String) {
        if (!firstItem) out.append(',')
        firstItem = false
        out.append(encoded)
    }
}

@Serializable
private data class ArchiveRecord(
    val format: Int,
    val exportedAt: Long,
    val samples: List<SampleRecord> = emptyList(),
    val events: List<EventRecord> = emptyList(),
    val appUsage: List<UsageRecord> = emptyList(),
    val lifetime: LifetimeRecord? = null,
    val estimate: EstimateRecord = EstimateRecord(),
)

@Serializable
private data class SampleRecord(
    val timestamp: Long,
    val weightSec: Float = 0f,
    val heatLevel: String,
    val batteryTemp: Float,
    val cpuTemp: Float? = null,
    val gpuTemp: Float? = null,
    val skinTemp: Float? = null,
    val modemTemp: Float? = null,
    val usbTemp: Float? = null,
    val maxTemp: Float,
    val hottestZone: String? = null,
    val ambientTemp: Float? = null,
    val ambientSource: String,
    val thermalStatus: Int,
    val thermalHeadroom: Float? = null,
    val batteryLevel: Int,
    val currentMa: Int,
    val voltageMv: Int,
    val powerW: Float,
    val isCharging: Boolean,
    val plugType: String,
    val chargerMaxW: Float? = null,
    val fastCharging: Boolean,
    val screenOn: Boolean,
    val brightnessPct: Int? = null,
    val networkType: String,
    val rxKBps: Float,
    val txKBps: Float,
    val gpsActive: Boolean,
    val cameraActive: Boolean,
    val audioActive: Boolean,
    val cpuFreqPct: Float? = null,
    val lux: Float? = null,
    val humidity: Float? = null,
    val zoneSource: String,
    val wifiDbm: Int? = null,
    val cellDbm: Int? = null,
    val cellKBps: Float? = null,
    val wifiKBps: Float? = null,
    val screenOnDuty: Float? = null,
    val chargingDuty: Float? = null,
    val fastChargingDuty: Float? = null,
    val gpsDuty: Float? = null,
    val cameraDuty: Float? = null,
    val audioDuty: Float? = null,
    val chargePowerW: Float? = null,
    val dischargePowerW: Float? = null,
)

@Serializable
private data class EventRecord(
    val timestamp: Long,
    val type: String,
    val active: Boolean,
    val value: Float? = null,
    val packageName: String? = null,
    val appName: String? = null,
)

@Serializable
private data class UsageRecord(
    val timestamp: Long,
    val resource: String,
    val packageName: String,
    val value: Float,
)

@Serializable
private data class LifetimeRecord(
    val firstSampleAt: Long,
    val lastSampleAt: Long,
    val sampleCount: Long = 0,
    val totalSec: Double = 0.0,
    val batteryTempSum: Double = 0.0,
    val referenceTempSum: Double = 0.0,
    val maxTempSum: Double = 0.0,
    val ambientSum: Double = 0.0,
    val ambientSec: Double = 0.0,
    val peakTemp: Float? = null,
    val peakAt: Long? = null,
    val chargingSec: Double = 0.0,
    val hotSec: Double = 0.0,
    val throttledSec: Double = 0.0,
    val screenOnSec: Double = 0.0,
    val dischargeEnergyWs: Double = 0.0,
    val dischargeSec: Double = 0.0,
    val inUseTempSum: Double = 0.0,
    val inUseSec: Double = 0.0,
)

@Serializable
private data class EstimateRecord(
    val idleBaselineTemp: Float? = null,
    val idleBaselineAt: Long = 0,
    val fusionX: Float? = null,
    val fusionP: Float? = null,
    val fusionAt: Long = 0,
    val fusionRth: Float = 2.5f,
    val fusionSelfHeat: Float = 0.8f,
    val ambientBiasC: Float = -5f,
    val weatherTemp: Float? = null,
    val weatherAt: Long = 0,
    val weatherLat: Float? = null,
    val weatherLon: Float? = null,
    val weatherShortwave: Float? = null,
    val weatherWindMs: Float? = null,
    val weatherIsDay: Boolean? = null,
)

private fun ArchiveRecord.toContents(): ArchiveContents {
    val storedSamples = samples.map { it.toEntity() }
    return ArchiveContents(
        exportedAt = exportedAt,
        samples = storedSamples,
        events = events.map { it.toEntity() },
        appUsage = appUsage.map { it.toEntity() },
        lifetime = lifetime?.toEntity()?.backfillInUse(storedSamples),
        estimate = estimate.toEstimate(),
    )
}

private fun ThermalSampleEntity.toRecord() = SampleRecord(
    timestamp = timestamp,
    weightSec = weightSec,
    heatLevel = heatLevel.name,
    batteryTemp = batteryTemp,
    cpuTemp = cpuTemp,
    gpuTemp = gpuTemp,
    skinTemp = skinTemp,
    modemTemp = modemTemp,
    usbTemp = usbTemp,
    maxTemp = maxTemp,
    hottestZone = hottestZone,
    ambientTemp = ambientTemp,
    ambientSource = ambientSource.name,
    thermalStatus = thermalStatus,
    thermalHeadroom = thermalHeadroom,
    batteryLevel = batteryLevel,
    currentMa = currentMa,
    voltageMv = voltageMv,
    powerW = powerW,
    isCharging = isCharging,
    plugType = plugType.name,
    chargerMaxW = chargerMaxW,
    fastCharging = fastCharging,
    screenOn = screenOn,
    brightnessPct = brightnessPct,
    networkType = networkType.name,
    rxKBps = rxKBps,
    txKBps = txKBps,
    gpsActive = gpsActive,
    cameraActive = cameraActive,
    audioActive = audioActive,
    cpuFreqPct = cpuFreqPct,
    lux = lux,
    humidity = humidity,
    zoneSource = zoneSource.name,
    wifiDbm = wifiDbm,
    cellDbm = cellDbm,
    cellKBps = cellKBps,
    wifiKBps = wifiKBps,
    screenOnDuty = screenOnDuty,
    chargingDuty = chargingDuty,
    fastChargingDuty = fastChargingDuty,
    gpsDuty = gpsDuty,
    cameraDuty = cameraDuty,
    audioDuty = audioDuty,
    chargePowerW = chargePowerW,
    dischargePowerW = dischargePowerW,
)

private fun SampleRecord.toEntity() = ThermalSampleEntity(
    timestamp = timestamp,
    weightSec = weightSec,
    heatLevel = enumValue("heat level", heatLevel),
    batteryTemp = batteryTemp,
    cpuTemp = cpuTemp,
    gpuTemp = gpuTemp,
    skinTemp = skinTemp,
    modemTemp = modemTemp,
    usbTemp = usbTemp,
    maxTemp = maxTemp,
    hottestZone = hottestZone,
    ambientTemp = ambientTemp,
    ambientSource = enumValue("ambient source", ambientSource),
    thermalStatus = thermalStatus,
    thermalHeadroom = thermalHeadroom,
    batteryLevel = batteryLevel,
    currentMa = currentMa,
    voltageMv = voltageMv,
    powerW = powerW,
    isCharging = isCharging,
    plugType = enumValue("plug type", plugType),
    chargerMaxW = chargerMaxW,
    fastCharging = fastCharging,
    screenOn = screenOn,
    brightnessPct = brightnessPct,
    networkType = enumValue("network", networkType),
    rxKBps = rxKBps,
    txKBps = txKBps,
    gpsActive = gpsActive,
    cameraActive = cameraActive,
    audioActive = audioActive,
    cpuFreqPct = cpuFreqPct,
    lux = lux,
    humidity = humidity,
    zoneSource = enumValue("zone source", zoneSource),
    wifiDbm = wifiDbm,
    cellDbm = cellDbm,
    cellKBps = cellKBps,
    wifiKBps = wifiKBps,
    screenOnDuty = screenOnDuty,
    chargingDuty = chargingDuty,
    fastChargingDuty = fastChargingDuty,
    gpsDuty = gpsDuty,
    cameraDuty = cameraDuty,
    audioDuty = audioDuty,
    chargePowerW = chargePowerW,
    dischargePowerW = dischargePowerW,
)

private fun HardwareEventEntity.toRecord() = EventRecord(
    timestamp = timestamp,
    type = type.name,
    active = active,
    value = value,
    packageName = packageName,
    appName = appName,
)

private fun EventRecord.toEntity() = HardwareEventEntity(
    timestamp = timestamp,
    type = enumValue("event", type),
    active = active,
    value = value,
    packageName = packageName,
    appName = appName,
)

private fun AppUsageEntity.toRecord() = UsageRecord(timestamp, resource.name, packageName, value)

private fun UsageRecord.toEntity() = AppUsageEntity(
    timestamp = timestamp,
    resource = enumValue("resource", resource),
    packageName = packageName,
    value = value,
)

private fun LifetimeStatsEntity.toRecord() = LifetimeRecord(
    firstSampleAt, lastSampleAt, sampleCount, totalSec, batteryTempSum, referenceTempSum, maxTempSum,
    ambientSum, ambientSec, peakTemp, peakAt, chargingSec, hotSec, throttledSec, screenOnSec,
    dischargeEnergyWs, dischargeSec, inUseTempSum, inUseSec,
)

private fun LifetimeRecord.toEntity() = LifetimeStatsEntity(
    firstSampleAt = firstSampleAt,
    lastSampleAt = lastSampleAt,
    sampleCount = sampleCount,
    totalSec = totalSec,
    batteryTempSum = batteryTempSum,
    referenceTempSum = referenceTempSum,
    maxTempSum = maxTempSum,
    ambientSum = ambientSum,
    ambientSec = ambientSec,
    peakTemp = peakTemp,
    peakAt = peakAt,
    chargingSec = chargingSec,
    hotSec = hotSec,
    throttledSec = throttledSec,
    screenOnSec = screenOnSec,
    dischargeEnergyWs = dischargeEnergyWs,
    dischargeSec = dischargeSec,
    inUseTempSum = inUseTempSum,
    inUseSec = inUseSec,
)

private fun CollectedEstimate.toRecord() = EstimateRecord(
    idleBaselineTemp = idleBaselineTemp.finiteOrNull(),
    idleBaselineAt = idleBaselineAt,
    fusionX = fusionX.finiteOrNull(),
    fusionP = fusionP.finiteOrNull(),
    fusionAt = fusionAt,
    fusionRth = fusionRth,
    fusionSelfHeat = fusionSelfHeat,
    ambientBiasC = ambientBiasC,
    weatherTemp = weatherTemp.finiteOrNull(),
    weatherAt = weatherAt,
    weatherLat = weatherLat.finiteOrNull(),
    weatherLon = weatherLon.finiteOrNull(),
    weatherShortwave = weatherShortwave.finiteOrNull(),
    weatherWindMs = weatherWindMs.finiteOrNull(),
    weatherIsDay = weatherIsDay,
)

private fun EstimateRecord.toEstimate() = CollectedEstimate(
    idleBaselineTemp = idleBaselineTemp ?: Float.NaN,
    idleBaselineAt = idleBaselineAt,
    fusionX = fusionX ?: Float.NaN,
    fusionP = fusionP ?: Float.NaN,
    fusionAt = fusionAt,
    fusionRth = fusionRth,
    fusionSelfHeat = fusionSelfHeat,
    ambientBiasC = ambientBiasC,
    weatherTemp = weatherTemp ?: Float.NaN,
    weatherAt = weatherAt,
    weatherLat = weatherLat ?: Float.NaN,
    weatherLon = weatherLon ?: Float.NaN,
    weatherShortwave = weatherShortwave ?: Float.NaN,
    weatherWindMs = weatherWindMs ?: Float.NaN,
    weatherIsDay = weatherIsDay,
)

private fun Float.finiteOrNull(): Float? = takeIf { it.isFinite() }

private inline fun <reified T : Enum<T>> enumValue(kind: String, name: String): T =
    enumValues<T>().firstOrNull { it.name == name }
        ?: throw ArchiveFormatException("Unknown $kind \"$name\" in this backup.")
