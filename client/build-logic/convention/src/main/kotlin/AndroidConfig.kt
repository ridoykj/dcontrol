import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal object AndroidConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
    val JAVA_VERSION = JavaVersion.VERSION_17
    const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"

    /** Robolectric's FileDescriptor interceptor uses JDK internals that JDK 25 blocks without these. */
    val UNIT_TEST_JVM_ARGS = listOf(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
}

internal fun Project.configureApplication(extension: ApplicationExtension) {
    extension.apply {
        compileSdk = AndroidConfig.COMPILE_SDK
        defaultConfig {
            minSdk = AndroidConfig.MIN_SDK
            targetSdk = AndroidConfig.TARGET_SDK
            testInstrumentationRunner = AndroidConfig.TEST_RUNNER
        }
        compileOptions {
            sourceCompatibility = AndroidConfig.JAVA_VERSION
            targetCompatibility = AndroidConfig.JAVA_VERSION
            isCoreLibraryDesugaringEnabled = true
        }
        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.all { it.jvmArgs(AndroidConfig.UNIT_TEST_JVM_ARGS) }
        }
        packaging {
            resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
        }
    }
    addDesugaring()
}

internal fun Project.configureLibrary(extension: LibraryExtension) {
    extension.apply {
        compileSdk = AndroidConfig.COMPILE_SDK
        defaultConfig {
            minSdk = AndroidConfig.MIN_SDK
            testInstrumentationRunner = AndroidConfig.TEST_RUNNER
        }
        compileOptions {
            sourceCompatibility = AndroidConfig.JAVA_VERSION
            targetCompatibility = AndroidConfig.JAVA_VERSION
            isCoreLibraryDesugaringEnabled = true
        }
        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.all { it.jvmArgs(AndroidConfig.UNIT_TEST_JVM_ARGS) }
        }
        lint {
            targetSdk = AndroidConfig.TARGET_SDK
        }
    }
    addDesugaring()
}

private fun Project.addDesugaring() {
    dependencies {
        add("coreLibraryDesugaring", libs.lib("desugar-jdk-libs"))
    }
}
