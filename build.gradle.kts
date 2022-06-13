plugins {
    val kotlinVersion = "1.5.10"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.11.1"
}

group = "top.saucecode"
version = "1.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation("org.xerial:sqlite-jdbc:3.32.3.2")
    implementation(files("../yqlang/build/libs/yqlang-1.0.jar"))
    implementation("io.socket:socket.io-client:2.0.1")
}
