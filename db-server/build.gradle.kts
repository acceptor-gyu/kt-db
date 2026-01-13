plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.25"
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    application
}

application {
    mainClass.set("study.db.server.DbServerApplicationKt")
}

tasks.register<JavaExec>("runInitElasticsearch") {
    group = "elasticsearch"
    description = "Initialize Elasticsearch index for query logs"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("study.db.server.elasticsearch.InitElasticsearchIndexApp")
    args = project.findProperty("args")?.toString()?.split(" ") ?: emptyList()
}

tasks.register<JavaExec>("runQueryLogExample") {
    group = "elasticsearch"
    description = "Run QueryLogService example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("study.db.server.elasticsearch.example.QueryLogExampleApp")
}

dependencies {
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
