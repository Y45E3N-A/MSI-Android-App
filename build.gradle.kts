buildscript {
    repositories {
        google()
        mavenCentral()

    }
    dependencies {
        // Usually empty if using pluginManagement in settings.gradle.kts
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
