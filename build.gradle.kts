import java.util.Properties
plugins { alias(libs.plugins.android.application) }
val signing = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
    namespace = "com.os4.canvas"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.yzc26623.HyperCanvas"
        minSdk = 35
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3-beta"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes { release {
        isMinifyEnabled = false
        signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
    } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
