plugins {
    alias(libs.plugins.dcontrol.android.library)
    alias(libs.plugins.dcontrol.android.hilt)
}

android {
    namespace = "com.dcontrol.client.core.rtc"
}

dependencies {
    api(project(":core:websocket"))
    api(libs.webrtc.android)
    implementation(libs.kotlinx.coroutines.android)
}
