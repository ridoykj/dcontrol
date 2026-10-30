plugins {
    alias(libs.plugins.dcontrol.android.library)
    alias(libs.plugins.dcontrol.android.hilt)
}

android {
    namespace = "com.dcontrol.client.core.database"
}

dependencies {
    api(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
