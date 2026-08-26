rootProject.name = "m8trx-twin"

dependencyResolutionManagement {
    versionCatalogs {
        create("deps") {
            version("kotlin", "2.4.10")
            version("ktlint", "12.2.0")
            version("jnats", "2.26.2")
            version("jackson", "2.22.2")
            version("coroutines", "1.11.0")
            version("logback", "1.6.3")
            plugin("kotlinJvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
            plugin("ktlint", "org.jlleitschuh.gradle.ktlint").versionRef("ktlint")
        }
    }
}
