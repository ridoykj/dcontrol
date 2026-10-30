plugins {
    alias(libs.plugins.dcontrol.android.feature)
}

android {
    namespace = "com.dcontrol.client.feature.screen"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:accessibility"))
    implementation(project(":core:telephony"))
    implementation(project(":core:rtc"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.webrtc.android)
}
