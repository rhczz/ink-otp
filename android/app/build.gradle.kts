import java.util.Properties
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    jacoco
}

val localSigningFile = rootProject.file("local-signing.properties")
val localSigning = Properties().apply {
    if (localSigningFile.exists()) localSigningFile.inputStream().use { load(it) }
}

abstract class BundlePrivacyPolicy : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val policyHtml: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val policyStyles: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val policy = outputDirectory.dir("privacy").get().asFile
        policy.mkdirs()
        policyHtml.get().asFile.copyTo(policy.resolve("index.html"), overwrite = true)
        policyStyles.get().asFile.copyTo(policy.resolve("site.css"), overwrite = true)
    }
}

val bundlePrivacyPolicy = tasks.register<BundlePrivacyPolicy>("bundlePrivacyPolicy") {
    policyHtml.set(rootProject.layout.projectDirectory.file("../website/privacy/index.html"))
    policyStyles.set(rootProject.layout.projectDirectory.file("../website/assets/site.css"))
    outputDirectory.set(layout.buildDirectory.dir("generated/privacy/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundlePrivacyPolicy, BundlePrivacyPolicy::outputDirectory)
    }
}

android {
    namespace = "io.github.rhczz.inkotp"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.rhczz.inkotp"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.2"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (localSigningFile.exists()) {
            create("localBeta") {
                storeFile = rootProject.file(localSigning.getProperty("storeFile"))
                storePassword = localSigning.getProperty("storePassword")
                keyAlias = localSigning.getProperty("keyAlias")
                keyPassword = localSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (localSigningFile.exists()) signingConfig = signingConfigs.getByName("localBeta")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--enable-native-access=ALL-UNNAMED",
                )
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        includes = listOf("io.github.rhczz.inkotp.*")
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoDebugReport") {
    dependsOn("testDebugUnitTest")
    executionData(fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec", "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
    classDirectories.setFrom(files(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*")
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*")
        },
    ))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JavaExec>("benchmarkProjection") {
    dependsOn("compileDebugUnitTestKotlin")
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    classpath = files(unitTests.map { it.classpath }, unitTests.map { it.testClassesDirs })
    mainClass.set("io.github.rhczz.inkotp.perf.OtpProjectionBenchmark")
    args(layout.buildDirectory.file("reports/performance/otp-projection.json").get().asFile.absolutePath)
    workingDir(rootProject.projectDir)
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
}
