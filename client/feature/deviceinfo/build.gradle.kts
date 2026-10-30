plugins {
    alias(libs.plugins.dcontrol.android.library)
    alias(libs.plugins.dcontrol.android.hilt)
}

android {
    namespace = "com.dcontrol.client.feature.deviceinfo"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:storage"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
