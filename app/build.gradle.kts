import java.security.MessageDigest

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "com.p33nah.loopo"
	compileSdk = libs.versions.compile.sdk.get().toInt()
	ndkVersion = "27.0.12077973"

	defaultConfig {
		applicationId = "com.p33nah.loopo"
		minSdk = libs.versions.min.sdk.get().toInt()
		targetSdk = libs.versions.target.sdk.get().toInt()
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
			it.extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
				isIncludeNoLocationClasses = true
				includes = listOf("com.p33nah.loopo.*")
			}
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
	debugImplementation(libs.vosk.android)
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.robolectric:robolectric:4.17")
	testImplementation("androidx.test:core:1.7.0")
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation("androidx.compose.ui:ui-test-junit4")
	androidTestImplementation("androidx.test.ext:junit:1.3.0")
	androidTestImplementation("androidx.test:runner:1.7.0")
}

val wakeModelPath = providers.gradleProperty("loopoWakeModel")
val prepareWakeModel = tasks.register<PrepareWakeModel>("prepareDebugWakeModelAsset") {
	wakeModelPath.orNull?.let { archive.fileValue(file(it)) }
	assetsDirectory.set(layout.buildDirectory.dir("generated/loopoWake/debug/assets"))
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
	variant.sources.assets?.addGeneratedSourceDirectory(prepareWakeModel, PrepareWakeModel::assetsDirectory)
}

abstract class PrepareWakeModel : DefaultTask() {
	@get:InputFile
	@get:Optional
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val archive: RegularFileProperty

	@get:OutputDirectory
	abstract val assetsDirectory: DirectoryProperty

	@TaskAction
	fun prepare() {
		val directory = assetsDirectory.get().asFile
		val generatedAsset = directory.resolve("loopo-vosk-it.zip")
		check(!generatedAsset.exists() || generatedAsset.delete()) { "Could not remove the previous wake model asset" }
		directory.mkdirs()
		val modelArchive = archive.orNull?.asFile ?: return
		val digest = MessageDigest.getInstance("SHA-256")
		modelArchive.inputStream().use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
		check(actualHash == "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267") {
			"The loopoWakeModel archive has an unexpected SHA-256"
		}
		modelArchive.copyTo(generatedAsset)
	}
}
