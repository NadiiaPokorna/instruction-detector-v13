package com.example.intrusiondetector.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// ЛР5, шар Model (MVVM): локальна БД Room — сутність, DAO, база даних і репозиторій.

/**
 * Один запис історії сенсора — рядок таблиці `sensor_records`.
 *
 * `@Entity` перетворює клас на таблицю SQLite, кожна властивість стає колонкою.
 * Відповідність типів: `Long` → `INTEGER`, `Float` → `REAL`, `Boolean` → `INTEGER` 0/1
 * (у SQLite немає окремого типу BOOLEAN, Room сам перетворює `true`/`false` на 1/0).
 *
 * Індекс на `timestamp` — B-tree: прискорює `ORDER BY timestamp` (графік) і
 * `DELETE ... WHERE timestamp < ?` (ретеншн), бо SQLite не перебирає всю таблицю.
 * Ціна — трохи повільніший `INSERT`, бо при кожному записі оновлюється ще й індекс.
 */
@Entity(tableName = "sensor_records", indices = [Index("timestamp")])
data class SensorRecord(
    /**
     * Первинний ключ. `autoGenerate = true` і `id = 0`: Room підставляє в `INSERT` значення `NULL`,
     * і SQLite сам видає наступний `rowid`. Без цього довелося б вигадувати унікальні id вручну.
     */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * `System.currentTimeMillis()`, мс від 1970-01-01 UTC («настінний» час).
     * Не `SensorEvent.timestamp`: той — наносекунди від завантаження пристрою
     * (`elapsedRealtimeNanos`) і обнуляється після перезавантаження, тож для історії
     * він непридатний і незрозумілий людині в Database Inspector і на осі X.
     */
    val timestamp: Long,
    /** RMS вібрації, м/с² (та сама метрика, що йде в Firebase у ЛР3). */
    val sensorValue: Float,
    /** `true` — у цей момент `DecisionEngine` виконав автодію (ЛР4). */
    val actionTriggered: Boolean,
)

/**
 * DAO (Data Access Object) — інтерфейс доступу до таблиці `sensor_records`.
 *
 * Реалізацію (`SensorDao_Impl`) генерує KSP під час компіляції. SQL у `@Query` перевіряється
 * на етапі збірки: помилка в назві колонки чи таблиці зламає збірку, а не застосунок у рантаймі.
 *
 * `suspend`-методи main-safe: Room сам виконує їх на своєму фоновому executor-і.
 * Синхронний (не-`suspend`) метод, викликаний з UI-потоку, кинув би
 * `IllegalStateException: Cannot access database on the main thread`.
 *
 * Методи, що повертають `Flow`, «живі»: `InvalidationTracker` стежить за таблицею (Room ставить
 * на неї SQLite-тригери), і після кожного `INSERT`/`DELETE` у `sensor_records` Room сам
 * перезапускає запит і віддає новий список. Так графік оновлюється без ручного опитування.
 */
@Dao
interface SensorDao {
    /** Додає запис і повертає його `rowid` (згенерований `id`). */
    @Insert
    suspend fun insert(record: SensorRecord): Long

    /**
     * Останні [limit] записів у хронологічному порядку (для графіка).
     *
     * `:limit` — bind-параметр (`?` у prepared statement): значення не вклеюється в текст SQL,
     * тому SQL-ін'єкція неможлива. Внутрішній `DESC LIMIT` бере N найновіших записів,
     * зовнішній `ASC` перевпорядковує їх від старих до нових — так, як іде вісь X.
     */
    @Query(
        "SELECT * FROM (SELECT * FROM sensor_records ORDER BY timestamp DESC LIMIT :limit) " +
            "ORDER BY timestamp ASC"
    )
    fun getLastRecords(limit: Int): Flow<List<SensorRecord>>

