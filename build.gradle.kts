import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.external.javadoc.CoreJavadocOptions

// These values come from gradle.properties
val ossrhUsername: String by project
val ossrhPassword: String by project

buildscript {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    java
    "java-library"
    checkstyle
    jacoco
    signing
    "maven-publish"
    idea
    id("org.jetbrains.kotlin.jvm") version "1.6.10"
    id("de.marcphilipp.nexus-publish") version "0.4.0"
    id("io.codearte.nexus-staging") version "0.30.0"
}

// Note about org.jetbrains.kotlin.jvm in the plugins block:
// Normally we wouldn't have to explicitly list the Kotlin plugin here since we're
// only building Java code. However-- possibly because OkHttp uses Kotlin-- if we
// don't list the plugin here, then we're unable to use the "api" configuration in
// dependencies even though that would normally be made available by the combination
// of "java" and "java-library". See:
// https://discuss.gradle.org/t/does-the-java-library-plugin-actually-do-anything-gradle-cant-do-natively/34361/5
// We have to make sure the version shown here matches the version that is used by
// OkHttp-- otherwise we will end up with an extra Kotlin runtime dependency in our
// own pom.
// This does not appear to be an issue with other Java projects such as
// launchdarkly-java-sdk-common; there, we can just use "java" and "java-library".

repositories {
    mavenLocal()
    // Before LaunchDarkly release artifacts get synced to Maven Central they are here along with snapshots:
    maven { url = uri("https://oss.sonatype.org/content/groups/public/") }
    mavenCentral()
}

base {
    group = "com.launchdarkly"
    archivesBaseName = "okhttp-eventsource"
    version = version
}

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

object Versions {
    const val launchdarklyLogging = "1.1.0"
    const val okhttp = "4.9.3"
    const val slf4j = "1.7.22"
}

dependencies {
    api("com.launchdarkly:launchdarkly-logging:${Versions.launchdarklyLogging}")
    api("com.squareup.okhttp3:okhttp:${Versions.okhttp}")
    api("org.slf4j:slf4j-api:${Versions.slf4j}")
    // SLF4J is no longer referenced directly by okhttp-eventsource, but since the default behavior is
    // to use the SLF4J adapter from com.launchdarkly.logging, we are still retaining the dependency
    // here to make sure it is in the classpath.
    testImplementation("org.mockito:mockito-core:1.10.19")
    testImplementation("com.launchdarkly:test-helpers:1.0.0")
    testImplementation("com.google.guava:guava:30.1-jre")
    testImplementation("junit:junit:4.12")
    testImplementation("org.hamcrest:hamcrest-all:1.3")
}

checkstyle {
    configFile = file("${project.rootDir}/checkstyle.xml")
}

tasks.jar.configure {
    manifest {
        attributes(mapOf("Implementation-Version" to project.version))
    }
}

tasks.javadoc.configure {
    // Force the Javadoc build to fail if there are any Javadoc warnings. See: https://discuss.gradle.org/t/javadoc-fail-on-warning/18141/3
    // See JDK-8200363 (https://bugs.openjdk.java.net/browse/JDK-8200363)
    // for information about the -Xwerror option.
    (options as CoreJavadocOptions).addStringOption("Xwerror")
}

tasks.test.configure {
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.jacocoTestReport.configure {
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification.configure {
    // See notes in CONTRIBUTING.md on code coverage. Unfortunately we can't configure line-by-line code
    // coverage overrides within the source code itself, because Jacoco operates on bytecode.
    violationRules {
        val knownMissedLinesForMethods = mapOf(
            // The key for each of these items is the complete method signature minus the "com.launchdarkly.eventsource." prefix.
            "AsyncEventHandler.acquire()" to 2,
            "AsyncEventHandler.execute(java.lang.Runnable)" to 3,
            "EventSource.awaitClosed(java.time.Duration)" to 3,
            "EventSource.handleSuccessfulResponse(okhttp3.Response)" to 2,
            "EventSource.maybeReconnectDelay(int, long)" to 2,
            "EventSource.run()" to 3,
            "EventSource.Builder.createInitialClientBuilder()" to 1,
            "EventSource.Builder.defaultTrustManager()" to 2,
            "MessageEvent.getData()" to 2,
            "SLF4JLogger.error(java.lang.String)" to 2,
            "ModernTLSSocketFactory.createSocket(java.lang.String, int)" to 1,
            "ModernTLSSocketFactory.createSocket(java.lang.String, int, java.net.InetAddress, int)" to 1,
            "ModernTLSSocketFactory.createSocket(java.net.InetAddress, int)" to 1,
            "ModernTLSSocketFactory.createSocket(java.net.InetAddress, int, java.net.InetAddress, int)" to 1,
            "ModernTLSSocketFactory.createSocket(java.net.Socket, java.lang.String, int, boolean)" to 1,
            "ModernTLSSocketFactory.getDefaultCipherSuites()" to 1,
            "ModernTLSSocketFactory.getSupportedCipherSuites()" to 1
        )
        
        knownMissedLinesForMethods.forEach { (signature, maxMissedLines) ->
            if (maxMissedLines > 0) {  // < 0 means skip entire method
                rule {
                    element = "METHOD"
                    includes = listOf("com.launchdarkly.eventsource." + signature)
                    limit {
                        counter = "LINE"
                        value = "MISSEDCOUNT"
                        maximum = maxMissedLines.toBigDecimal()
                    }
                }
            }
        }
        
        // General rule that we should expect 100% test coverage; exclude any methods that have overrides above
        rule {
            element = "METHOD"
            limit {
                counter = "LINE"
                value = "MISSEDCOUNT"
                maximum = 0.toBigDecimal()
            }
            excludes = knownMissedLinesForMethods.map { (signature, maxMissedLines) ->
                "com.launchdarkly.eventsource." + signature }
        }
    }
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

nexusStaging {
    packageGroup = "com.launchdarkly"
    numberOfRetries = 40 // we've seen extremely long delays in closing repositories
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            groupId = "com.launchdarkly"
            artifactId = "okhttp-eventsource"

            pom {
                name.set("okhttp-eventsource")
                description.set("EventSource Implementation built on OkHttp")
                url.set("https://github.com/launchdarkly/okhttp-eventsource")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("LaunchDarkly SDK Team")
                        email.set("sdks@launchdarkly.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/launchdarkly/okhttp-eventsource.git")
                    developerConnection.set("scm:git:ssh:git@github.com:launchdarkly/okhttp-eventsource.git")
                    url.set("https://github.com/launchdarkly/okhttp-eventsource")
                }
            }
        }
    }
    repositories {
        mavenLocal()
    }
}

nexusPublishing {
    clientTimeout.set(Duration.ofMinutes(2)) // we've seen extremely long delays in creating repositories
    repositories {
        sonatype {
            username.set(ossrhUsername)
            password.set(ossrhPassword)
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}
