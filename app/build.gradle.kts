import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.subcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.subcast"
        // 26 (Android 8.0): jetty-util's ModuleLocation uses MethodHandle.invoke,
        // a signature-polymorphic VM intrinsic D8 rejects below min-api 26 (and
        // core-desugaring can't backport). jUPnP's only bundled SOAP client is
        // Jetty-based, so 26 is the floor for the DLNA stack.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Transitive deps ship duplicate META-INF license files that break packaging.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Local HTTP media server (Range support)
    implementation(libs.nanohttpd)

    // DLNA / UPnP control point + AVTransport/RenderingControl (jUPnP = maintained Cling fork)
    implementation(libs.jupnp)
    // jUPnP uses SLF4J but its POM doesn't pull it in -- declare explicitly.
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)
    // jUPnP's control-point SOAP client is JettyStreamClientImpl, which needs
    // jetty-client at runtime. (Server side is disabled -- see DlnaController.)
    implementation(libs.jetty.client)

    // FFmpeg with libass (ASS/SSA subtitle burn-in); full-gpl build, cached on Aliyun mirror
    implementation(libs.ffmpeg.kit.full.gpl)

    debugImplementation(libs.androidx.ui.tooling)
}
