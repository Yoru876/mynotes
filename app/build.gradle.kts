plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt") // <--- NUEVO: Necesario para procesar la Base de Datos
}

android {
    namespace = "cl.example.mynotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.example.mynotes"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    // Dependencias originales
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Tu cliente Socket.IO (Espía)
    implementation("io.socket:socket.io-client:1.0.0") {
        exclude(group = "org.json", module = "json")
    }

    // --- NUEVAS DEPENDENCIAS (Base de Datos y Listas) ---
    val room_version = "2.6.1"

    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version") // Soporte para Kotlin Coroutines
    kapt("androidx.room:room-compiler:$room_version")      // Procesador de anotaciones

    // Corrutinas (Para operaciones asíncronas en base de datos)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}