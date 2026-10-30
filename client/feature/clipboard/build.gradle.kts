plugins {
    alias(libs.plugins.dcontrol.android.feature)
}

android {
    namespace = "com.dcontrol.client.feature.clipboard"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(libs.androidx.activity.compose)
}
