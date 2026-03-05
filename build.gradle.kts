plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "me.thecodingduck"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "me.thecodingduck.bugsworld.MainKt"
}