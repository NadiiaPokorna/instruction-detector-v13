package com.example.intrusiondetector.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ЛР6, Model-шар ML: типи результату, кільцевий буфер, препроцесинг і обгортка над TFLite Interpreter.

private const val TAG = "AI"

/**
 * Клас вібрації, який розпізнає модель.
 * Порядок = індекси виходу моделі, синхронно з `CLASSES` у `ml/train_model.py`.
 */
enum class VibrationClass(val label: String) {
    CALM("Спокій"),
    TOUCH("Випадковий дотик"),
    BREAK_IN("Спроба злому"),
}

/**
 * Результат одного прогнозу.
 *
 * @property cls клас з найбільшою ймовірністю.
 * @property confidence його ймовірність, 0..1.
 * @property probabilities ймовірності всіх класів у порядку [VibrationClass] (сума ≈ 1). Окрема копія,
 *   тож її безпечно передавати між потоками. `equals` для `FloatArray` порівнює посилання — для стану UI
 *   це не заважає, бо кожен прогноз і так новий об'єкт.
 * @property inferenceMs скільки тривав `Interpreter.run`, мс.
 * @property timeMillis коли зроблено прогноз (`System.currentTimeMillis`).
 */
data class Prediction(
    val cls: VibrationClass,
    val confidence: Float,
    val probabilities: FloatArray,
    val inferenceMs: Float,
    val timeMillis: Long,
)

/**
 * Кільцевий (циклічний) буфер останніх [capacity] значень.
 *
 * Проблема: моделі потрібні «останні 100 значень», а нове значення приходить 50 разів на секунду.
 * `ArrayList.add` + `removeAt(0)` щоразу зсуває весь масив (O(n)) і створює об'єкти `Float` (boxing),
 * тобто сміття для GC на головному потоці.
 *
 * Механіка: фіксований `FloatArray`, [head] — індекс наступного запису. `head = (head + 1) % capacity`:
 * коли масив заповнено, новий запис перезаписує найстаріше значення. [add] — O(1), без алокацій.
 *
 * Не потокобезпечний: використовується лише з головного потоку (див. `AiViewModel.onSample`).
 */
