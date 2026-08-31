plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFile = System.getenv("NV_SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("NV_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("NV_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("NV_SIGNING_KEY_PASSWORD")
val ciVersionCode = System.getenv("NV_VERSION_CODE")?.toIntOrNull()
val ciVersionName = System.getenv("NV_VERSION_NAME")

val hasReleaseSigning =
    !signingStoreFile.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.nv.dronemapping"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nv.dronemapping"
        minSdk = 24
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("nvRelease") {
            if (hasReleaseSigning) {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("nvRelease")
            }
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
}
