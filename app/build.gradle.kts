plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "com.p33nah.loopo"
	compileSdk = libs.versions.compile.sdk.get().toInt()
	ndkVersion = "27.0.12077973"

	defaultConfig {
		applicationId = "com.p33nah.loopo"
		minSdk = libs.versions.min.sdk.get().toInt()
		targetSdk = libs.versions.compile.sdk.get().toInt()
		versionCode = 1
		versionName = "0.1.0"
		ndk {
			abiFilters += "arm64-v8a"
		}
		externalNativeBuild {
			cmake {
				cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = false
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

	buildFeatures {
		compose = true
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.22.1"
		}
	}
}

dependencies {
	implementation(libs.androidx.activity.compose)
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)

	debugImplementation("androidx.compose.ui:ui-tooling")
}
