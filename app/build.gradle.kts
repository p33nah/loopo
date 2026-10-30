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
		versionName = "0.0.1"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		ndk {
			abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
		}
		externalNativeBuild {
			cmake {
				cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
				arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
			}
		}
	}

	signingConfigs {
		if (providers.environmentVariable("LOOPO_KEYSTORE_PATH").isPresent) {
			create("distribution") {
				storeFile = file(providers.environmentVariable("LOOPO_KEYSTORE_PATH").get())
				storePassword = providers.environmentVariable("LOOPO_KEYSTORE_PASSWORD").get()
				keyAlias = providers.environmentVariable("LOOPO_KEY_ALIAS").get()
				keyPassword = providers.environmentVariable("LOOPO_KEY_PASSWORD").get()
			}
		}
	}

	buildTypes {
		debug {
			enableUnitTestCoverage = true
			enableAndroidTestCoverage = true
		}
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.findByName("distribution")
		}
	}
	testCoverage {
		jacocoVersion = "0.8.14"
	}

	testOptions {
		unitTests.isIncludeAndroidResources = true
		unitTests.all {
			it.jvmArgs(
				"--add-opens=java.base/java.lang=ALL-UNNAMED",
				"--add-opens=java.base/java.util=ALL-UNNAMED",
				"--add-opens=java.base/java.io=ALL-UNNAMED",
				"--add-opens=java.base/java.net=ALL-UNNAMED",
				"--add-opens=java.base/java.security=ALL-UNNAMED",
				"--add-opens=java.base/java.text=ALL-UNNAMED",
				"--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
				"--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
				"--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
			)
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
		prefab = true
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
	implementation(libs.androidx.datastore.preferences)
	implementation("com.google.oboe:oboe:1.11.0")

	debugImplementation("androidx.compose.ui:ui-tooling")
	debugImplementation(platform(libs.androidx.compose.bom))
	debugImplementation("androidx.compose.ui:ui-test-manifest")
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.robolectric:robolectric:4.17")
	testImplementation("androidx.test:core:1.7.0")
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation("androidx.compose.ui:ui-test-junit4")
	androidTestImplementation("androidx.test.ext:junit:1.3.0")
	androidTestImplementation("androidx.test:runner:1.7.0")
}
