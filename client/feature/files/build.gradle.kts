plugins {
    alias(libs.plugins.dcontrol.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dcontrol.client.feature.files"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:storage"))
    implementation(project(":core:database"))
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.okhttp.mockwebserver)
}
