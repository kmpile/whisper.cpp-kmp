plugins {
    // trick: keep the same plugin versions across all sub-modules
    alias(libs.plugins.androidLibrary).apply(false)
    alias(libs.plugins.kotlinMultiplatform).apply(false)
    alias(libs.plugins.androidKotlinMultiplatformLibrary).apply(false)
}
