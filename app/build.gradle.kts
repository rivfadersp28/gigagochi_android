import java.io.File

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun String.asBuildConfigString(): String {
    require(none { it.code < 0x20 || it.code == 0x7f }) {
        "BuildConfig values must not contain control characters"
    }
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val configuredBackendBaseUrl = providers
    .gradleProperty("GIGAGOCHI_BACKEND_BASE_URL")
    .orElse(providers.environmentVariable("GIGAGOCHI_BACKEND_BASE_URL"))
    .getOrElse("https://gigagochi.serega.works/")
val releaseKeystorePath = providers.environmentVariable("GIGAGOCHI_ANDROID_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("GIGAGOCHI_ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("GIGAGOCHI_ANDROID_KEY_ALIAS")
    .getOrElse("gigagochi")
val releaseKeyPassword = providers.environmentVariable("GIGAGOCHI_ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.gigagochi.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.gigagochi.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 19
        versionName = "0.1.18"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            configuredBackendBaseUrl.asBuildConfigString(),
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").assets.srcDir(
            layout.buildDirectory.dir("generated/webAssets").get().asFile,
        )
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-database:1.10.1")
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    ksp("androidx.room:room-compiler:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val webDirectory = rootProject.layout.projectDirectory.dir("web")
val generatedWebAssets = layout.buildDirectory.dir("generated/webAssets/web")
val bridgeModelsFile = layout.projectDirectory.file(
    "src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt",
)
val webContractsFile = webDirectory.file("src/contracts.ts")
val webBundleBuildScript = rootProject.layout.projectDirectory.file(
    "scripts/build-web-bundle.sh",
)
val webDependencyInstallScript = rootProject.layout.projectDirectory.file(
    "scripts/install-web-dependencies.sh",
)
val webBundleManifestTool = rootProject.layout.projectDirectory.file(
    "scripts/web-bundle-manifest.mjs",
)
val typographyContractTool = rootProject.layout.projectDirectory.file(
    "scripts/check-typography-contract.mjs",
)
val bridgeCommandContractTool = rootProject.layout.projectDirectory.file(
    "scripts/check-bridge-command-contract.mjs",
)
val webTypographyFile = layout.projectDirectory.file(
    "src/main/java/com/gigagochi/app/core/webview/WebTypography.kt",
)
val wireContractSourceList = rootProject.layout.projectDirectory.file(
    "scripts/wire-contract-sources.txt",
)
val repositoryRootPath = rootProject.layout.projectDirectory.asFile.absolutePath
val wireContractSources = providers.fileContents(wireContractSourceList).asText.get()
    .lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { relativePath -> File(repositoryRootPath, relativePath) }
    .toList()
val webBundleVerificationScript = rootProject.layout.projectDirectory.file(
    "scripts/verify-web-bundle.sh",
)
val webBundleVerificationStamp = layout.buildDirectory.file(
    "verification/webBundleManifest.sha256",
)
val webDependencyLock = webDirectory.file("node_modules/.package-lock.json")
val webViteBinary = webDirectory.file("node_modules/.bin/vite")
val webTypescriptBinary = webDirectory.file("node_modules/.bin/tsc")

val installWebDependencies by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Installs the exact Web dependency graph from package-lock.json"
    workingDir(rootProject.layout.projectDirectory.asFile)
    commandLine(
        "bash",
        webDependencyInstallScript.asFile.absolutePath,
        webDirectory.asFile.absolutePath,
    )
    inputs.files(
        webDirectory.file("package.json"),
        webDirectory.file("package-lock.json"),
        webDependencyInstallScript,
    )
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.files(
        webDependencyLock,
        webViteBinary,
        webTypescriptBinary,
    )
}

val buildWebBundle by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds and hashes the validated local React bundle embedded in the Android APK"
    dependsOn(installWebDependencies)
    workingDir(rootProject.layout.projectDirectory.asFile)
    commandLine(
        "bash",
        webBundleBuildScript.asFile.absolutePath,
        generatedWebAssets.get().asFile.absolutePath,
    )
    inputs.files(
        fileTree(webDirectory) {
            include("index.html")
            include("package.json")
            include("package-lock.json")
            include("tsconfig.json")
            include("vite.config.ts")
            include("src/**")
            exclude("node_modules/**")
        },
        bridgeModelsFile,
        webContractsFile,
        webBundleBuildScript,
        webBundleManifestTool,
        bridgeCommandContractTool,
        typographyContractTool,
        webTypographyFile,
        wireContractSourceList,
        wireContractSources,
    )
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedWebAssets)
}

val validateWebBundleManifest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates bridge versions and every generated Web bundle hash"
    dependsOn(buildWebBundle)
    workingDir(rootProject.layout.projectDirectory.asFile)
    commandLine(
        "bash",
        webBundleVerificationScript.asFile.absolutePath,
        generatedWebAssets.get().asFile.absolutePath,
        webBundleVerificationStamp.get().asFile.absolutePath,
    )
    inputs.dir(generatedWebAssets)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        bridgeModelsFile,
        webContractsFile,
        webBundleManifestTool,
        webBundleVerificationScript,
        wireContractSourceList,
        wireContractSources,
    )
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(webBundleVerificationStamp)
}

tasks.named("preBuild").configure {
    dependsOn(validateWebBundleManifest)
}
