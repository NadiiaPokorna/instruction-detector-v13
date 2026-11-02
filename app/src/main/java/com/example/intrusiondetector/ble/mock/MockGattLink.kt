package com.example.intrusiondetector.ble.mock

import com.example.intrusiondetector.ble.BleDevice
import com.example.intrusiondetector.ble.BleLogger
import com.example.intrusiondetector.ble.GattException
import com.example.intrusiondetector.ble.GattLink
import com.example.intrusiondetector.ble.GattNotification
import com.example.intrusiondetector.ble.HubProfile
import com.example.intrusiondetector.ble.HubProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Імітація GATT-з'єднання з [VirtualHub]: затримки підключення й пошуку сервісів,
 * CCCD, затримка відповідей, втрати пакетів і розриви згідно з [config].
 */
class MockGattLink(
    val hub: VirtualHub,
    val config: MutableStateFlow<MockLinkConfig>,
    private val logger: BleLogger,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) : GattLink {

    private val _notifications = MutableSharedFlow<GattNotification>(extraBufferCapacity = 64)
    override val notifications: Flow<GattNotification> = _notifications.asSharedFlow()

    private val _linkLost = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val linkLost: Flow<String> = _linkLost.asSharedFlow()

    @Volatile private var device: BleDevice? = null

    /** Характеристики, для яких через CCCD увімкнено Notify. */
    private val notifyEnabled = ConcurrentHashMap.newKeySet<UUID>()

    /** Корутини «з боку хаба» (PING, батарея, перешкоди, відповіді) живуть, поки є з'єднання. */
    @Volatile private var session: CoroutineScope? = null

    /** Скільки разів телефон записав у Command — для тестів повторів. */
    val commandWrites = AtomicInteger(0)

    private val cfg get() = config.value

    override suspend fun connect(device: BleDevice) {
        delay(cfg.connectMs)
        if (roll(cfg.packetLoss)) throw GattException("GATT_ERROR (133): пристрій не відповів")
        endSession()
        notifyEnabled.clear()
        this.device = device
        val newSession = scope + SupervisorJob(scope.coroutineContext[Job])
        session = newSession
        if (device.isTarget) startHubLoops(newSession)
    }

    override suspend fun discoverServices(): List<UUID> {
        val d = requireDevice()
        delay(cfg.discoverMs)
        return if (d.isTarget) listOf(GAP_SERVICE, HubProfile.SERVICE) else listOf(GAP_SERVICE, BATTERY_SERVICE)
    }

    override suspend fun writeDescriptor(characteristic: UUID, descriptor: UUID, value: ByteArray) {
        requireDevice()
        delay(WRITE_MS)
        if (descriptor == HubProfile.CCCD && value.contentEquals(HubProfile.ENABLE_NOTIFICATION_VALUE)) {
            notifyEnabled += characteristic
        }
    }

    override suspend fun write(characteristic: UUID, value: ByteArray) {
        requireDevice()
        if (characteristic != HubProfile.COMMAND) throw GattException("характеристика не підтримує запис")
        delay(WRITE_MS)
        commandWrites.incrementAndGet()
        if (roll(cfg.packetLoss)) {
            logger.warn("Mock: пакет втрачено в ефірі, хаб його не отримав")
            return
        }
        val replies = hub.onWrite(value.decodeToString())
        session?.launch {
            delay(replyDelay())
            replies.forEach { notify(it) }
        }
    }

    override suspend fun read(characteristic: UUID): ByteArray {
        requireDevice()
        delay(READ_MS)
        val text = when (characteristic) {
            HubProfile.DEVICE_INFO -> hub.deviceInfo
            HubProfile.HUB_STATUS -> HubProtocol.encodeStatus(hub.status.value)
            else -> throw GattException("характеристика не підтримує читання")
        }
        return text.toByteArray(Charsets.US_ASCII)
    }

    override suspend fun disconnect() {
        endSession()
        delay(WRITE_MS)
    }

    /** Імітація розриву зв'язку (вийшли із зони дії, перешкоди). */
    fun dropConnection(reason: String = "пристрій поза зоною дії") {
        if (device == null) return
        endSession()
        _linkLost.tryEmit(reason)
    }

    /** Кнопка на хабі — він сам ініціює `CMD|ARM`. */
    fun pressHubArmButton() {
        val s = session ?: return
        val replies = hub.pressArmButton()
        s.launch { replies.forEach { notify(it) } }
    }

    private fun startHubLoops(s: CoroutineScope) {
        s.launch {
            while (isActive) {
                delay(cfg.pingIntervalMs)
                notify(hub.ping())
            }
        }
        s.launch {
            while (isActive) {
                delay(cfg.batteryDrainMs)
                hub.drainBattery()?.let { notify(it) }
            }
        }
        s.launch {
            while (isActive) {
                delay(cfg.faultCheckMs)
                if (roll(cfg.dropChance)) {
                    logger.warn("Mock: імітація розриву з'єднання")
                    dropConnection("перешкоди в радіоканалі")
                }
            }
        }
    }

    private suspend fun notify(reply: HubReply) {
        if (device == null || reply.characteristic !in notifyEnabled) return
        if (roll(cfg.notifyLoss)) {
            logger.warn("Mock: Notify [${HubProfile.shortId(reply.characteristic)}] ${reply.text} втрачено")
            return
        }
        _notifications.emit(GattNotification(reply.characteristic, reply.text.toByteArray(Charsets.US_ASCII)))
    }

    private fun endSession() {
        device = null
        notifyEnabled.clear()
        session?.cancel()
        session = null
    }

    private fun requireDevice(): BleDevice = device ?: throw GattException("немає з'єднання")

    private fun roll(probability: Double) = probability > 0 && random.nextDouble() < probability

    private fun replyDelay(): Long = with(cfg.replyDelayMs) { random.nextLong(first, last + 1) }

    private companion object {
        const val WRITE_MS = 15L
        const val READ_MS = 40L
        val GAP_SERVICE: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    }
}
