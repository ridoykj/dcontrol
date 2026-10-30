plugins {
    alias(libs.plugins.dcontrol.android.feature)
}

android {
    namespace = "com.dcontrol.client.feature.camera"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:telephony"))
    implementation(project(":core:rtc"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.webrtc.android)

    testImplementation(libs.okhttp.mockwebserver)
}
