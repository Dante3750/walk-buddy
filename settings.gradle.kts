pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "walk-buddy"
include(":app", ":domain")

// The Glance home-screen widget lives in its own module so a problem there can never break the main app.
// Turn it off with -Pwidget=false (or widget=false in gradle.properties).
if (providers.gradleProperty("widget").getOrElse("true") != "false") {
    include(":widget")
}
