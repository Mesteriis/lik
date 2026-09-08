import java.util.Properties
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.Component

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
val generatedUSearch = layout.buildDirectory.dir("generated/usearch")

val fetchUSearch by tasks.registering(Exec::class) {
    description = "Fetch pinned official USearch Android arm64 runtime into build output."
    workingDir(rootProject.projectDir)
    outputs.file(generatedUSearch.map { it.file("src/receipt.txt") })
    commandLine(modelPython.get(), "scripts/usearch/fetch.py", "--output", generatedUSearch.get().asFile.absolutePath)
}

val stageModelMetadata by tasks.registering(Exec::class) {
    description = "Stage preset manifests/licenses only. External model caches never enter APK assets."
    workingDir(rootProject.projectDir)
    outputs.dir(generatedModelAssets)
    outputs.upToDateWhen { false }
    commandLine(modelPython.get(), "scripts/models/artifacts.py", "stage",
        "--output", generatedModelAssets.get().asFile.absolutePath)
}

tasks.named("preBuild") { dependsOn(stageModelMetadata, fetchUSearch) }

val verifyDistributionApks by tasks.registering {
    group = "verification"
    description = "Inspect every configured app and instrumentation APK; reject model payloads anywhere."
}

tasks.register("assembleDistribution") {
    group = "build"
    description = "Build and inspect metadata-only app and instrumentation APKs for every variant."
    dependsOn(verifyDistributionApks)
}

fun registerApkPayloadCheck(component: Component, kind: String) {
    val apkDirectory = component.artifacts.get(SingleArtifact.APK)
    val buildToolsDirectory = androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/36.0.0") }
    val taskSuffix = component.name.replaceFirstChar { it.uppercaseChar() }
    val checkPayloads = tasks.register<Exec>(component.computeTaskName("verify", "ModelPayloads")) {
        group = "verification"
        description = "Reject model/tokenizer bytes anywhere in ${component.name} APKs."
        // The AGP artifact provider carries the package-task dependency and the
        // actual variant output path. No Debug/Release or single-output assumption.
        inputs.dir(apkDirectory)
        workingDir(rootProject.projectDir)
        commandLine(modelPython.get(), "scripts/models/inspect_apk.py",
            "--directory", apkDirectory.get().asFile.absolutePath, "--kind", kind,
            "--variant", component.name, "--build-tools", buildToolsDirectory.get().asFile.absolutePath)
    }
    tasks.matching { it.name == "assemble$taskSuffix" }
        .configureEach { dependsOn(checkPayloads) }
    // Direct package invocations must also run the gate, including cached APKs.
    tasks.matching { it.name == "package$taskSuffix" }
        .configureEach { finalizedBy(checkPayloads) }
    component.lifecycleTasks.registerPreInstallation(checkPayloads)
    verifyDistributionApks.configure { dependsOn(checkPayloads) }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        registerApkPayloadCheck(variant, "app")
        variant.deviceTests.values.forEach { test -> registerApkPayloadCheck(test, "android-test") }
    }
}

android {
    namespace = "io.github.mesteriis.lik"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.mesteriis.lik"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

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
        // Lik ships only to the arm64 Galaxy Fold/API 36+ target family.
        disable += "ChromeOsAbiSupport"
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
    implementation(libs.gson)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.testing)
}
