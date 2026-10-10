plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.devtools.ksp") version "2.3.2" apply false
    kotlin("plugin.serialization") version "2.3.0"
    alias(libs.plugins.android.library) apply false
}
