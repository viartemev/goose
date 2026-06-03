plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

ktlint {
    version.set("1.3.1")
    filter {
        exclude("**/*.kts")
        include("**/kotlin/**/*.kt")
    }
}
