import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.gatotiles.tools.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g", "-Djava.awt.headless=true")
}

dependencies {
    implementation(project(":analyzer"))
    implementation(libs.kotlinx.serialization.json)
}
