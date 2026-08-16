import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val versionPropertiesFile = file("version.properties")

fun taskNameTriggersApkVersionBump(rawTaskName: String): Boolean {
    val task = rawTaskName.substringAfterLast(':')
    if (task.equals("assemble", ignoreCase = true)) return true
    if (task.startsWith("assemble", ignoreCase = true) &&
        (task.endsWith("Debug", ignoreCase = true) || task.endsWith("Release", ignoreCase = true)) &&
        !task.contains("Test", ignoreCase = true)
    ) {
        return true
    }
    if (task.equals("packageDebug", ignoreCase = true) ||
        task.equals("packageRelease", ignoreCase = true)
    ) {
        return true
    }
    return false
}

fun readAppVersion(file: java.io.File): Pair<Int, String> {
    if (!file.exists()) {
        return 1 to "1.0.0"
    }
    val props = Properties()
    file.reader(Charsets.UTF_8).use { props.load(it) }
    val code = props.getProperty("VERSION_CODE")?.toIntOrNull() ?: 1
    val name = props.getProperty("VERSION_NAME") ?: "1.0.$code"
    return code to name
}

fun persistAppVersion(file: java.io.File, code: Int, name: String) {
    file.writeText(
        """
        |# Auto-bumped when packaging an APK (assemble/package). Dirty tree is expected; do not auto-commit.
        |VERSION_CODE=$code
        |VERSION_NAME=$name
        |
        """.trimMargin(),
    )
}

// Bump only when packaging an APK — not on Gradle sync or compile-only tasks.
if (gradle.startParameter.taskNames.any(::taskNameTriggersApkVersionBump)) {
    val existed = versionPropertiesFile.exists()
    val currentCode = readAppVersion(versionPropertiesFile).first
    val nextCode = if (!existed) 2 else maxOf(currentCode + 1, 2)
    persistAppVersion(versionPropertiesFile, nextCode, "1.0.$nextCode")
}

val (appVersionCode, appVersionName) = readAppVersion(versionPropertiesFile)

android {
    namespace = "dev.holgerendt.hanative"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.holgerendt.hanative"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
