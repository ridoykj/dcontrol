plugins {
    alias(libs.plugins.dcontrol.android.feature)
}

android {
    namespace = "com.dcontrol.client.feature.calllog"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:telephony"))
    implementation(libs.androidx.activity.compose)
}
