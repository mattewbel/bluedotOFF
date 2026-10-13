import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseSecrets = Properties().apply {
    val secretFile = rootProject.file("../.private/bluedotOFF-release.properties")
    if(secretFile.isFile)secretFile.inputStream().use { load(it) }
}
android {
    namespace = "app.bluedotoff"
    compileSdk = 37
    compileSdkMinor = 0
    ndkVersion = "27.2.12479018"
    defaultConfig { applicationId = "app.bluedotoff"; minSdk = 30; targetSdk = 37; versionCode = 30; versionName = "1.0.3" }
    flavorDimensions += "transport"
    productFlavors {
        create("local") { dimension = "transport" }
    }
    defaultConfig {
        buildConfigField("boolean", "LOCAL_WRITES_ENABLED", "true")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"; cppFlags += "-std=c++17"; targets += "bluedot_adb" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2048m"; it.forkEvery=1; it.systemProperty("screenshotDir", rootProject.layout.projectDirectory.dir("artifacts/ui-preview").asFile.absolutePath) }
    }
    signingConfigs {
        if(releaseSecrets.isNotEmpty())create("localRelease") {
            storeFile = file(releaseSecrets.getProperty("storeFile"))
            storePassword = releaseSecrets.getProperty("storePassword")
            keyAlias = releaseSecrets.getProperty("keyAlias")
            keyPassword = releaseSecrets.getProperty("keyPassword")
        }
    }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); isDebuggable = false; signingConfig = signingConfigs.findByName("localRelease") } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
