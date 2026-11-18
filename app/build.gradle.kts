import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // ЛР5: KSP (Kotlin Symbol Processing) запускає Room compiler. kapt генерує Java-заглушки
    // (stubs) для Kotlin-коду і через них запускає Java annotation processor, а KSP читає
    // Kotlin-код напряму. Тому KSP до ~2 разів швидший, а kapt перебуває в maintenance mode.
    alias(libs.plugins.ksp)
    // ЛР5: Room Gradle plugin — налаштовує експорт схеми БД (див. блок room нижче)
    alias(libs.plugins.room)
}

// ЛР3: адреса Firebase RTDB з local.properties (не потрапляє в git)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val firebaseDbUrl: String = localProps.getProperty("FIREBASE_DB_URL", "")

android {
    namespace = "com.example.intrusiondetector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.intrusiondetector"
        minSdk = 31 // Android 12
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_DB_URL", "\"$firebaseDbUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // android.util.Log у JVM-тестах повертає 0 замість винятку
        unitTests.isReturnDefaultValues = true
    }
}

// ЛР5: експорт схеми БД у JSON (app/schemas/<клас БД>/<version>.json). Файл комітиться в git:
// так видно історію змін схеми, а Room може перевіряти й тестувати міграції між версіями.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    // ЛР5: Room — runtime і корутинні розширення (suspend DAO, Flow)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // ЛР5: генератор реалізації DAO і Database (SensorDao_Impl, AppDatabase_Impl), працює під час збірки
    ksp(libs.androidx.room.compiler)
    // ЛР5: графік на вкладці «Аналітика»
    implementation(libs.vico.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
}
