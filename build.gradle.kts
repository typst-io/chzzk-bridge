import nu.studer.gradle.jooq.JooqEdition
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jooq.meta.jaxb.Logging.WARN
import org.jooq.meta.kotlin.*

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    jacoco
    // https://github.com/etiennestuder/gradle-jooq-plugin
    id("nu.studer.jooq") version "10.2"
}

group = "io.typst"
version = "1.1.0"

repositories {
    mavenCentral()
}

configurations {
    testImplementation {
        extendsFrom(compileClasspath.get(), runtimeClasspath.get())
    }
}

dependencies {
    implementation(libs.chzzk4j)
    // kotlin
    implementation(commons.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // ktor
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.collections.immutable)
    // logger
    implementation(libs.slf4j.simple)
    // jooq
    implementation(commons.jooq.core)
    implementation(commons.jooq.meta)
    implementation(libs.jooq.kotlin.core)
    implementation(libs.jooq.kotlin.coroutines)
    implementation(commons.flyway.core)
    implementation(libs.sqlite.jdbc)
    jooqGenerator(libs.sqlite.jdbc)
    implementation(commons.hikariCP)
    // junit
    testImplementation(enforcedPlatform(commons.junit.bom))
    testImplementation(commons.junit.jupiter)
    testImplementation(commons.mockito.core)
    testImplementation(commons.assertj.core)
    // test ktor
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.resources)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(commons.junit.platform.launcher)
    testImplementation(libs.gson)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "io.typst.chzzk.bridge.MainKt"
}

jooq {
    version = commons.versions.jooq.get()
    edition = JooqEdition.OSS
    configurations {
        create("main") {
            generateSchemaSourceOnCompilation = false
            jooqConfiguration {
                logging = WARN
                jdbc {
                    val dbFile = projectDir.resolve("chzzk_bridge.db").absolutePath
                    driver = "org.sqlite.JDBC"
                    url = "jdbc:sqlite:$dbFile"
                }
                generator {
                    name = "org.jooq.codegen.DefaultGenerator"

                    database {
                        name = "org.jooq.meta.sqlite.SQLiteDatabase"
                        includes = ".*"
                        excludes = ""
                    }
                    generate {
                        isDeprecated = false
                        isRecords = true
                        isImmutablePojos = true
                        isFluentSetters = true
                    }
                    target {
                        withPackageName("io.typst.chzzk.bridge.sqlite")
                        withDirectory("$projectDir/src-gen")
                    }
                    strategy.name = "org.jooq.codegen.DefaultGeneratorStrategy"
                }
            }
        }
    }
}

jacoco {
    toolVersion = "0.8.14"
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            showStandardStreams = true
            events = setOf(
                TestLogEvent.PASSED,
                TestLogEvent.SKIPPED,
                TestLogEvent.FAILED,
                TestLogEvent.STANDARD_OUT,
                TestLogEvent.STANDARD_ERROR,
            )
            maxHeapSize = "4g"
        }
        finalizedBy(jacocoTestReport)
//        systemProperty("junit.jupiter.execution.timeout.default", "10 s")
    }
    val coverageExcludes = listOf(
        "**/io/typst/chzzk/bridge/sqlite/**"
    )
    jacocoTestReport {
        dependsOn(test)
        classDirectories.setFrom(
            classDirectories.files.map { dir ->
                fileTree(dir) {
                    exclude(coverageExcludes)
                }
            }
        )
    }
    jacocoTestCoverageVerification {
        classDirectories.setFrom(
            classDirectories.files.map { dir ->
                fileTree(dir) {
                    exclude(coverageExcludes)
                }
            }
        )
    }
    assemble {
        dependsOn(shadowJar)
    }
}