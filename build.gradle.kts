plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
}

group = "io.github.limpbrains"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf("-Xmx2g")
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "qr"
            from(components["java"])

            pom {
                name.set("QR")
                description.set("A Kotlin library for QR code reading/decoding. Port of Paul Miller's paulmillr/qr library.")
                url.set("https://github.com/limpbrains/qr")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("limpbrains")
                        name.set("Limpbrains")
                    }
                }

                scm {
                    url.set("https://github.com/limpbrains/qr")
                    connection.set("scm:git:git://github.com/limpbrains/qr.git")
                    developerConnection.set("scm:git:ssh://github.com/limpbrains/qr.git")
                }
            }
        }
    }
}
