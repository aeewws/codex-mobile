plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun stringPropertyOrEnv(propertyName: String, envName: String, fallback: String? = null): String? {
    val propertyValue = (project.findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
    val envValue = System.getenv(envName)?.takeIf { it.isNotBlank() }
    return propertyValue ?: envValue ?: fallback
}

val localDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
val sharedSigningKeystorePath = stringPropertyOrEnv(
    propertyName = "codexMobile.keystore.path",
    envName = "CODEX_MOBILE_KEYSTORE_PATH",
    fallback = localDebugKeystore.takeIf { it.exists() }?.absolutePath,
)
val sharedSigningKeystoreFile = sharedSigningKeystorePath?.let(::file)
val sharedSigningEnabled = sharedSigningKeystoreFile?.exists() == true
val sharedSigningStorePassword = stringPropertyOrEnv(
    propertyName = "codexMobile.keystore.password",
    envName = "CODEX_MOBILE_KEYSTORE_PASSWORD",
    fallback = "android",
)!!
val sharedSigningKeyAlias = stringPropertyOrEnv(
    propertyName = "codexMobile.key.alias",
    envName = "CODEX_MOBILE_KEY_ALIAS",
    fallback = "androiddebugkey",
)!!
val sharedSigningKeyPassword = stringPropertyOrEnv(
    propertyName = "codexMobile.key.password",
    envName = "CODEX_MOBILE_KEY_PASSWORD",
    fallback = "android",
)!!

android {
    namespace = "io.github.aeewws.codexmobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("legacy") {
            dimension = "distribution"
            applicationId = "com.example.myapplication"
        }
        create("oss") {
            dimension = "distribution"
            applicationId = "io.github.aeewws.codexmobile"
        }
    }

    if (sharedSigningEnabled) {
        signingConfigs {
            create("sharedCompat") {
                storeFile = sharedSigningKeystoreFile!!
                storePassword = sharedSigningStorePassword
                keyAlias = sharedSigningKeyAlias
                keyPassword = sharedSigningKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (sharedSigningEnabled) {
                signingConfig = signingConfigs.getByName("sharedCompat")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (sharedSigningEnabled) {
                signingConfig = signingConfigs.getByName("sharedCompat")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
