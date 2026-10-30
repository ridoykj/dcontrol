plugins {
    alias(libs.plugins.dcontrol.android.feature)
}

android {
    namespace = "com.dcontrol.client.feature.remotecontrol"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:accessibility"))
}
