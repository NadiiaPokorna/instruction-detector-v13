"""ЛР6: навчання крихітної 1D-CNN, що класифікує вікно вібрації, і експорт у TensorFlow Lite.

Що робить скрипт (запуск: ``ml/.venv/bin/python ml/train_model.py`` з кореня репозиторію):
  1. Генерує синтетичний датасет: 3 осі акселерометра на 50 Гц → той самий фільтр гравітації,
     що й ``IntrusionDetector`` у застосунку → ``|a − g|`` → препроцесинг (розділ «Контракт»).
  2. Навчає модель ``Conv1D → MaxPool → Conv1D → GlobalMaxPool → Dense(softmax)``.
  3. Конвертує її в ``app/src/main/assets/vibration_classifier.tflite`` (float32, статичний вхід).
  4. Перевіряє саме ``.tflite`` через ``tf.lite.Interpreter``: тензори, метрики на test-set,
     різниця з Keras, golden vectors для самоперевірки в застосунку.
  5. Перезаписує звіт ``ml/TRAINING.md`` реальними числами цього запуску.

Чому TensorFlow саме 2.17 (див. ``ml/requirements.txt``). Android-рантайм —
``org.tensorflow:tensorflow-lite:2.17.0``. Конвертер записує в ``.tflite`` версію кожного
оператора (наприклад, ``FULLY_CONNECTED`` v12). Конвертер новішого TF може записати версію,
якої старий рантайм ще не знає, і тоді застосунок упаде при створенні ``Interpreter`` з
``Didn't find op for builtin opcode ... version ...``. Правило: версія конвертера ≤ версії рантайму.
Найпростіше гарантувати це, взявши однакові версії.
"""

from __future__ import annotations

import datetime as dt
import io
import os
import platform
import sys
import time
from pathlib import Path

import numpy as np
import sklearn
import tensorflow as tf
from sklearn.metrics import classification_report, confusion_matrix, precision_recall_fscore_support
from sklearn.model_selection import train_test_split

keras = tf.keras

# ---------------------------------------------------------------------------------------------
# Шляхи
# ---------------------------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = ROOT / "app/src/main/assets/vibration_classifier.tflite"
REPORT_PATH = ROOT / "ml/TRAINING.md"
BUILD_DIR = ROOT / "ml/build"  # тимчасові артефакти (SavedModel запасного шляху), у .gitignore

# ---------------------------------------------------------------------------------------------
# Контракт даних. Синхронно з companion object ActivityClassifier
# (app/src/main/java/com/example/intrusiondetector/ml/ActivityClassifier.kt).
# Якщо змінити тут, а не там (або навпаки), виникне training-serving skew: модель отримає
# в застосунку не ті дані, на яких навчалась, і тихо «тупитиме» без жодної помилки.
# ---------------------------------------------------------------------------------------------
RAW_RATE_HZ = 50  # синхронно з ActivityClassifier.RAW_RATE_HZ: частота сенсора (SENSOR_DELAY_GAME)
RAW_WINDOW = 100  # синхронно з ActivityClassifier.RAW_WINDOW: 2 с сирих значень |a − g|
DECIMATION = 2  # синхронно з ActivityClassifier.DECIMATION: 50 Гц → 25 Гц
MODEL_WINDOW = RAW_WINDOW // DECIMATION  # синхронно з ActivityClassifier.MODEL_WINDOW: 50 відліків
HOP = 25  # синхронно з ActivityClassifier.HOP: прогноз кожні 0,5 с (у навчанні не використовується)
SCALE = 10.0  # синхронно з ActivityClassifier.SCALE: м/с², нормалізація до [0, 1]
CLASSES = ["CALM", "TOUCH", "BREAK_IN"]  # синхронно з VibrationClass: порядок = індекс виходу

# Параметри фільтра гравітації — синхронно з sensor/IntrusionDetector.kt (Thresholds).
GRAVITY = 9.80665  # Thresholds.GRAVITY
GRAVITY_TAU_S = 0.5  # Thresholds.GRAVITY_TAU_S
DT = 1.0 / RAW_RATE_HZ

# ---------------------------------------------------------------------------------------------
# Генератор
# ---------------------------------------------------------------------------------------------
SEED = 42
PER_CLASS = 3000  # вікон на клас; класи збалансовані
WARMUP_S = 3.0  # «розгін» перед вікном: фільтр гравітації стабілізується, як у застосунку
WINDOW_S = RAW_WINDOW / RAW_RATE_HZ  # 2 с
TOTAL_N = int(round((WARMUP_S + WINDOW_S) * RAW_RATE_HZ))  # 250 семплів на одну симуляцію
WIN_START_S = WARMUP_S  # вікно — останні RAW_WINDOW семплів

GOLDEN_SEED = 2024  # окремий seed для трьох golden vectors


def alpha(dt_s: float, tau: float) -> float:
    """Коефіцієнт ФНЧ першого порядку, ідентичний ``IntrusionDetector.alpha``: ``dt / (tau + dt)``."""
    return dt_s / (tau + dt_s)


