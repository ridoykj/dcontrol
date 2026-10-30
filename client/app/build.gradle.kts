plugins {
    alias(libs.plugins.dcontrol.android.application)
    alias(libs.plugins.dcontrol.android.compose)
    alias(libs.plugins.dcontrol.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dcontrol.client"

    defaultConfig {
        applicationId = "com.dcontrol.client"
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:permissions"))
    implementation(project(":core:network"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:connection"))
    implementation(project(":feature:files"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:clipboard"))
    implementation(project(":feature:deviceinfo"))
    implementation(project(":feature:contacts"))
    implementation(project(":feature:calllog"))
    implementation(project(":feature:sms"))
    implementation(project(":feature:remotecontrol"))
    implementation(project(":feature:screen"))
    implementation(project(":feature:findphone"))
    implementation(project(":feature:camera"))
    implementation(project(":feature:mic"))
    implementation(project(":core:accessibility"))
    implementation(project(":core:telephony"))
    implementation(project(":core:storage"))
    implementation(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
