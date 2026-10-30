plugins {
    alias(libs.plugins.dcontrol.android.feature)
}

android {
    namespace = "com.dcontrol.client.feature.findphone"
}

dependencies {
    implementation(project(":core:websocket"))
    implementation(project(":core:accessibility"))
    implementation(project(":core:telephony"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
