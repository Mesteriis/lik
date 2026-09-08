import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

val modelPython = providers.gradleProperty("likModelPython").orElse("python3")
val generatedModelAssets = layout.buildDirectory.dir("generated/modelAssets")

val stageModelMetadata by tasks.registering(Exec::class) {
    description = "Stage preset manifests/licenses only. External model caches never enter APK assets."
    workingDir(rootProject.projectDir)
    outputs.dir(generatedModelAssets)
    outputs.upToDateWhen { false }
    commandLine(modelPython.get(), "scripts/models/artifacts.py", "stage",
        "--output", generatedModelAssets.get().asFile.absolutePath)
}

tasks.named("preBuild") { dependsOn(stageModelMetadata) }

listOf("Debug", "Release").forEach { variant ->
    val checkPayloads = tasks.register<Exec>("verify${variant}ModelPayloads") {
        group = "verification"
        description = "Reject model/tokenizer payloads in the $variant APK."
        dependsOn("package$variant")
        workingDir(rootProject.projectDir)
        commandLine(modelPython.get(), "scripts/models/inspect_apk.py",
            "--directory", layout.buildDirectory.dir("outputs/apk/${variant.lowercase()}").get().asFile.absolutePath)
    }
    tasks.matching { it.name == "assemble$variant" }.configureEach { dependsOn(checkPayloads) }
}

val verifyDistributionApks by tasks.registering {
    group = "verification"
    description = "Reject model/tokenizer payloads in debug/release APKs, including oversized stale archives."
    dependsOn("assembleDebug", "assembleRelease")
}

tasks.register("assembleDistribution") {
    group = "build"
    description = "Build metadata-only debug/release APKs and verify no model payload is bundled."
    dependsOn(verifyDistributionApks)
}

android {
    namespace = "io.github.mesteriis.lik"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.mesteriis.lik"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A checkout without a private keystore can still build an unsigned release.
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("main").assets.srcDir(generatedModelAssets.get().asFile)

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Toolchain versions follow Rune Keyboard and are updated deliberately.
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.livedata)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.work)
    implementation(libs.onnxruntime.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.testing)
}
