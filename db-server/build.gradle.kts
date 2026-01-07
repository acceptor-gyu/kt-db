plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
    application
}

application {
    mainClass.set("study.db.server.DbServerApplicationKt")
}

dependencies {
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
