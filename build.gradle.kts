import java.util.*

plugins {
    java
    application
    jacoco
    id("com.github.breadmoirai.github-release") version "2.5.2"
    id("com.github.jarmstrong.buildconfig") version "1.9.0"
    id("cloud.rio.license") version "0.18.0"
}

val edtLocation = file(project.properties["EDT_LOCATION"] as String)
println("EDT location: $edtLocation")

repositories {
    mavenCentral()
    maven("https://jitpack.io")

    flatDir {
        dirs(edtLocation)
    }
}

group = "com.clouds42"
version = "2.7.3.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = "com.clouds42.Coverage41C"
}

dependencies {

    compileOnly(
        fileTree(edtLocation) {
            include("com._1c.g5.v8.dt.debug.*.jar")
        }
    )

    implementation("info.picocli:picocli:4.7.7")

    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    implementation("org.scala-sbt.ipcsocket:ipcsocket:1.6.3")

    implementation("org.eclipse.emf:org.eclipse.emf.common:2.44.0")
    implementation("org.eclipse.emf:org.eclipse.emf.ecore.xmi:2.39.0")
    implementation("org.eclipse.emf:org.eclipse.emf.ecore.xcore.lib:1.7.1")

    implementation("org.eclipse.platform:org.eclipse.osgi:3.24.0")
    implementation("org.eclipse.platform:org.eclipse.core.runtime:3.34.100")
    implementation("org.eclipse.platform:org.eclipse.osgi.services:3.12.300")
    implementation("org.eclipse.platform:org.eclipse.equinox.common:3.20.300")

    implementation("org.eclipse.jetty:jetty-client:11.0.26")

    implementation("com.google.guava:guava:33.5.0-jre")
    implementation("com.google.inject:guice:7.0.0")

    implementation("de.vandermeer:asciitable:0.3.2") {
        exclude("org.apache.commons:commons-lang3")
    }

    implementation("io.github.1c-syntax:bsl-common-library:0.9.0")
    implementation("io.github.1c-syntax:utils:0.6.6")

    implementation("io.github.1c-syntax:mdclasses:0.17.0")
    implementation("io.github.1c-syntax:bsl-parser:0.29.0") {
        exclude("com.ibm.icu", "*")
        exclude("org.antlr", "ST4")
        exclude("org.antlr", "antlr-runtime")
    }

    annotationProcessor("info.picocli:picocli-codegen:4.7.7")

    testImplementation("org.xmlunit:xmlunit-core:2.11.0")
    testImplementation("org.xmlunit:xmlunit-matchers:2.11.0")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.20.1")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

buildConfig {
    className("BuildConfig")
    packageName("com.clouds42")
    buildConfigField("String", "APP_VERSION", "\"${project.version}\"")
    buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
}

tasks.generateBuildConfig {
    dependsOn(tasks.licenseMain)
}

tasks.named("startScripts", CreateStartScripts::class) {
    (unixStartScriptGenerator as TemplateBasedScriptGenerator).template =
        resources.text.fromFile(file("${project.rootDir.absolutePath}/scripts/unixStartScript.txt"))

    (windowsStartScriptGenerator as TemplateBasedScriptGenerator).template =
        resources.text.fromFile(file("${project.rootDir.absolutePath}/scripts/windowsStartScript.txt"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Aproject=${project.group}/${project.name}")
    classpath += configurations.compileClasspath.get()
}

tasks.test {
    classpath += configurations.compileClasspath.get()
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed", "standard_error")
    }

    reports {
        html.required.set(true)
    }

    testLogging.showStandardStreams = true
}

tasks.check {
    dependsOn(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        xml.outputLocation.set(File("${layout.buildDirectory.get()}/reports/jacoco/test/jacoco.xml"))
    }
}

license {
    header = rootProject.file("HEADER.txt")
    skipExistingHeaders = false
    strictCheck = true
    mapping("java", "SLASHSTAR_STYLE")

    ext["year"] = "2020-" + Calendar.getInstance().get(Calendar.YEAR)
    ext["name"] = "Kosolapov Stanislav aka proDOOMman <prodoomman@gmail.com>"
    ext["project"] = "Coverage41C"
    exclude("**/*.properties")
    exclude("**/*.xml")
    exclude("**/*.json")
    exclude("**/*.bsl")
    exclude("**/*.os")
    exclude("**/*.txt")
    exclude("**/*.java.orig")
    exclude("**/*.impl")
    exclude("**/BuildConfig.java")
    exclude("**/test/resources/**/*")
}

tasks.named("run", JavaExec::class) {
    classpath += configurations.compileClasspath.get()
}

githubRelease {
    token(project.findProperty("github.token")?.toString() ?: "")
    owner = "proDOOMman"
    repo = "Coverage41C"
    targetCommitish = "master"
    releaseAssets(tasks.named("distZip"))
    dryRun = false
}
