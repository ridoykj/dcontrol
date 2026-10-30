import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("dcontrol-android-library"))
        pluginManager.apply(libs.pluginId("dcontrol-android-compose"))
        pluginManager.apply(libs.pluginId("dcontrol-android-hilt"))
        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:permissions"))
            add("implementation", project(":core:network"))
            add("implementation", libs.lib("androidx-core-ktx"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("testImplementation", libs.lib("turbine"))
        }
    }
}
