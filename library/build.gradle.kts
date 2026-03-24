plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("maven-publish")
}

android {
    buildFeatures {
        dataBinding = false
        viewBinding = true
    }

    compileSdk = 36

    defaultConfig {
        minSdk = 19
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }
    namespace = "com.github.evermindzz.challengefloatsaway"

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                // Optional: Falls JitPack eine Gruppen-ID erzwingen will
                groupId = "com.github.evermindzz"
                artifactId = "challengefloatsaway"
                version = "1.0.0"
            }
        }
    }
}

dependencies {
    api("androidx.activity:activity:1.8.2")

    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.core:core:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("com.github.evermind-zz:Cloudflare-Bypass:17b591c0dd")
    implementation("org.greenrobot:eventbus:3.3.1")

    runtimeOnly("androidx.lifecycle:lifecycle-process:2.6.2")
    runtimeOnly("androidx.transition:transition:1.2.0")

    /** Testing **/
    testImplementation(libs.junit)
}
