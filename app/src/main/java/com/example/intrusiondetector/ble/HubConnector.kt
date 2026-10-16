package com.example.intrusiondetector.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Таймінги механізму надійної доставки. */
data class ConnectorTiming(
    /** Скільки чекати ACK на одну спробу. */
    val ackTimeoutMs: Long = 1_000,
    /** Затримки перед повторами: 1 спроба + стільки повторів, скільки тут елементів. */
    val retryDelaysMs: List<Long> = listOf(200, 400, 800),
    /** Затримки перед спробами перепідключення. */
    val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 3_000),
)

/**
 * Надійний зв'язок із хабом поверх будь-якого [GattLink]:
 * - машина станів з'єднання;
 * - черга: усі GATT-операції строго по одній ([gattMutex]);
 * - номер пакета `seq` 0–99, очікування ACK з таймаутом, повтори з експоненційною затримкою;
 * - автоматична відповідь `PONG` на `CMD|PING`;
 * - автоперепідключення після неочікуваного розриву.
 *
 * Не тримає посилань на Activity: [scope] — область застосунку.
 */
class HubConnector(
    private val link: GattLink,
    private val logger: BleLogger,
    private val scope: CoroutineScope,
    private val timing: ConnectorTiming = ConnectorTiming(),
) : IBleConnector {

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected())
    override val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<HubEvent>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<HubEvent> = _incoming.asSharedFlow()

    /** BLE не допускає паралельних GATT-операцій, тому все йде через один м'ютекс (чесна черга FIFO). */
    private val gattMutex = Mutex()

    /** Пакети, які чекають на ACK/NAK, за номером. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HubEvent>>()

    private val seqCounter = AtomicInteger(0)

    /** Пристрій, до якого треба бути підключеним (для перепідключення). */
    @Volatile private var target: BleDevice? = null

    /** Користувач сам від'єднався — перепідключатися не треба. */
    @Volatile private var manualDisconnect = false

    @Volatile private var reconnectJob: Job? = null

    init {
        // UNDISPATCHED — підписка відбувається одразу, жоден Notify не загубиться.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.notifications.collect { onNotification(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.linkLost.collect { onLinkLost(it) }
        }
    }

    override suspend fun connect(device: BleDevice): Result<Unit> {
        val current = _state.value
        if (current.isLinkUp && current.device?.address == device.address) return Result.success(Unit)
        if (current.isLinkUp) disconnect()
        manualDisconnect = false
        reconnectJob?.cancel()
        val result = try {
            gattMutex.withLock { establish(device, reconnectAttempt = null) }
        } catch (e: CancellationException) {
            // Корутину, що підключалася, скасовано — не залишаємо стан «Connecting».
            runCatching { link.disconnect() }
            target = null
            _state.value = BleConnectionState.Disconnected()
            throw e
        }
        return result
            .onFailure {
                // Якщо користувач натиснув «Від'єднатися» під час підключення — це не помилка.
                _state.value = if (manualDisconnect) {
                    BleConnectionState.Disconnected()
                } else {
                    BleConnectionState.Error(it.message ?: "помилка підключення")
                }
                logger.error("Підключення не вдалося: ${it.message}")
            }
    }

    /**
     * Підключення, пошук сервісів, увімкнення Notify, читання Device Info.
     * Викликається під [gattMutex].
     */
    private suspend fun establish(device: BleDevice, reconnectAttempt: Int?): Result<Unit> = gattCall {
        target = device
        if (reconnectAttempt == null) _state.value = BleConnectionState.Connecting(device)
        logger.sys("Підключення до ${device.name} (${device.address}), RSSI ${device.rssi} dBm")
        link.connect(device)

        if (reconnectAttempt == null) _state.value = BleConnectionState.DiscoveringServices(device)
        logger.sys("З'єднано, пошук сервісів…")
        val services = link.discoverServices()
        if (HubProfile.SERVICE !in services) {
            link.disconnect()
            target = null
            throw GattException("сервіс ${HubProfile.shortId(HubProfile.SERVICE)} не знайдено")
        }
        logger.sys("Знайдено сервіс [${HubProfile.shortId(HubProfile.SERVICE)}] Intrusion Hub")

        for (characteristic in listOf(HubProfile.HUB_STATUS, HubProfile.REMOTE_EVENT)) {
            link.writeDescriptor(characteristic, HubProfile.CCCD, HubProfile.ENABLE_NOTIFICATION_VALUE)
            logger.sys("CCCD [2902] = 01 00: Notify [${HubProfile.shortId(characteristic)}] увімкнено")
        }

        val info = link.read(HubProfile.DEVICE_INFO).decodeToString()
        logger.rx(HubProfile.DEVICE_INFO, info)

        _state.value = BleConnectionState.Connected(device)
        logger.sys("Підключено до ${device.name}")
    }

    override suspend fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        val device = target
        target = null
        failPending("з'єднання закрито")
        if (device == null && _state.value !is BleConnectionState.Error) {
            _state.value = BleConnectionState.Disconnected()
            return
        }
        _state.value = BleConnectionState.Disconnecting
        logger.sys("Від'єднання від ${device?.name ?: "пристрою"}")
        // Без м'ютекса: розірвати з'єднання можна будь-коли, навіть під час очікування ACK.
        runCatching { link.disconnect() }
        _state.value = BleConnectionState.Disconnected()
        logger.sys("Від'єднано")
    }

    override suspend fun sendData(data: String): Result<Unit> = gattMutex.withLock {
        val device = _state.value.takeIf { it.isLinkUp }?.device
            ?: return@withLock Result.failure(GattException("немає з'єднання з хабом"))

        val seq = seqCounter.getAndUpdate { (it + 1) % HubProtocol.SEQ_MODULO }
        val raw = when (val framed = HubProtocol.frame(seq, data)) {
            is ProtocolResult.Error -> {
                logger.error("Пакет відхилено: ${framed.reason}")
                return@withLock Result.failure(IllegalArgumentException(framed.reason))
            }
            is ProtocolResult.Ok -> framed.value
        }

        _state.value = BleConnectionState.DataTransfer(device)
        try {
            deliver(seq, raw)
        } finally {
            if (_state.value is BleConnectionState.DataTransfer) {
                _state.value = BleConnectionState.Connected(device)
            }
        }
    }

    /** Відправка з очікуванням ACK, таймаутом і повторами з тим самим [seq]. */
    private suspend fun deliver(seq: Int, raw: String): Result<Unit> = gattCall {
        val attempts = timing.retryDelaysMs.size + 1
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                val pause = timing.retryDelaysMs[attempt - 1]
                logger.warn("Повтор ${attempt}/${attempts - 1} пакета #$seq через $pause мс")
                delay(pause)
            }
            if (_state.value !is BleConnectionState.DataTransfer) throw GattException("з'єднання втрачено")

            val reply = CompletableDeferred<HubEvent>()
            pending[seq] = reply
            try {
                logger.tx(HubProfile.COMMAND, raw, if (attempt > 0) " [повтор $attempt]" else "")
                link.write(HubProfile.COMMAND, raw.toByteArray(Charsets.US_ASCII))
                when (val event = withTimeoutOrNull(timing.ackTimeoutMs) { reply.await() }) {
                    is HubEvent.Ack -> return@gattCall
                    is HubEvent.Nak -> throw GattException("хаб не розібрав пакет #$seq (NAK)")
                    else -> logger.warn("ACK #$seq не отримано за ${timing.ackTimeoutMs} мс")
                }
            } finally {
                pending.remove(seq, reply)
            }
        }
        throw GattException("пакет #$seq не доставлено після $attempts спроб")
    }.onFailure { logger.error(it.message ?: "помилка відправки") }

    override suspend fun readStatus(): Result<HubStatus> = readCharacteristic(HubProfile.HUB_STATUS) { raw ->
        HubProtocol.decodeStatus(raw)
    }.onSuccess { _incoming.emit(HubEvent.Status(it)) }

    override suspend fun readDeviceInfo(): Result<HubDeviceInfo> =
        readCharacteristic(HubProfile.DEVICE_INFO) { raw -> HubProtocol.decodeDeviceInfo(raw) }

    private suspend fun <T> readCharacteristic(
        characteristic: UUID,
        parse: (String) -> ProtocolResult<T>,
    ): Result<T> = gattMutex.withLock {
        if (!_state.value.isLinkUp) return@withLock Result.failure(GattException("немає з'єднання з хабом"))
        gattCall {
            val raw = link.read(characteristic).decodeToString()
            logger.rx(characteristic, raw)
            when (val parsed = parse(raw)) {
                is ProtocolResult.Ok -> parsed.value
                is ProtocolResult.Error -> throw GattException(parsed.reason)
            }
        }.onFailure { logger.error("Читання [${HubProfile.shortId(characteristic)}]: ${it.message}") }
    }

    private suspend fun onNotification(notification: GattNotification) {
        val raw = notification.value.decodeToString()
        logger.rx(notification.characteristic, raw)
        val parsed = when (notification.characteristic) {
            HubProfile.REMOTE_EVENT -> HubProtocol.decodeEvent(raw)
            HubProfile.HUB_STATUS -> when (val status = HubProtocol.decodeStatus(raw)) {
                is ProtocolResult.Ok -> ProtocolResult.Ok(HubEvent.Status(status.value))
                is ProtocolResult.Error -> status
            }
            else -> ProtocolResult.Error("Notify з невідомої характеристики")
        }
        val event = when (parsed) {
            is ProtocolResult.Error -> {
                logger.error("Не вдалося розібрати Notify: ${parsed.reason}")
                return
            }
            is ProtocolResult.Ok -> parsed.value
        }
        when (event) {
            is HubEvent.Ack -> completePending(event.seq, event)
            is HubEvent.Nak -> completePending(event.seq, event)
            HubEvent.RemotePing -> {
                logger.sys("Хаб перевіряє зв'язок, відповідаємо PONG")
                scope.launch { send(HubCommand.Pong) }
            }
            HubEvent.RemoteArm -> logger.sys("Віддалена команда: поставити на охорону")
            is HubEvent.Status -> Unit
        }
        _incoming.emit(event)
    }

    private fun completePending(seq: Int, event: HubEvent) {
        // ACK на пакет, якого вже не чекаємо: запізнілий після повтору або дублікат.
        if (pending[seq]?.complete(event) != true) logger.warn("$event без очікуваного пакета, ігноровано")
    }

    private fun onLinkLost(reason: String) {
        val device = target
        if (manualDisconnect || device == null) return
        // Розрив під час першого підключення обробить сам connect(); перепідключення вже триває.
        if (!_state.value.isLinkUp || reconnectJob?.isActive == true) return
        logger.error("З'єднання втрачено: $reason")
        failPending("з'єднання втрачено")
        reconnectJob = scope.launch { reconnect(device) }
    }

    private suspend fun reconnect(device: BleDevice) {
        val attempts = timing.reconnectDelaysMs.size
        for ((index, pause) in timing.reconnectDelaysMs.withIndex()) {
            val attempt = index + 1
            _state.value = BleConnectionState.Reconnecting(device, attempt)
            logger.warn("Перепідключення $attempt/$attempts через $pause мс")
            delay(pause)
            val result = gattMutex.withLock { establish(device, reconnectAttempt = attempt) }
            if (result.isSuccess) {
                logger.sys("З'єднання відновлено")
                return
            }
            logger.warn("Спроба $attempt невдала: ${result.exceptionOrNull()?.message}")
        }
        target = null
        val message = "Не вдалося відновити з'єднання з ${device.name}"
        logger.error(message)
        _state.value = BleConnectionState.Disconnected(message)
    }

    private fun failPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(GattException(reason)) }
        pending.clear()
    }

    /** Як runCatching, але не ковтає скасування корутини. */
    private inline fun <T> gattCall(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
