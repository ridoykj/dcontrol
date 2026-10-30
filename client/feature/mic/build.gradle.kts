plugins {
    alias(libs.plugins.dcontrol.android.feature)
}

android {
    namespace = "com.dcontrol.client.feature.mic"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:telephony"))
    implementation(project(":core:rtc"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.webrtc.android)

    testImplementation(libs.okhttp.mockwebserver)
}
