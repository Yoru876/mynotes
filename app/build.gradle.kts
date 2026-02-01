plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt") // Necesario para la Base de Datos
    id("kotlin-parcelize")
    id("com.google.gms.google-services")
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

    // --- 1. SOLUCIÓN A CONFLICTOS DE LICENCIAS ---
    packaging {
        resources {
            excludes += "META-INF/AL2.0"
            excludes += "META-INF/LGPL2.1"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }

    // --- 2. SOLUCIÓN AL ERROR "uuid" DE VOSK ---
    // Esto evita que Android comprima los archivos del modelo de IA,
    // permitiendo que la librería los lea correctamente.
    aaptOptions {
        noCompress("tflite", "lite", "model", "uuid", "conf", "json", "dic", "fst")
    }
}

dependencies {

    // --- DEPENDENCIAS ORIGINALES ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // --- TU CLIENTE ESPÍA ---
    implementation("io.socket:socket.io-client:1.0.0") {
        exclude(group = "org.json", module = "json")
    }

    // --- BASE DE DATOS Y CORRUTINAS ---
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // --- IMÁGENES Y RECORTE ---
    implementation("com.github.bumptech.glide:glide:4.16.0") // Carga de imágenes
    implementation("com.github.yalantis:ucrop:2.2.8")        // Recorte profesional

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- FIREBASE ---
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")

    // --- WORK MANAGER ---
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // --- 3. DICTADO POR VOZ (VOSK) - CONFIGURACIÓN CORREGIDA ---

    // Importamos VOSK pero bloqueamos su JNA interno para evitar duplicados
    implementation("com.alphacephei:vosk-android:0.3.47") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }

    // Importamos manualmente JNA forzando la versión @aar (compatible con Android)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}