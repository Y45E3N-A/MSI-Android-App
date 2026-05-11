buildscript {
    repositories {
        google()
        mavenCentral()

    }
    dependencies {
        // Usually empty if using pluginManagement in settings.gradle.kts
    }
}

val externalBuildRoot = run {
    val baseDir = System.getenv("LOCALAPPDATA")
        ?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.io.tmpdir")
    file("$baseDir/MSIAndroidApp-gradle-build")
}

allprojects {
    val buildLeaf = project.path
        .trim(':')
        .replace(':', '_')
        .ifBlank { "root" }
    layout.buildDirectory.set(externalBuildRoot.resolve(buildLeaf))
}

tasks.register("clean", Delete::class) {
    delete(externalBuildRoot)
}
