import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class GenerateBuildInfo : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDir.get().asFile
        directory.mkdirs()
        directory.resolve("build-info.txt").writeText(System.currentTimeMillis().toString())
    }
}

android {
    namespace = "app.goultra.transfer"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.goultra.transfer"
        minSdk = 29 // WifiNetworkSpecifier, MediaStore without storage permission
        targetSdk = 35
        versionCode = 13
        versionName = "0.13"
    }

    buildTypes {
        release {
            // R8 drops the unused icons of material-icons-extended (otherwise +20 MB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
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
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GenerateBuildInfo>("generate${variant.name.replaceFirstChar { it.uppercase() }}BuildInfo") {
            outputDir.set(layout.buildDirectory.dir("generated/build-info/${variant.name}"))
            outputs.upToDateWhen { false }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateBuildInfo::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // In-app preview of files still on the camera (the system player cannot reach the camera's Wi-Fi).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Colour over I-Log videos in the viewer: a LUT, brightness, saturation (GL effects in the player).
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
