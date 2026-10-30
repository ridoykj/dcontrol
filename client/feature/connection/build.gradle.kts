plugins {
    alias(libs.plugins.dcontrol.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dcontrol.client.feature.connection"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.service)
}
