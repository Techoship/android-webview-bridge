plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.techoship.webviewbridge"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(libs.androidx.webkit)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = providers.gradleProperty("bridge.group").get()
                artifactId = "webview-bridge"
                // JitPack passes the git tag as VERSION.
                version = providers.environmentVariable("VERSION").orElse(providers.gradleProperty("bridge.version")).get()
            }
        }
    }
}