def dynamic_accel(acc: np.ndarray) -> np.ndarray:
    """Повторює кроки 1–2 ``IntrusionDetector.process``: ``|a − g|`` для серії семплів.

    ``acc`` — масив ``(n, 3)`` сирого прискорення (м/с², з гравітацією). Гравітація ``g``
    оцінюється фільтром нижніх частот по кожній осі, ініціалізованим першим семплом.
    Обчислення у float32 — як у Kotlin, де всі величини ``Float``.
    """
    acc = acc.astype(np.float32)
    a = np.float32(alpha(DT, GRAVITY_TAU_S))
    g = acc[0].copy()
    out = np.empty(len(acc), dtype=np.float32)
    for i, x in enumerate(acc):
        if i > 0:
            g += a * (x - g)
        d = x - g
        out[i] = np.sqrt(np.sum(d * d, dtype=np.float32))
    return out


def to_model_input(raw: np.ndarray) -> np.ndarray:
    """Препроцесинг з контракту; синхронно з ``ActivityClassifier.toModelInput``.

    1. ``raw`` — останні ``RAW_WINDOW`` значень ``|a − g|`` у хронологічному порядку.
    2. Децимація через максимум пари: ``out[i] = max(raw[2i], raw[2i+1])``. Не середнє: удар часто
       займає один семпл, і усереднення з сусіднім «тихим» відліком удвічі зменшило б його пік.
    3. Нормалізація ``min(v, SCALE) / SCALE`` → [0, 1]. Обрізання захищає від рідкісних величезних
       піків, які інакше домінували б у згортках.
    Повертає ``float32`` форми ``(MODEL_WINDOW,)``; форму ``[1, 50, 1]`` додає викликач.
    """
    raw = np.asarray(raw, dtype=np.float32)
    assert raw.shape == (RAW_WINDOW,)
    dec = raw.reshape(MODEL_WINDOW, DECIMATION).max(axis=1)
    scale = np.float32(SCALE)
    return (np.minimum(dec, scale) / scale).astype(np.float32)


def random_unit(rng: np.random.Generator) -> np.ndarray:
    """Випадковий одиничний 3D-вектор (напрям удару, вібрації або осі повороту)."""
    v = rng.normal(size=3)
    return v / np.linalg.norm(v)


