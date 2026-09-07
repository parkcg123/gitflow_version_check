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

plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.github.gitversiontracker"
version = "1.0.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.3.6")
    type.set("IC")
    plugins.set(listOf("git4idea"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("243.*")
    }

    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    compileTestKotlin {
        kotlinOptions.jvmTarget = "17"
    }
}
