plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
}

group = "com.keklol"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf("-Xmx2g")  // 2GB heap for loading test vectors
}

kotlin {
    jvmToolchain(17)
}
