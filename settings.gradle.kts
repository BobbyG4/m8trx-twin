rootProject.name = "m8trx-twin"

dependencyResolutionManagement {
    versionCatalogs {
        create("deps") {
            version("kotlin", "2.4.21")
            version("ktlint", "12.2.0")
            version("jnats", "2.26.4")
            version("jackson", "2.22.4")
            version("coroutines", "1.11.0")
            version("logback", "1.6.6")
            plugin("kotlinJvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
            plugin("ktlint", "org.jlleitschuh.gradle.ktlint").versionRef("ktlint")
        }
    }
}
