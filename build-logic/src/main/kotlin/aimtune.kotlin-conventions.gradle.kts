plugins {
    kotlin("jvm")
}

group = "de.yuuto.aimtune"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The sim gates in core hold many headless rounds at once; the default 512 MB worker ran out of heap and then hung in GC.
    maxHeapSize = "1g"
    // A test that hangs fails after this time instead of blocking the build (the slowest gates take about a minute).
    systemProperty("junit.jupiter.execution.timeout.default", "10 m")
}
