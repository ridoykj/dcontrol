plugins {
    alias(libs.plugins.dcontrol.android.library)
    alias(libs.plugins.dcontrol.android.compose)
}

android {
    namespace = "com.dcontrol.client.core.permissions"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
