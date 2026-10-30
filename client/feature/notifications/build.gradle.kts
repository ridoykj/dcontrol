plugins {
    alias(libs.plugins.dcontrol.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dcontrol.client.feature.notifications"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:database"))
    implementation(libs.androidx.navigation.compose)

    testImplementation(libs.okhttp.mockwebserver)
}
