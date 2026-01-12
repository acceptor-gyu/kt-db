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

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
