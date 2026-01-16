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

tasks.register<JavaExec>("runInitSampleData") {
    group = "explain"
    description = "Initialize sample data for EXPLAIN testing"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("study.db.server.elasticsearch.example.InitSampleDataForExplainApp")
}

tasks.register<JavaExec>("runExplainExample") {
    group = "explain"
    description = "Run EXPLAIN command examples"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("study.db.server.elasticsearch.example.ExplainExampleApp")
}

dependencies {
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Elasticsearch를 편하게 활용하기 위한 Spring Boot 관련 설정
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")

    // SQL 파서 (SQL을 파싱하고 자바 객체로 변환)
    implementation("com.github.jsqlparser:jsqlparser:4.7")
}
