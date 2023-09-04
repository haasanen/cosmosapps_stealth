plugins {
    kotlin("jvm") version Dependencies.Versions.kotlin apply false
    kotlin("plugin.serialization") version Dependencies.Versions.kotlin apply false
    id("com.android.application") version Dependencies.Versions.androidGradlePlugin apply false
    id("com.google.devtools.ksp") version Dependencies.Versions.ksp apply false
    id("com.google.dagger.hilt.android") version Dependencies.Versions.hiltGradlePlugin apply false
    id("androidx.navigation.safeargs.kotlin") version Dependencies.Versions.navigation apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