    /** Останні [limit] спрацювань актуаторів, нові зверху. */
    @Query("SELECT * FROM sensor_records WHERE actionTriggered = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun getTriggeredRecords(limit: Int): Flow<List<SensorRecord>>

    /** Кількість рядків у таблиці; оновлюється так само, як інші `Flow`-запити. */
    @Query("SELECT COUNT(*) FROM sensor_records")
    fun countAll(): Flow<Int>

    /** Видаляє записи, старіші за [before] (мс epoch); повертає кількість видалених рядків. */
    @Query("DELETE FROM sensor_records WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** Очищає всю історію. */
    @Query("DELETE FROM sensor_records")
    suspend fun deleteAll()
}

/**
 * База даних Room: перелік таблиць (`entities`) і версія схеми.
 *
 * `version` і міграції: при зміні схеми (нова колонка тощо) треба підняти `version` і додати
 * `Migration(old, new)` з SQL-командою `ALTER TABLE ...`. Без міграції Room при відкритті кине
 * `IllegalStateException`. `fallbackToDestructiveMigration()` не використовуємо: він просто
 * стирає і створює БД заново, тобто історія губиться.
 *
 * `exportSchema = true` + блок `room { schemaDirectory(...) }` у `app/build.gradle.kts`:
 * схема кожної версії зберігається в `app/schemas/.../<version>.json` для контролю версій і міграцій.
 *
 * Файл БД: `/data/data/com.example.intrusiondetector/databases/intrusion_detector.db`
 * (+ `-wal` і `-shm`: Room за замовчуванням вмикає WAL-журнал — нові записи спершу йдуть у `-wal`,
 * а `-shm` — спільна пам'ять-індекс до нього; так читання не блокує запис).
 */
@Database(entities = [SensorRecord::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    /** DAO таблиці `sensor_records`; реалізацію генерує Room. */
    abstract fun sensorDao(): SensorDao

    companion object {
        /**
         * Єдиний екземпляр БД (синглтон). `RoomDatabase` — важкий об'єкт (пул з'єднань,
         * `InvalidationTracker`); два екземпляри на один файл марнували б ресурси, а спостерігачі
         * одного не дізнавалися б про записи через інший.
         *
         * `@Volatile`: запис у `INSTANCE` одразу видно всім потокам (значення не кешується в
         * кеші ядра CPU), тож інший потік не побачить застарілий `null`.
         */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Повертає синглтон БД, створюючи його при першому виклику.
         *
         * Double-checked locking: перша перевірка без блокування — швидкий шлях, коли БД уже є;
         * `synchronized` + друга перевірка — щоб два потоки, які одночасно побачили `null`,
         * не створили дві БД.
         *
         * `applicationContext`, а не Activity: синглтон живе довше за Activity, тож посилання
         * на Activity не дало б збирачу сміття її звільнити (memory leak).
         */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "intrusion_detector.db",
                ).build().also { INSTANCE = it }
            }
    }
}

/**
 * Репозиторій — єдина точка доступу до даних сенсора (single source of truth).
 *
 * У MVVM ViewModel не знає, звідки дані (Room, мережа, кеш): вона звертається лише до
 * репозиторію. Тут же — політика потоків. Якщо колись додасться синхронізація з хмарою,
 * зміниться лише цей клас, а ViewModel і UI — ні.
 */
class SensorRepository(private val dao: SensorDao) {

    /**
     * Зберігає запис. `Dispatchers.IO` — пул потоків для блокувального I/O (до 64 потоків або за
     * кількістю ядер, якщо їх більше); `withContext` перемикає корутину на нього і повертає
     * результат назад у потік викликача.
     *
     * Чесно: для `suspend` DAO Room і так виконує запит у фоні, тож `withContext(Dispatchers.IO)`
     * тут — явна гарантія main-safety (і вимога методички), а не виправлення конкретної помилки.
     */
    suspend fun insert(record: SensorRecord): Long = withContext(Dispatchers.IO) { dao.insert(record) }

    // Flow повертаємо без withContext: Room-Flow сам виконує запит у фоні,
    // а збирати його можна з будь-якого потоку.

    /** Останні [limit] записів хронологічно (див. [SensorDao.getLastRecords]). */
    fun lastRecords(limit: Int): Flow<List<SensorRecord>> = dao.getLastRecords(limit)

    /** Останні [limit] спрацювань, нові зверху. */
    fun triggered(limit: Int): Flow<List<SensorRecord>> = dao.getTriggeredRecords(limit)

    /** Кількість записів у БД. */
    fun count(): Flow<Int> = dao.countAll()

    /** Ретеншн: видаляє записи, старіші за [before]. */
    suspend fun deleteOlderThan(before: Long): Int = withContext(Dispatchers.IO) { dao.deleteOlderThan(before) }

    /** Очищає історію. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }
}