def perpendicular(u: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Випадковий одиничний вектор, перпендикулярний ``u`` (площина повороту гравітації)."""
    v = random_unit(rng)
    v -= u * np.dot(u, v)
    return v / np.linalg.norm(v)


T = np.arange(TOTAL_N) / RAW_RATE_HZ  # час кожного семпла симуляції, с


def impulse(t0: float, amp: float, ring_s: float, freq: float, direction: np.ndarray) -> np.ndarray:
    """Удар: миттєвий пік ``amp`` у момент ``t0`` і загасаючий «дзвін» поверхні.

    ``amp·exp(−τ/k)·cos(2πfτ)``, де ``k = ring_s / 3``: за ``ring_s`` амплітуда падає до ≈5%.
    Повертає ``(TOTAL_N, 3)`` — внесок у прискорення вздовж ``direction``.
    """
    tau = T - t0
    env = np.where(tau >= 0, np.exp(-np.clip(tau, 0, None) / (ring_s / 3)), 0.0)
    return (amp * env * np.cos(2 * np.pi * freq * np.clip(tau, 0, None)))[:, None] * direction


def sine(freq: float, amp: float, direction: np.ndarray, t_from: float, t_to: float,
         rng: np.random.Generator) -> np.ndarray:
    """Синусоїдальна вібрація частотою ``freq`` на відрізку ``[t_from, t_to)``.

    Із плавними фронтами 50 мс, щоб не створювати штучного удару на початку.
    """
    phase = rng.uniform(0, 2 * np.pi)
    s = amp * np.sin(2 * np.pi * freq * T + phase)
    edge = 0.05
    env = np.clip(np.minimum(T - t_from, t_to - T) / edge, 0, 1)
    return (s * env)[:, None] * direction


def rotation_profile(kind: str, t0: float, dur: float, delta: float) -> np.ndarray:
    """Кут повороту пристрою θ(t), рад: один рух повзунка Device Pose в емуляторі.

    ``step`` — плавний перехід у нове положення (піднятий косинус), ``bump`` — туди й назад
    (напівсинус). Обидва після фільтра гравітації дають «горб» у ``|a − g|``.
    """
    s = np.clip((T - t0) / dur, 0, 1)
    if kind == "step":
        return delta * (1 - np.cos(np.pi * s)) / 2
    inside = (T >= t0) & (T <= t0 + dur)
    return np.where(inside, delta * np.sin(np.pi * s), 0.0)


def gravity_track(theta: np.ndarray, u: np.ndarray, v: np.ndarray) -> np.ndarray:
    """Вектор гравітації в системі координат пристрою, що повертається на кут θ(t) у площині (u, v)."""
    return GRAVITY * (np.cos(theta)[:, None] * u + np.sin(theta)[:, None] * v)


def hump_delta(target: float, kind: str, dur: float, u: np.ndarray, v: np.ndarray) -> float:
    """Кут, при якому одиничний рух дає пік ``|a − g|`` ≈ ``target`` м/с².

    Фільтр запізнюється, тож пік менший за ``g·θ``; один пробний прогін фільтра і лінійне
    масштабування (для малих кутів залежність майже лінійна).
    """
    t0 = WIN_START_S + 0.5
    probe = target / GRAVITY
    peak = dynamic_accel(gravity_track(rotation_profile(kind, t0, dur, probe), u, v)).max()
    return probe * target / max(peak, 1e-6)


def in_window(t: float) -> bool:
    """Чи потрапляє момент ``t`` (с від початку симуляції) у вікно, яке бачить модель."""
    return WIN_START_S <= t < WIN_START_S + WINDOW_S


def simulate(cls: str, rng: np.random.Generator, subtype: str | None = None) -> tuple[np.ndarray, str]:
    """Одна симуляція 5 с (3 с розгону + 2 с вікна) → сирі ``|a − g|`` останніх 2 с і підтип.

    Аугментація вбудована: випадкове положення подій у вікні (зсув у часі), масштаб амплітуди
    ×0,8–1,2 для всього «корисного» сигналу, випадковий рівень шуму сенсора.
    """
    u = random_unit(rng)  # початкове положення пристрою (напрям гравітації)
    v = perpendicular(u, rng)
    theta = np.zeros(TOTAL_N)
    events = np.zeros((TOTAL_N, 3))
    w0 = WIN_START_S

    if cls == "CALM":
        subtype = subtype or rng.choice(["noise", "background"])
        if subtype == "background":
            # Фонова вібрація: холодильник, кроки поруч. Амплітуда < 0,15 — нижче порогу R3.
            events += sine(rng.uniform(8, 25), rng.uniform(0.02, 0.15), random_unit(rng), 0, 99, rng)

    elif cls == "TOUCH":
        subtype = subtype or rng.choice(["impulse", "hump", "two_weak"])
        if subtype == "impulse":
            # Рівно один удар десь у вікні (лишаємо 0,15 с до кінця, щоб пік точно потрапив)
            t0 = rng.uniform(w0, w0 + WINDOW_S - 0.15)
            events += impulse(t0, rng.uniform(0.8, 5), rng.uniform(0.03, 0.12), rng.uniform(10, 25),
                              random_unit(rng))
        elif subtype == "hump":
            # Один ривок повзунка Device Pose: плавний «горб» 0,15–0,5 с
            kind = rng.choice(["step", "bump"])
            dur = rng.uniform(0.15, 0.5)
            t0 = rng.uniform(w0 - 0.1, w0 + WINDOW_S - dur)
            delta = hump_delta(rng.uniform(0.5, 3), kind, dur, u, v) * rng.choice([-1, 1])
            theta += rotation_profile(kind, t0, dur, delta)
        else:
            # Два слабкі дотики (< 1,5 м/с²) — ще не схоже на злом
            t1 = rng.uniform(w0, w0 + WINDOW_S - 0.4)
            t2 = rng.uniform(t1 + 0.2, min(t1 + 1.5, w0 + WINDOW_S - 0.1))
            for t0 in (t1, t2):
                events += impulse(t0, rng.uniform(0.5, 1.5), rng.uniform(0.03, 0.12), rng.uniform(10, 25),
                                  random_unit(rng))

    elif cls == "BREAK_IN":
        subtype = subtype or rng.choice(["knocks", "humps", "drill"])
        if subtype == "knocks":
            # Серія 3–6 сильних ударів з інтервалами 0,15–0,6 с; принаймні 3 мають бути у вікні
            while True:
                n = rng.integers(3, 7)
                gaps = rng.uniform(0.15, 0.6, size=n - 1)
                times = rng.uniform(w0 - 0.5, w0 + 0.6) + np.concatenate([[0], np.cumsum(gaps)])
                if sum(in_window(t) and t < w0 + WINDOW_S - 0.05 for t in times) >= 3:
                    break
            for t0 in times:
                events += impulse(t0, rng.uniform(2, 10), rng.uniform(0.03, 0.12), rng.uniform(10, 25),
                                  random_unit(rng))
        elif subtype == "humps":
            # Швидке смикання повзунка туди-назад: 3–5 «горбів» підряд, знак повороту чергується
            while True:
                n = rng.integers(3, 6)
                durs = rng.uniform(0.15, 0.4, size=n)
                pauses = rng.uniform(0.0, 0.25, size=n)
                starts = rng.uniform(w0 - 0.3, w0 + 0.3) + np.concatenate([[0], np.cumsum(durs + pauses)[:-1]])
                if sum(in_window(s) and s + d < w0 + WINDOW_S for s, d in zip(starts, durs)) >= 3:
                    break
            sign = rng.choice([-1, 1])
            amp = rng.uniform(0.8, 5)
            for s, d in zip(starts, durs):
                kind = rng.choice(["step", "bump"])
                delta = hump_delta(amp * rng.uniform(0.8, 1.2), kind, d, u, v) * sign
                theta += rotation_profile(kind, s, d, delta)
                sign = -sign
        else:
            # Свердління / пиляння: тривала сильна вібрація 15–60 Гц, RMS ≥ 0,8, ≥ 1,2 с у вікні.
            # RMS синуса = A/√2, тож A ∈ [1,13; 4,2]. Частоти > 25 Гц при дискретизації 50 Гц
            # «загортаються» (aliasing) — так само, як у справжньому сенсорі.
            dur = rng.uniform(1.2, 2.5)
            t_from = rng.uniform(w0 + WINDOW_S - dur - 0.3, w0 + WINDOW_S - 1.2)
            t_from = max(t_from, w0 - 0.5)
            events += sine(rng.uniform(15, 60), rng.uniform(0.8, 3.0) * np.sqrt(2), random_unit(rng),
                           t_from, t_from + dur, rng)
            # Іноді друга гармоніка (нерівномірний інструмент)
            if rng.random() < 0.5:
                events += sine(rng.uniform(15, 60), rng.uniform(0.2, 0.8), random_unit(rng),
                               t_from, t_from + dur, rng)
    else:
        raise ValueError(cls)

    # Аугментація: масштаб амплітуди корисного сигналу (поворот теж масштабується)
    k = rng.uniform(0.8, 1.2)
    acc = gravity_track(theta * k, u, v) + events * k
    # Шум сенсора 0,01–0,08 м/с² по кожній осі (рівномірний, як у IntrusionDetectorTest)
    noise = rng.uniform(0.01, 0.08)
    acc += rng.uniform(-noise, noise, size=acc.shape)
    return dynamic_accel(acc)[-RAW_WINDOW:], str(subtype)


def make_dataset(rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """``PER_CLASS`` вікон на клас → ``X (N, 50, 1)``, ``y (N,)``, підтипи (для статистики у звіті)."""
    xs, ys, subs = [], [], []
    for label, cls in enumerate(CLASSES):
        for _ in range(PER_CLASS):
            raw, sub = simulate(cls, rng)
            xs.append(to_model_input(raw))
            ys.append(label)
            subs.append(f"{cls}/{sub}")
    return np.stack(xs)[..., None], np.array(ys, dtype=np.int64), subs


# ---------------------------------------------------------------------------------------------
# Модель
# ---------------------------------------------------------------------------------------------
def build_model() -> keras.Model:
    """Крихітна 1D-CNN (≈750 параметрів).

    Чому CNN, а не MLP: MLP має окрему вагу на кожну позицію вікна, тож удар на 0,3 с і на 1,7 с
    для неї — різні патерни, яких треба навчитися окремо. Згортка використовує ті самі ваги в
    кожній позиції і знаходить патерн будь-де.
    """
    return keras.Sequential(
        [
            keras.Input(shape=(MODEL_WINDOW, 1)),
            # Conv1D: 8 фільтрів-«трафаретів» довжиною 5 відліків (0,2 с при 25 Гц), що ковзають по
            # сигналу. Кожен шукає локальний патерн (гострий пік, загасання, плавний схил) будь-де у
            # вікні. padding="same" зберігає довжину 50.
            keras.layers.Conv1D(8, 5, padding="same", activation="relu"),
            # MaxPooling1D(2): довжина 50 → 25, з кожної пари лишається найсильніша реакція фільтра.
            keras.layers.MaxPooling1D(2),
            # Другий Conv1D: 16 фільтрів по 5 відліків уже після пулінгу. Кожен відлік тепер — 0,08 с,
            # тож поле зору ≈ 0,4 с + поле першого шару: вистачає, щоб відрізнити «один пік»
            # від «кількох піків поруч» і від безперервної вібрації.
            keras.layers.Conv1D(16, 5, padding="same", activation="relu"),
            # GlobalMaxPooling1D: на кожен із 16 фільтрів — одне число «наскільки сильно патерн
            # проявився хоч десь у вікні». Позиція події більше не важлива.
            keras.layers.GlobalMaxPooling1D(),
            # Dense + softmax: лінійна комбінація 16 ознак → 3 логіти → ймовірності, сума = 1.
            keras.layers.Dense(len(CLASSES), activation="softmax"),
        ],
        name="vibration_classifier",
    )


# ---------------------------------------------------------------------------------------------
# Конвертація
# ---------------------------------------------------------------------------------------------
def convert(model: keras.Model) -> tuple[bytes, str]:
    """Keras → ``.tflite`` зі статичним входом ``[1, 50, 1]``. Повертає байти і назву шляху.

    Навчаємо з динамічним batch (``None``), але в застосунку завжди одне вікно, тож фіксуємо
    batch = 1: рантайм знає всі розміри заздалегідь і не мусить робити ``resizeInput``.
    Без квантування (float32): модель важить кілька КБ, int8 майже не зменшить розмір, але
    вхід/вихід стали б цілочисельними з параметрами scale/zero_point, і препроцесинг ускладнився б.
    """
    spec = tf.TensorSpec([1, MODEL_WINDOW, 1], tf.float32, name="window")

    # Основний шлях за ТЗ — tf.function(input_signature=...) → from_concrete_functions — з Keras 3
    # у TF 2.17 не працює: MLIR-конвертер падає на змінних Keras 3 з
    # "error: missing attribute 'value' / LLVM ERROR: Failed to infer result type(s)" і аварійно
    # завершує весь процес (abort у C++), тож try/except його не перехопить. Тому одразу запасний шлях:
    # Keras 3 сам експортує SavedModel із заданою сигнатурою, а конвертер читає SavedModel.
    saved = BUILD_DIR / "saved_model"
    model.export(str(saved), input_signature=[spec])
    conv = tf.lite.TFLiteConverter.from_saved_model(str(saved))
    return conv.convert(), ("model.export(input_signature=[1,50,1]) → TFLiteConverter.from_saved_model "
                           "(from_concrete_functions з Keras 3 аварійно завершує процес: LLVM ERROR)")


def tflite_predict(interp: tf.lite.Interpreter, x: np.ndarray) -> np.ndarray:
    """Прогін вікон ``(N, 50, 1)`` по одному через TFLite-інтерпретатор (batch = 1, як в Android)."""
    inp = interp.get_input_details()[0]["index"]
    out = interp.get_output_details()[0]["index"]
    res = np.empty((len(x), len(CLASSES)), dtype=np.float32)
    for i, w in enumerate(x):
        interp.set_tensor(inp, w[None].astype(np.float32))
        interp.invoke()
        res[i] = interp.get_tensor(out)[0]
    return res


def kotlin_floats(a: np.ndarray, per_line: int = 8, indent: str = "        ") -> str:
    """Масив float32 → тіло ``floatArrayOf(...)``. ``unique=True`` дає найкоротший запис,
    який при розборі в Kotlin (``Float``) відновлює рівно той самий float32."""
    items = [np.format_float_positional(np.float32(v), unique=True, trim="0") + "f" for v in a]
    lines = [", ".join(items[i:i + per_line]) for i in range(0, len(items), per_line)]
    return (",\n" + indent).join(lines)


def ascii_bar(v: float, vmax: float, width: int = 30) -> str:
    """Горизонтальна смуга для ASCII-графіка у звіті."""
    n = int(round(width * v / vmax)) if vmax > 0 else 0
    return "#" * n


# ---------------------------------------------------------------------------------------------
# Головний сценарій
# ---------------------------------------------------------------------------------------------
def main() -> None:
    """Генерація → навчання → конвертація → перевірка → звіт."""
    # Відтворюваність: однакові дані, ініціалізація ваг і порядок батчів при кожному запуску
    keras.utils.set_random_seed(SEED)
    tf.config.experimental.enable_op_determinism()
    rng = np.random.default_rng(SEED)
    BUILD_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Дані
    t_gen = time.time()
    X, y, subs = make_dataset(rng)
    gen_s = time.time() - t_gen
    idx = np.arange(len(y))
    # Стратифікована розбивка 70 / 15 / 15: у кожній частині класи в тій самій пропорції
    i_train, i_tmp = train_test_split(idx, test_size=0.30, stratify=y, random_state=SEED)
    i_val, i_test = train_test_split(i_tmp, test_size=0.50, stratify=y[i_tmp], random_state=SEED)
    print(f"Дані: {len(y)} вікон, train={len(i_train)} val={len(i_val)} test={len(i_test)}, {gen_s:.1f} с")

    # 2. Навчання
    model = build_model()
    model.compile(optimizer=keras.optimizers.Adam(1e-2),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    buf = io.StringIO()
    model.summary(print_fn=lambda s, **_: buf.write(s + "\n"))
    summary = buf.getvalue()
    params = model.count_params()
    print(summary)

    t_fit = time.time()
    hist = model.fit(
        X[i_train], y[i_train], validation_data=(X[i_val], y[i_val]),
        epochs=50, batch_size=64, verbose=2,
        # EarlyStopping: зупиняємось, коли val_loss 5 епох поспіль не покращується, і повертаємо
        # ваги найкращої епохи — захист від перенавчання.
        callbacks=[keras.callbacks.EarlyStopping(patience=5, restore_best_weights=True)],
    )
    fit_s = time.time() - t_fit
    h = hist.history
    epochs_run = len(h["loss"])
    best_epoch = int(np.argmin(h["val_loss"])) + 1

    # 3. Конвертація
    tflite_bytes, conv_path = convert(model)
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.write_bytes(tflite_bytes)
    print(f"Модель: {MODEL_PATH} ({len(tflite_bytes)} байт), шлях: {conv_path}")

    # 4. Перевірка саме .tflite
    interp = tf.lite.Interpreter(model_path=str(MODEL_PATH))
    interp.allocate_tensors()
    in_det = interp.get_input_details()[0]
    out_det = interp.get_output_details()[0]
    for name, d in (("input", in_det), ("output", out_det)):
        print(f"{name}: name={d['name']} shape={d['shape'].tolist()} dtype={d['dtype'].__name__} "
              f"quantization={d['quantization']}")
    assert in_det["shape"].tolist() == [1, MODEL_WINDOW, 1] and in_det["dtype"] == np.float32
    assert out_det["shape"].tolist() == [1, len(CLASSES)] and out_det["dtype"] == np.float32

    probs_test = tflite_predict(interp, X[i_test])
    pred_test = probs_test.argmax(axis=1)
    acc = float((pred_test == y[i_test]).mean())
    cm = confusion_matrix(y[i_test], pred_test, labels=range(len(CLASSES)))
    prec, rec, f1, sup = precision_recall_fscore_support(y[i_test], pred_test, labels=range(len(CLASSES)))
    print(classification_report(y[i_test], pred_test, target_names=CLASSES, digits=4))
    print(f"TFLite test accuracy: {acc:.4f}")

    # Keras vs TFLite на 100 вікнах
    cmp = X[i_test[:100]]
    diff = float(np.abs(model.predict(cmp, verbose=0) - tflite_predict(interp, cmp)).max())
    print(f"max |Keras − TFLite| = {diff:.3e}")
    assert diff < 1e-5, "TFLite розходиться з Keras"

    # Помилки по підтипах (де модель плутається)
    sub_arr = np.array(subs)[i_test]
    sub_stats = []
    for s in sorted(set(sub_arr)):
        m = sub_arr == s
        sub_stats.append((s, int(m.sum()), float((pred_test[m] == y[i_test][m]).mean())))

    # 5. Golden vectors: по одному фіксованому вікну на клас (окремий seed, базовий підтип)
    grng = np.random.default_rng(GOLDEN_SEED)
    golden = []
    for cls, sub in zip(CLASSES, ["noise", "impulse", "knocks"]):
        raw, _ = simulate(cls, grng, subtype=sub)
        mi = to_model_input(raw)
        p = tflite_predict(interp, mi[None, :, None])[0]
        golden.append((cls, sub, raw, mi, p))

    kotlin_golden = []
    for cls, sub, raw, mi, p in golden:
        kotlin_golden.append(
            f"    // {cls} ({sub})\n"
            f"    GoldenVector(\n"
            f"        raw = floatArrayOf(\n        {kotlin_floats(raw)},\n        ),\n"
            f"        expected = floatArrayOf({kotlin_floats(p, indent='')}),\n"
            f"    ),"
        )
    kotlin_block = "\n".join(kotlin_golden)
    print("\n// ---- Golden vectors для ActivityClassifier.kt ----\n" + kotlin_block)

    # 6. Звіт
    write_report(
        now=dt.datetime.now().strftime("%Y-%m-%d %H:%M"), gen_s=gen_s, fit_s=fit_s, size=len(tflite_bytes),
        acc=acc, n=len(y), n_train=len(i_train), n_val=len(i_val), n_test=len(i_test),
        summary=summary, params=params, h=h, epochs_run=epochs_run, best_epoch=best_epoch, cm=cm,
        prec=prec, rec=rec, f1=f1, sup=sup, diff=diff, in_det=in_det, out_det=out_det,
        conv_path=conv_path, golden=golden, kotlin_block=kotlin_block, sub_stats=sub_stats,
    )
    print(f"Звіт: {REPORT_PATH}")


def write_report(**r) -> None:
    """Генерує ``ml/TRAINING.md`` українською з реальними числами цього запуску."""
    h = r["h"]
    cm = r["cm"]
    lines: list[str] = []
    w = lines.append

    w("# ЛР6: звіт про навчання моделі `vibration_classifier.tflite`\n")
    w("> Файл згенеровано скриптом `ml/train_model.py`. Не редагуйте вручну: при наступному запуску "
      "його буде перезаписано.\n")

    w("## 1. Підсумок\n")
    w("| Параметр | Значення |\n|---|---|")
    w(f"| Дата запуску | {r['now']} |")
    w(f"| Python | {platform.python_version()} |")
    w(f"| TensorFlow | {tf.__version__} (Keras {keras.__version__}) |")
    w(f"| NumPy / scikit-learn | {np.__version__} / {sklearn.__version__} |")
    w(f"| Seed | {SEED} (golden vectors: {GOLDEN_SEED}) |")
    w(f"| Генерація даних | {r['gen_s']:.1f} с |")
    w(f"| Навчання | {r['fit_s']:.1f} с, {r['epochs_run']} епох (найкраща — {r['best_epoch']}) |")
    w(f"| Параметрів моделі | {r['params']} |")
    w(f"| Розмір `.tflite` | {r['size']} байт ({r['size'] / 1024:.1f} КБ) |")
    w(f"| **Test accuracy (TFLite)** | **{r['acc']:.4f}** |")
    w(f"| max \\|Keras − TFLite\\| | {r['diff']:.2e} |")
    w(f"| Шлях конвертації | `{r['conv_path']}` |\n")

    w("## 2. Задача і класи\n")
    w("Модель отримує 2 с динамічного прискорення `|a − g|` і відповідає, що відбувається з поверхнею.\n")
    w("| Клас | Індекс | Опис | Приклади підтипів | Реакція системи |\n|---|---|---|---|---|")
    w("| `CALM` — «Спокій» | 0 | нічого не відбувається | шум сенсора, фонова вібрація (холодильник, кроки) "
      "| нічого |")
    w("| `TOUCH` — «Випадковий дотик» | 1 | одиночна подія | один удар, один ривок пристрою, два слабкі дотики "
      "| швидкий запис у БД (0,2 с) |")
    w("| `BREAK_IN` — «Спроба злому» | 2 | повторювана або тривала сильна дія | серія ударів, смикання "
      "туди-назад, свердління | швидкий запис + правило R4 → сирена (`Action.ALARM`) |\n")

    w("## 3. Дані\n")
    w("Сигнал генерується так само, як він виникає в застосунку: 3 осі прискорення на 50 Гц (з гравітацією) "
      "→ той самий фільтр нижніх частот, що в `IntrusionDetector` (`alpha = dt/(tau+dt)`, `tau = 0,5 с`, "
      "ініціалізація першим семплом) → `|a − g|` → препроцесинг (розділ 4). Кожна симуляція триває 5 с: "
      "3 с «розгону», щоб фільтр стабілізувався, і 2 с вікна, яке бачить модель.\n")
    w("| Клас | Підтип | Параметри |\n|---|---|---|")
    w("| CALM | `noise` | лише шум сенсора 0,01–0,08 м/с² по кожній осі |")
    w("| CALM | `background` | + синус 8–25 Гц, амплітуда 0,02–0,15 м/с² |")
    w("| TOUCH | `impulse` | рівно один удар 0,8–5 м/с², «дзвін» 10–25 Гц із загасанням 30–120 мс |")
    w("| TOUCH | `hump` | один плавний поворот пристрою (перехід або туди-назад) за 0,15–0,5 с, пік `\\|a−g\\|` 0,5–3 м/с² |")
    w("| TOUCH | `two_weak` | два удари 0,5–1,5 м/с² з інтервалом 0,2–1,5 с |")
    w("| BREAK_IN | `knocks` | 3–6 ударів 2–10 м/с² з інтервалами 0,15–0,6 с, ≥ 3 у вікні |")
    w("| BREAK_IN | `humps` | 3–5 поворотів підряд зі зміною напрямку, кожен 0,15–0,4 с, пік 0,8–5 м/с², ≥ 3 у вікні |")
    w("| BREAK_IN | `drill` | синус 15–60 Гц, RMS 0,8–3 м/с², 1,2–2,5 с (≥ 1,2 с у вікні), іноді друга гармоніка |\n")
    w("**Аугментація.** Випадкове положення подій у вікні (зсув у часі), масштаб амплітуди корисного "
      "сигналу ×0,8–1,2, випадковий рівень шуму, випадкова орієнтація пристрою й напрям ударів.\n")
    w(f"**Обсяг.** {PER_CLASS} вікон на клас, разом {r['n']}. Стратифікована розбивка 70 / 15 / 15: "
      f"train = {r['n_train']}, val = {r['n_val']}, test = {r['n_test']}.\n")
    w("**Domain gap і «горби».** В емуляторі Android немає справжніх ударів: панель Virtual Sensors → "
      "Device Pose лише плавно змінює положення пристрою. Фільтр гравітації запізнюється (`tau = 0,5 с`), "
      "тож кожен рух повзунка дає в `|a − g|` плавний «горб», а не гострий пік. Модель, навчена лише на "
      "імпульсах, на демонстрації в емуляторі вгадувала б погано. Тому «горби» є окремими підтипами: "
      "один — `TOUCH`, серія — `BREAK_IN`. Вони моделюються саме як поворот вектора гравітації, що проходить "
      "через той самий фільтр, а не як штучна форма, домальована до `|a − g|`.\n")

    w("## 4. Препроцесинг\n")
    w("Однаковий у Python (`to_model_input`) і Kotlin (`ActivityClassifier.toModelInput`):\n")
    w(f"1. Взяти останні `RAW_WINDOW = {RAW_WINDOW}` значень `|a − g|` (2 с на {RAW_RATE_HZ} Гц) у хронологічному порядку.")
    w(f"2. Децимація ×{DECIMATION} через максимум пари: `out[i] = max(raw[2i], raw[2i+1])` → {MODEL_WINDOW} відліків на 25 Гц.")
    w(f"3. Нормалізація: `v = min(v, {SCALE}) / {SCALE}` → [0, 1].")
    w(f"4. Форма `[1, {MODEL_WINDOW}, 1]`, `float32`.\n")
    w("**Чому максимум, а не середнє.** Удар часто займає один семпл. Середнє з сусіднім «тихим» семплом "
      "удвічі зменшило б пік, і сильний удар став би схожим на слабкий дотик. Максимум зберігає амплітуду піку, "
      "а для плавних сигналів (горби, вібрація) майже не відрізняється від середнього.\n")
    w(f"Прогноз у застосунку робиться кожні `HOP = {HOP}` сирих семплів (0,5 с), тож сусідні вікна "
      "перекриваються на 75%.\n")

    w("## 5. Архітектура\n")
    w("```\n" + r["summary"].rstrip() + "\n```\n")
    w("| Шар | Вихід | Що робить |\n|---|---|---|")
    w("| `Conv1D(8, 5, relu)` | 50 × 8 | 8 фільтрів-«трафаретів» по 5 відліків (0,2 с) шукають локальний патерн "
      "(пік, загасання, схил) будь-де у вікні |")
    w("| `MaxPooling1D(2)` | 25 × 8 | залишає найсильнішу реакцію з кожної пари, довжина вдвічі менша |")
    w("| `Conv1D(16, 5, relu)` | 25 × 16 | ширший контекст (≈0,4 с): відрізняє «один пік», «кілька піків» і "
      "безперервну вібрацію |")
    w("| `GlobalMaxPooling1D` | 16 | для кожного фільтра одне число: «чи був патерн хоч десь» |")
    w("| `Dense(3, softmax)` | 3 | 16 ознак → 3 ймовірності, сума = 1 |\n")
    w("CNN, а не MLP: згортка застосовує ті самі ваги в кожній позиції, тож подія розпізнається незалежно від "
      "того, де саме у вікні вона сталася. MLP прив'язується до позиції й потребувала б значно більше даних.\n")

    w("## 6. Навчання\n")
    w(f"Adam (lr = 1e-2), `sparse_categorical_crossentropy`, batch 64, до 50 епох, "
      f"`EarlyStopping(patience=5, restore_best_weights=True)` за `val_loss`. Виконано {r['epochs_run']} епох, "
      f"ваги взято з епохи {r['best_epoch']}.\n")
    w("| Епоха | loss | accuracy | val_loss | val_accuracy |\n|---|---|---|---|---|")
    for i in range(r["epochs_run"]):
        mark = " ←" if i + 1 == r["best_epoch"] else ""
        w(f"| {i + 1}{mark} | {h['loss'][i]:.4f} | {h['accuracy'][i]:.4f} | {h['val_loss'][i]:.4f} | "
          f"{h['val_accuracy'][i]:.4f} |")
    w("\nASCII-графік `val_loss` (`#`) і `loss` (`.`) по епохах:\n")
    w("```")
    vmax = max(max(h["loss"]), max(h["val_loss"]))
    for i in range(r["epochs_run"]):
        w(f"{i + 1:>3} val {h['val_loss'][i]:.3f} {ascii_bar(h['val_loss'][i], vmax)}")
        w(f"    trn {h['loss'][i]:.3f} {ascii_bar(h['loss'][i], vmax).replace('#', '.')}")
    w("```\n")

    w("## 7. Результати (test-set, через TFLite-модель)\n")
    w(f"Accuracy: **{r['acc']:.4f}** на {r['n_test']} вікнах.\n")
    w("Confusion matrix (рядки — справжній клас, стовпці — прогноз):\n")
    w("| | " + " | ".join(CLASSES) + " |\n|---|" + "---|" * len(CLASSES))
    for i, c in enumerate(CLASSES):
        w(f"| **{c}** | " + " | ".join(str(v) for v in cm[i]) + " |")
    w("\n| Клас | Precision | Recall | F1 | Вікон |\n|---|---|---|---|---|")
    for i, c in enumerate(CLASSES):
        w(f"| {c} | {r['prec'][i]:.4f} | {r['rec'][i]:.4f} | {r['f1'][i]:.4f} | {r['sup'][i]} |")
    w("\nТочність по підтипах:\n")
    w("| Підтип | Вікон | Accuracy |\n|---|---|---|")
    for s, n, a in r["sub_stats"]:
        w(f"| `{s}` | {n} | {a:.4f} |")
    w(f"\n**Keras vs TFLite.** На 100 вікнах test-set максимальна різниця ймовірностей — {r['diff']:.2e} "
      "(поріг 1e-5). Конвертація не змінила поведінку моделі.\n")

    w("## 8. Тензори моделі\n")
    w("| Тензор | name | shape | dtype | quantization |\n|---|---|---|---|---|")
    for title, d in (("Вхід", r["in_det"]), ("Вихід", r["out_det"])):
        w(f"| {title} | `{d['name']}` | `{d['shape'].tolist()}` | `{d['dtype'].__name__}` | "
          f"`{d['quantization']}` (немає) |")
    w("\nВхід `[1, 50, 1]`: 1 — batch (одне вікно за раз), 50 — час (відліки на 25 Гц = 2 с), "
      "1 — канал (одна величина `|a − g|`; для 3 осей окремо було б 3).\n")
    w("Вихід `[1, 3]`: 1 — batch, 3 — ймовірності класів у порядку `CALM, TOUCH, BREAK_IN` (softmax, сума ≈ 1).\n")

    w("## 9. Golden vectors\n")
    w(f"Три фіксовані вікна (seed {GOLDEN_SEED}). Сирі 100 значень `|a − g|` і очікувані ймовірності TFLite "
      "вставлено в `ActivityClassifier.GOLDEN` (Kotlin). `selfTest()` у debug-збірці проганяє їх через "
      "`toModelInput` + `Interpreter` і порівнює з допуском 1e-4: так перевіряється і препроцесинг, і модель.\n")
    for cls, sub, raw, mi, p in r["golden"]:
        w(f"**{cls}** (`{sub}`): очікуваний вихід `[{', '.join(f'{v:.6f}' for v in p)}]`\n")
        w("Вхід моделі (50 чисел після препроцесингу):\n")
        w("```\n" + ", ".join(f"{v:.6f}" for v in mi) + "\n```\n")
    w("Kotlin-літерали (сирі 100 значень + очікуваний вихід):\n")
    w("```kotlin\n" + r["kotlin_block"] + "\n```\n")

    w("## 10. Обмеження\n")
    w("- Дані повністю синтетичні. Train і test згенеровано тим самим генератором, тож accuracy вище "
      "оцінює, наскільки модель вивчила **генератор**, а не реальний світ. Вона оптимістична.")
    w("- Реальна якість на фізичному пристрої невідома. Для її оцінки потрібен записаний і розмічений датасет "
      "з різних поверхонь і пристроїв (інший шум, частота, резонанс корпусу).")
    w("- Межі класів умовні: «два слабкі дотики» vs «серія ударів» визначено діапазонами генератора, "
      "а не реальною статистикою зломів.")
    w("- Пороги підтвердження в застосунку (BREAK_IN ≥ 0,8 у двох прогнозах поспіль, швидкий запис "
      "при ≥ 0,6, утримання 5 с) підібрані під демонстрацію в емуляторі.")
    w("- Вікно на межі події (подія частково у вікні) дає проміжні прогнози, наприклад `TOUCH` на початку "
      "серії ударів. Саме тому аномалія підтверджується двома прогнозами поспіль.")

    REPORT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if sys.version_info >= (3, 13):
        sys.exit("TensorFlow 2.17 не підтримує Python 3.13+, створіть venv на 3.9–3.12")
    os.chdir(ROOT)
    main()
