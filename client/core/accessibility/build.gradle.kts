plugins {
    alias(libs.plugins.dcontrol.android.library)
    alias(libs.plugins.dcontrol.android.hilt)
}

android {
    namespace = "com.dcontrol.client.core.accessibility"
}

dependencies {
    api(project(":core:websocket"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
