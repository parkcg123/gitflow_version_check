/*
 * Copyright 2026 Git Branch Version Tracker Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )
        bundledPlugin("Git4Idea")
    }
}

fun nonBlankEnv(name: String) = providers.environmentVariable(name).flatMap { raw ->
    providers.provider { raw.trim().takeIf { it.isNotEmpty() } }
}

intellijPlatform {
    buildSearchableOptions.set(false)

    pluginConfiguration {
        version.set(providers.gradleProperty("pluginVersion"))
        ideaVersion {
            sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
            untilBuild.set(provider { null })
        }
    }

    signing {
        certificateChain.set(nonBlankEnv("CERTIFICATE_CHAIN"))
        privateKey.set(nonBlankEnv("PRIVATE_KEY"))
        password.set(nonBlankEnv("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(nonBlankEnv("PUBLISH_TOKEN"))
    }

    pluginVerification {
        ides {
            create("IC", "2023.3.8")
            current()
            select {
                types.set(listOf(IntelliJPlatformType.IntellijIdea))
                channels.set(listOf(ProductRelease.Channel.RELEASE, ProductRelease.Channel.EAP))
                sinceBuild.set("263")
            }
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.release.set(17)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
    }
}
