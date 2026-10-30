plugins {
    alias(libs.plugins.dcontrol.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dcontrol.client.feature.pairing"
}

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
}