class CircularBuffer(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0

    /** Скільки значень накопичено (не більше [capacity]). */
    var size = 0
        private set

    /** Буфер заповнений — можна робити прогноз. */
    val isFull: Boolean get() = size == capacity

    /** Додає значення, при заповненому буфері витісняючи найстаріше. O(1). */
    fun add(v: Float) {
        data[head] = v
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Копія вмісту від найстаршого до найновішого («розгортання» кільця).
     * Найстаріше значення лежить у `head`, якщо буфер повний, інакше в 0.
     * Це нова копія, тож її безпечно передавати в інший потік, поки буфер далі наповнюється.
     */
    fun toArray(): FloatArray {
        val out = FloatArray(size)
        val start = if (size == capacity) head else 0
        for (i in 0 until size) out[i] = data[(start + i) % capacity]
        return out
    }

    /** Очищує буфер (значення не затираються, лише лічильники). */
    fun clear() {
        head = 0
        size = 0
    }
}

/**
 * ЛР6: on-device класифікатор вібрації на TensorFlow Lite.
 *
 * Модель `assets/vibration_classifier.tflite` навчена скриптом `ml/train_model.py`
 * (звіт — `ml/TRAINING.md`). Вхід `[1, 50, 1]` float32, вихід `[1, 3]` float32 (softmax).
 *
 * **Не потокобезпечний.** `Interpreter` тримає внутрішні тензори й буфери, а цей клас ще й
 * перевикористовує [inputBuffer] і [output]. Два одночасні виклики [predict] перезаписали б дані
 * один одного. Викликати лише з одного потоку за раз — `AiViewModel` гарантує це через
 * `Dispatchers.Default.limitedParallelism(1)`.
 *
 * Після використання обов'язково [close]: інтерпретатор живе в native-пам'яті (C++), яку GC не звільняє.
 */
class ActivityClassifier(context: Context) : Closeable {

    /**
     * Інтерпретатор TFLite — виконує граф моделі з `.tflite` (на відміну від повного TensorFlow, не вміє
     * навчати, лише рахувати прогноз, тому важить ~1 МБ).
     *
     * `setNumThreads(1)`: модель має 755 параметрів і рахується за частки мілісекунди, тож накладні
     * витрати на синхронізацію кількох потоків були б більшими за виграш. GPU/NNAPI-делегати не
     * використовуємо з тієї ж причини (передача даних на прискорювач дорожча за саме обчислення),
     * а NNAPI ще й оголошено застарілим в Android 15.
     */
    private val interpreter: Interpreter =
        Interpreter(loadModelFile(context), Interpreter.Options().setNumThreads(1))

    /**
     * Вхід моделі: direct `ByteBuffer` на `1 * 50 * 1` float (по 4 байти).
     * Direct-буфер лежить поза Java heap, тож JNI передає його в C++ без копіювання.
     * `nativeOrder()` — порядок байтів процесора (little-endian на ARM/x86): C++ читає float без
     * перестановки байтів. Створюється один раз і перевикористовується — нуль алокацій на прогноз.
     */
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * MODEL_WINDOW * 1 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    /** Вихід моделі `[1, 3]`: `Interpreter.run` записує сюди ймовірності. Перевикористовується. */
    private val output = Array(1) { FloatArray(VibrationClass.entries.size) }

    init {
        // Перевірка контракту: якщо модель перенавчили з іншою формою входу, краще впасти одразу
        // зі зрозумілим повідомленням, ніж мовчки рахувати сміття
        val inT = interpreter.getInputTensor(0)
        val outT = interpreter.getOutputTensor(0)
        Log.i(TAG, "input: name=${inT.name()} shape=${inT.shape().contentToString()} dtype=${inT.dataType()}")
        Log.i(TAG, "output: name=${outT.name()} shape=${outT.shape().contentToString()} dtype=${outT.dataType()}")
        val expectedIn = intArrayOf(1, MODEL_WINDOW, 1)
        val expectedOut = intArrayOf(1, VibrationClass.entries.size)
        if (!inT.shape().contentEquals(expectedIn) || inT.dataType() != DataType.FLOAT32) {
            interpreter.close()
            throw IllegalStateException(
                "Вхід моделі ${inT.shape().contentToString()} ${inT.dataType()}, " +
                    "очікувався ${expectedIn.contentToString()} FLOAT32"
            )
        }
        if (!outT.shape().contentEquals(expectedOut) || outT.dataType() != DataType.FLOAT32) {
            interpreter.close()
            throw IllegalStateException(
                "Вихід моделі ${outT.shape().contentToString()} ${outT.dataType()}, " +
                    "очікувався ${expectedOut.contentToString()} FLOAT32"
            )
        }
    }

    /**
     * Читає модель з assets як `MappedByteBuffer`.
     *
     * Memory-mapping: файл відображається у віртуальну пам'ять процесу без копіювання в Java heap,
     * а ОС підвантажує сторінки ліниво, при першому зверненні. `startOffset` потрібен, бо asset лежить
     * усередині APK (ZIP-архіву), а не окремим файлом: дескриптор вказує на весь APK, і модель
     * починається з цього зсуву. Працює лише для нестиснутого запису — див. `noCompress` у `build.gradle.kts`.
     */
    private fun loadModelFile(context: Context): MappedByteBuffer =
        context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                // map() лишається дійсним і після закриття каналу та дескриптора
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    /**
     * Прогноз у вигляді рядка, як у методичці: `"Спроба злому (92%)"`.
     * @param sensorWindow останні [RAW_WINDOW] сирих значень `|a − g|`, хронологічно.
     */
    fun classify(sensorWindow: FloatArray): String {
        val p = predict(sensorWindow)
        return "${p.cls.label} (${(p.confidence * 100).roundToInt()}%)"
    }

    /**
     * Повний прогноз: клас, усі ймовірності, час inference.
     * @param sensorWindow останні [RAW_WINDOW] сирих значень `|a − g|`, хронологічно.
     */
    fun predict(sensorWindow: FloatArray): Prediction {
        val input = toModelInput(sensorWindow)
        inputBuffer.rewind()
        for (v in input) inputBuffer.putFloat(v)
        inputBuffer.rewind()

        val t0 = SystemClock.elapsedRealtimeNanos()
        interpreter.run(inputBuffer, output)
        val inferenceMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f

        // Копія: output перевикористовується наступним прогнозом
        val probs = output[0].copyOf()
        var best = 0
        for (i in probs.indices) if (probs[i] > probs[best]) best = i
        return Prediction(
            cls = VibrationClass.entries[best],
            confidence = probs[best],
            probabilities = probs,
            inferenceMs = inferenceMs,
            timeMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Самоперевірка проти training-serving skew: проганяє golden vectors з `ml/TRAINING.md` (розділ 9)
     * через [toModelInput] і [interpreter] і порівнює з ймовірностями, які дав Python
     * (`tf.lite.Interpreter`), з допуском [GOLDEN_TOLERANCE]. Збіг доводить, що Kotlin-препроцесинг
     * і Android-рантайм дають ті самі числа, що й Python. Викликається в debug-збірці (`AiViewModel`).
     */
    fun selfTest(): Boolean {
        var ok = true
        GOLDEN.forEachIndexed { i, g ->
            val p = predict(g.raw).probabilities
            var maxDiff = 0f
            for (k in p.indices) maxDiff = max(maxDiff, abs(p[k] - g.expected[k]))
            val pass = maxDiff <= GOLDEN_TOLERANCE
            ok = ok && pass
            Log.i(TAG, "selfTest[${VibrationClass.entries[i]}]: ${p.contentToString()} maxDiff=$maxDiff ${if (pass) "OK" else "FAIL"}")
        }
        Log.i(TAG, "selfTest: ${if (ok) "OK" else "FAIL"}")
        return ok
    }

    /** Звільняє native-пам'ять інтерпретатора. */
    override fun close() = interpreter.close()

    companion object {
        /** Ім'я моделі в `app/src/main/assets/`. */
        const val MODEL_FILE = "vibration_classifier.tflite"

        // Контракт даних. Синхронно з однойменними константами в ml/train_model.py
        // (див. ml/TRAINING.md, розділ 4). Розбіжність = training-serving skew.

        /** Частота сенсора, Гц (`SENSOR_DELAY_GAME`). Синхронно з `RAW_RATE_HZ` у `train_model.py`. */
        const val RAW_RATE_HZ = 50

        /** Довжина сирого вікна: 2 с значень `|a − g|`. Синхронно з `RAW_WINDOW`. */
        const val RAW_WINDOW = 100

        /** Децимація 50 Гц → 25 Гц через максимум пари. Синхронно з `DECIMATION`. */
        const val DECIMATION = 2

        /** Довжина входу моделі. Синхронно з `MODEL_WINDOW`. */
        const val MODEL_WINDOW = RAW_WINDOW / DECIMATION

        /** Новий прогноз кожні 25 сирих семплів = 0,5 с (вікна перекриваються на 75%). Синхронно з `HOP`. */
        const val HOP = 25

        /** Нормалізація, м/с²: вхід моделі `min(v, SCALE) / SCALE` ∈ [0, 1]. Синхронно з `SCALE`. */
        const val SCALE = 10f

        /** Допуск самоперевірки [selfTest]. */
        const val GOLDEN_TOLERANCE = 1e-4f

        /**
         * Перетворення сирих даних сенсора у вхідний формат моделі. Чиста функція без Android-залежностей;
         * синхронно з `to_model_input` у `ml/train_model.py`.
         *
         * 1. `raw` — останні [RAW_WINDOW] значень `|a − g|` у хронологічному порядку.
         * 2. Децимація через максимум пари: `out[i] = max(raw[2i], raw[2i+1])`. Не середнє: удар часто займає
         *    один семпл, і усереднення з сусіднім «тихим» відліком удвічі «з'їло» б його пік.
         * 3. Нормалізація `min(v, SCALE) / SCALE` → [0, 1]: нейромережі стабільніше вчаться на входах порядку
         *    одиниці, а обрізання по [SCALE] захищає від рідкісних величезних піків.
         * 4. Результат — [MODEL_WINDOW] float; форму `[1, 50, 1]` задає розмір [inputBuffer].
         */
        fun toModelInput(raw: FloatArray): FloatArray {
            require(raw.size == RAW_WINDOW) { "Очікується $RAW_WINDOW значень, отримано ${raw.size}" }
            return FloatArray(MODEL_WINDOW) { i ->
                val v = max(raw[DECIMATION * i], raw[DECIMATION * i + 1]) // крок 2
                min(v, SCALE) / SCALE // крок 3
            }
        }
    }
}

/** Пара «сире вікно → очікуваний вихід TFLite у Python» для [ActivityClassifier.selfTest]. */
private class GoldenVector(val raw: FloatArray, val expected: FloatArray)

/**
 * Golden vectors, згенеровані `ml/train_model.py` (seed 2024), по одному на клас у порядку [VibrationClass].
 * Після перенавчання моделі замінити на новий блок з `ml/TRAINING.md`, розділ 9.
 */
private val GOLDEN = listOf(
    // CALM (noise)
    GoldenVector(
        raw = floatArrayOf(
        0.023411479f, 0.0113403285f, 0.03050915f, 0.018045187f, 0.023095125f, 0.02357342f, 0.017860595f, 0.022384116f,
        0.018385338f, 0.014041355f, 0.016771687f, 0.022872403f, 0.027496248f, 0.016641319f, 0.02332607f, 0.018238049f,
        0.022356935f, 0.019110063f, 0.027979601f, 0.031573847f, 0.025480796f, 0.01942783f, 0.026234351f, 0.018680431f,
        0.026145743f, 0.01775177f, 0.014867613f, 0.018804226f, 0.013973888f, 0.012654628f, 0.013585399f, 0.026345927f,
        0.024757614f, 0.023266379f, 0.0157405f, 0.037280004f, 0.012315941f, 0.018779371f, 0.006574845f, 0.0076958826f,
        0.017147187f, 0.024949053f, 0.020218674f, 0.024920462f, 0.013389508f, 0.022745533f, 0.0075849355f, 0.01606266f,
        0.01431914f, 0.024742398f, 0.02132717f, 0.025674498f, 0.025627641f, 0.028146444f, 0.019100463f, 0.017442422f,
        0.012238283f, 0.019732658f, 0.023230636f, 0.02152207f, 0.027811615f, 0.013639273f, 0.027682194f, 0.028252404f,
        0.026912287f, 0.013985977f, 0.020753572f, 0.029259885f, 0.011250768f, 0.02260149f, 0.015041366f, 0.020911075f,
        0.026425144f, 0.026997643f, 0.02380163f, 0.021901002f, 0.029370507f, 0.01943746f, 0.016747162f, 0.026171468f,
        0.024013443f, 0.012569318f, 0.011878176f, 0.029857423f, 0.014978059f, 0.029373985f, 0.028830368f, 0.024774203f,
        0.016558936f, 0.028199945f, 0.01934497f, 0.013604066f, 0.021394128f, 0.01555422f, 0.027612269f, 0.019241003f,
        0.02075055f, 0.02303471f, 0.0231476f, 0.013889266f,
        ),
        expected = floatArrayOf(0.99523854f, 0.0047610556f, 0.0000003553024f),
    ),
    // TOUCH (impulse)
    GoldenVector(
        raw = floatArrayOf(
        0.023169508f, 0.018628035f, 0.009283523f, 0.024388956f, 0.019688966f, 0.018276893f, 0.0130949635f, 0.02194832f,
        0.022815503f, 0.014846461f, 0.007901107f, 0.011593693f, 0.024919635f, 0.015209988f, 0.018672386f, 0.020513978f,
        0.024851523f, 0.013269424f, 0.017140105f, 0.029400019f, 0.012114869f, 0.023798728f, 0.018164244f, 0.013339712f,
        0.015826274f, 0.013366705f, 0.023506857f, 0.017933972f, 0.020021532f, 0.008786765f, 0.02164071f, 0.020155389f,
        0.031491354f, 0.016204398f, 0.006423378f, 0.013797296f, 0.008833803f, 0.0213204f, 0.015704049f, 0.011372392f,
        0.018762685f, 0.025492199f, 0.019128574f, 0.017644688f, 0.020644473f, 0.025371075f, 0.014086272f, 0.018606335f,
        0.01990025f, 0.0150227565f, 0.024931017f, 0.021028934f, 0.016246265f, 0.017631926f, 0.021181945f, 0.025423951f,
        0.017995246f, 0.013339511f, 0.013879812f, 0.014834129f, 0.016847229f, 0.016590815f, 0.0238691f, 0.025512895f,
        0.012605977f, 0.013362763f, 0.014267173f, 0.015181567f, 0.0147589445f, 0.018165534f, 0.013235902f, 0.02003486f,
        0.016913228f, 0.015901601f, 0.019897431f, 0.010976497f, 0.017712288f, 0.012287932f, 0.026422406f, 0.016854681f,
        0.013754382f, 0.01935115f, 0.014411529f, 0.016376477f, 0.009950174f, 0.013554323f, 0.0033348044f, 0.021679297f,
        0.019318486f, 0.014866403f, 0.016971154f, 0.010406588f, 1.7239938f, 0.31572717f, 0.55608916f, 0.045538064f,
        0.10920482f, 0.027315468f, 0.07386529f, 0.02884627f,
        ),
        expected = floatArrayOf(0.0f, 0.9994881f, 0.0005118466f),
    ),
    // BREAK_IN (knocks)
    GoldenVector(
        raw = floatArrayOf(
        0.06105415f, 0.04341554f, 0.05082304f, 0.050337005f, 0.041630194f, 0.37763715f, 0.28861848f, 0.15963323f,
        0.082341366f, 0.039828494f, 0.032264758f, 0.017874818f, 2.9325101f, 1.2939856f, 0.17363404f, 0.1762025f,
        0.16848148f, 0.09948246f, 0.037665244f, 0.05081881f, 0.080144085f, 0.070638984f, 0.05079569f, 0.06631015f,
        0.052861623f, 0.071146145f, 0.04396986f, 0.048918054f, 0.062019967f, 0.048506096f, 0.05614571f, 0.06441846f,
        0.05346586f, 0.04621563f, 2.3981507f, 1.2564455f, 0.4667431f, 0.30403394f, 0.08293357f, 0.10573918f,
        0.041789595f, 0.095297016f, 0.08156662f, 2.5226953f, 0.6918906f, 0.07089126f, 0.04492961f, 0.08968296f,
        0.046078954f, 0.106088795f, 0.092729434f, 0.082751386f, 0.041845337f, 0.10707591f, 0.063331634f, 0.022849571f,
        0.07574911f, 0.0491194f, 0.09974768f, 0.01660627f, 0.061185136f, 0.095595494f, 1.7529187f, 2.5243812f,
        0.15154724f, 0.584468f, 0.10470394f, 0.089085095f, 0.10787136f, 0.08630346f, 0.03412469f, 0.045974877f,
        0.04091415f, 0.0542689f, 0.03259331f, 0.023500618f, 0.05179985f, 0.025007771f, 0.05961184f, 0.056377217f,
        0.052465342f, 0.071008645f, 0.044058774f, 0.043783933f, 0.029232558f, 0.058875985f, 0.0313721f, 0.032236986f,
        0.05477992f, 0.031822942f, 0.04477834f, 0.047608286f, 0.040351946f, 0.052590545f, 0.033715162f, 0.045085285f,
        0.04634589f, 0.02754496f, 0.038953777f, 0.030548818f,
        ),
        expected = floatArrayOf(0.0f, 0.000000001862868f, 1.0f),
    ),
)
