package com.mai.aarpacker

import com.android.ide.common.symbols.ResourceDirectoryParser
import com.android.ide.common.symbols.IdProvider
import com.google.common.io.Files
import groovy.xml.XmlParser
import kotlin.text.Charsets
import org.gradle.api.Task
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency

import java.util.regex.Pattern

import static Utils.*

class AarPacker implements Plugin<Project> {

    // configuration
    private static final String EMBEDDED_CONFIGURATION_NAME = "embedded"
    private ArrayList<File> embeddedJars = new ArrayList<>()
    private ArrayList<String> embeddedAarDirs = new ArrayList<>()
    private String buildDir
    private String packagedClassDir
    private String intermediatesDir
    private String buildPluginDir
    private String tempClassesDir
    private String explodedAarDir
    private String aarLibsDir
    private AarPackerExtension extension

    private void init(Project project) {
        buildDir = project.buildDir.path.replace(File.separator, "/")
        packagedClassDir = "$buildDir/intermediates/compile_r_class_jar/"
        intermediatesDir = "$buildDir/intermediates"
        buildPluginDir = "$buildDir/aar-packer"
        tempClassesDir = "$buildPluginDir/temp-classes"
        explodedAarDir = "$buildPluginDir/exploded-aar"
        aarLibsDir = "$intermediatesDir/aar_libs_directory"
        extension = project.extensions.create("aarpacker", AarPackerExtension)
    }

    @Override
    void apply(Project project) {
        init(project)

        logLevel2("init plugin...")
        // create "embedded"
        project.configurations.maybeCreate("$EMBEDDED_CONFIGURATION_NAME")
        // create configuration for all flavor
        project.android.productFlavors.all { flavor ->
            def configurationName = "${flavor.name}${EMBEDDED_CONFIGURATION_NAME.capitalize()}"
            logLevel2("create configuration $configurationName")
            project.configurations.maybeCreate(configurationName)
        }

        project.afterEvaluate {
            logLevel2("project :$project.name")

            if (project.configurations."$EMBEDDED_CONFIGURATION_NAME".size() <= 0) {
                // ignore
                return
            }

            project.android.libraryVariants.all { libraryVariant ->
                def flavorName = libraryVariant.flavorName                  // flavorName
                def buildType = libraryVariant.buildType.name               // debug/release
                def flavorBuildType = libraryVariant.name.capitalize()      // FlavorNameDebug/FlavorNameRelease
                def enableProguard = libraryVariant.buildType.minifyEnabled // minifyEnabled value

                def embeddedSize = 0
                try {
                    // project.configurations.flavorNameEmbedded npe
                    project.dependencies.add("implementation", project.configurations."$flavorName${EMBEDDED_CONFIGURATION_NAME.capitalize()}")
                    embeddedSize += project.configurations."$flavorName${EMBEDDED_CONFIGURATION_NAME.capitalize()}".size()
                } catch (Throwable ignore) {
                    logV("flavorNameEmbedded not found...")
                }
                try {
                    // project.configurations.embedded not found
                    project.dependencies.add("implementation", project.configurations."$EMBEDDED_CONFIGURATION_NAME")
                    embeddedSize += +project.configurations."$EMBEDDED_CONFIGURATION_NAME".size()
                } catch (Throwable ignore) {
                    logV("embedded not found...")
                }
                if (embeddedSize > 0) {
                    logLevel2("${flavorName}Embedded.size: $embeddedSize")

                    // decompress aar file
                    Task decompressTask = project.task("decompress${flavorBuildType}Dependencies", group: "aar-packer").doLast {
                        decompressDependencies(project, flavorName)
                    }
                    // embedded
                    project.configurations."$EMBEDDED_CONFIGURATION_NAME".dependencies.each {
                        dependencyTask(it, decompressTask, flavorBuildType, buildType)
                    }
                    try {
                        // flavorEmbedded
                        project.configurations."$flavorName${EMBEDDED_CONFIGURATION_NAME.capitalize()}".dependencies.each {
                            dependencyTask(it, decompressTask, flavorBuildType, buildType)
                        }
                    } catch (Throwable ignore) {

                    }

                    def addSourceSetsTask = project.task("add${flavorBuildType}SourceSets", group: "aar-packer").doLast {
                        embeddedAarDirs.each {
                            // delete string.app_name and network_security_config.xml
                            deleteAttrs(it)
                            // addSourceSets
                            project.android.sourceSets.main.res.srcDirs += project.file("$it/res")
                            project.android.sourceSets.main.aidl.srcDirs += project.file("$it/aidl")
                            project.android.sourceSets.main.assets.srcDirs += project.file("$it/assets")
                            project.android.sourceSets.main.jniLibs.srcDirs += project.file("$it/jni")
                        }
                    }

                    addSourceSetsTask.dependsOn(decompressTask)
                    project.tasks."pre${flavorBuildType}Build".dependsOn(addSourceSetsTask)

                    // mergeManifests
                    def embedManifestsTask = project.task("embed${flavorBuildType}Manifests", group: "aar-packer").doLast {
                        embedManifests(flavorBuildType)
                    }
                    embedManifestsTask.dependsOn(project.tasks."process${flavorBuildType}Manifest")

                    // embed jars and generate new R.jar
                    def embedJarTask = project.task("embed${flavorBuildType}LibJarAndRClass", group: "aar-packer").doLast {
                        // generate new R.jar
                        def tableList = new ArrayList()
                        embeddedAarDirs.each { aarLibsDir ->
                            def resDir = new File("$aarLibsDir/res")
                            if (resDir.listFiles() == null) {
                                return
                            }
                            def table = ResourceDirectoryParser.parseResourceSourceSetDirectory(
                                    resDir, IdProvider.@Companion.sequential(), null, null, true)
                            if (aarLibsDir.contains("aar_name_pattern")) {
                                table = parseAarRFile(new File("$aarLibsDir/R.txt"), IdProvider.@Companion.sequential(), table)
                            }
                            def aarPackageName = new XmlParser().parse("$aarLibsDir/AndroidManifest.xml").@package
                            def field = table.getClass().getDeclaredField("tablePackage")
                            field.setAccessible(true)
                            field.set(table, aarPackageName)
                            tableList.add(table)
                        }

                        def currentPackageName = new XmlParser().parse("${new File(buildDir).parent}/src/main/AndroidManifest.xml").@package
                        exportToCompiledJava(tableList, currentPackageName, new File("$packagedClassDir/$flavorName${buildType.capitalize()}/R.jar").toPath())

                        // embed jars
                        if (!enableProguard) {
                            embeddedAarDirs.each { aarLibsDir ->
                                def jars = project.fileTree(dir: aarLibsDir, include: '*.jar', exclude: 'classes.jar')
                                jars += project.fileTree(dir: "$aarLibsDir/libs", include: '*.jar')
                                embeddedJars.addAll(jars)
                            }
                            embeddedJars.add(project.fileTree(dir: tempClassesDir))

                            // copy all additional jar files to bundle lib
                            project.copy {
                                from embeddedJars
                                into project.file("$aarLibsDir/$flavorName${buildType.capitalize()}/libs")
                            }
                            // rename new R.jar
                            project.copy {
                                from new File("$packagedClassDir/$flavorName${buildType.capitalize()}/R.jar")
                                into project.file("$aarLibsDir/$flavorName${buildType.capitalize()}/libs/")
                                rename("R.jar", "${project.name}_R.jar")
                            }
                        }
                        logV("--> end embedJarTasks")
                    }

                    embedJarTask.dependsOn(project.tasks."sync${libraryVariant.name.capitalize()}LibJars")

                    // embed AAR/R.txt
                    project.tasks."generate${flavorBuildType}RFile".doFirst {
                        embeddedAarDirs.each { aarLibsDir ->
                            if (aarLibsDir.contains("aar_name_pattern")) {
                                def rFile = new File("$intermediatesDir/local_only_symbol_list/${flavorBuildType.uncapitalize()}/R-def.txt")
                                def rText = Files.asCharSource(rFile, Charsets.UTF_8).read()
                                def remoteFile = new File("$aarLibsDir/R.txt")
                                // merge aar_name_pattern.aar/R.txt
                                def newRText = mergeAARV28StyleRFile(remoteFile, rText)
                                // delete last line with content "\n"
                                // otherwise generateRFile will throw a index exception when call readLines()
                                Files.asCharSink(rFile, Charsets.UTF_8).write(newRText.substring(0, newRText.length() - 1))
                            }
                        }
                    }

                    try {
                        def bundleAarTask = project.tasks."bundle${flavorBuildType}Aar"
                        bundleAarTask.dependsOn embedManifestsTask
                        bundleAarTask.dependsOn embedJarTask
                    } catch (Throwable tr) {
                        tr.printStackTrace()
                        throw tr
                    }
                }
            }
        }
    }

    /**
     * decompress aar files to build/aar-packer/exploded-aar
     */
    private def decompressDependencies(Project project, String flavorName) {
        // clean
        project.delete(buildPluginDir)

        def artifactList = new ArrayList<ResolvedArtifact>()

        try {
            // embedded
            Configuration defaultConfiguration = project.configurations."$EMBEDDED_CONFIGURATION_NAME"
            // local dependencies (Jar only)
            defaultConfiguration.files { it instanceof DefaultSelfResolvingDependency }.each {
                artifactList.add(new LocalResolvedArtifact(it))
            }
            // remote dependencies (aar)
            artifactList.addAll(defaultConfiguration.resolvedConfiguration.resolvedArtifacts)
        } catch (Throwable ignore) {

        }
        try {
            // flavorNameEmbedded
            Configuration flavorConfiguration = project.configurations."$flavorName${EMBEDDED_CONFIGURATION_NAME.capitalize()}"
            // local dependencies (Jar only)
            flavorConfiguration.files { it instanceof DefaultSelfResolvingDependency }.each {
                artifactList.add(new LocalResolvedArtifact(it))
            }
            // remote dependencies (aar)
            artifactList.addAll(flavorConfiguration.resolvedConfiguration.resolvedArtifacts)
        } catch (Throwable ignore) {

        }

        def embeddedPackages = new ArrayList<String>()

        artifactList.each { artifact ->
            // output directory
            def destination = ""
            if (artifact instanceof LocalResolvedArtifact) {
                destination = "$explodedAarDir/localDependencies/$artifact.name"
                logLevel2(" localArtifact.name: $artifact.name")
            } else {
                destination = "$explodedAarDir/${artifact.moduleVersion.toString().replace(":", "/")}"
                logLevel2(" artifact.name: $artifact.name")
            }

            // ignore by ext
            def moduleVersionId = artifact.moduleVersion.toString()

            extension.getIgnoreDependencies().each { pattern ->
                if (Pattern.matches(pattern, artifact.name)
                        || Pattern.matches(pattern, artifact.moduleVersion.toString())
                        || Pattern.matches(pattern, "${artifact.name}.jar")) {
                    logV("Ignore matched dependency: [$moduleVersionId]")
                    return
                }
            }

            // handle aar file
            if (artifact.type == 'aar') {
                if (artifact.file.isFile()) {
                    // decompressTo
                    project.copy {
                        from project.zipTree(artifact.file)
                        into destination
                    }
                }
                // android:package
                def packageName = new XmlParser().parse("$destination/AndroidManifest.xml").@package
                logV(" aar: $artifact & packageName: $packageName")
                // ignore android support or androidx package
                if (extension.ignoreAndroidSupport && (packageName.startsWith("android.") || packageName.startsWith("androidx."))) {
                    logV("Ignore android package:[$packageName]")
                    return
                }

                // ignore duplicate package
                if (embeddedPackages.contains(packageName)) {
                    logV("Duplicate package: [$packageName], [$artifact.file] has been ignored automatically")
                    return
                }

                // record
                embeddedPackages.add(packageName)
                if (!embeddedAarDirs.contains(destination)) {
                    embeddedAarDirs.add(destination)
                }

                // copy aar-classes.jar to temp_dir and rename
                project.copy {
                    from "$destination/classes.jar"
                    into "$tempClassesDir/"
                    rename "classes.jar", "${artifact.name}.jar"
                }
            } else if (artifact.type == "jar") {
                logV("jar info: $artifact.name $artifact.id $artifact.classifier $artifact.moduleVersion.id.group")
                def groupName = "$artifact.moduleVersion.id.group:$artifact.name"

                // ignore android jar
                if (extension.ignoreAndroidSupport &&
                        (groupName.startsWith("android.") || groupName.startsWith("com.android."))) {
                    logV("Ignore android jar:[$groupName]")
                    return
                }

                // ignore duplicate jar
                if (embeddedPackages.contains(groupName)) {
                    logV("Duplicate jar: [$groupName] has been ignored automatically")
                    return
                }

                // record
                embeddedPackages.add(groupName)
                if (!embeddedJars.contains(artifact.file)) {
                    embeddedJars.add(artifact.file)
                }
            } else {
                throw new IllegalArgumentException("Unhandled artifact of type $artifact.type")
            }
        }

        reportEmbeddedFiles()
    }

    private static def dependencyTask(Dependency it, Task decompressTask, String flavorBuildType, String buildType) {
        // If there is a project dependency, must build the dependency project first
        if (it instanceof DefaultProjectDependency) {
            if (it.targetConfiguration == null) {
                it.targetConfiguration = "default"
            }
            def dependencyTasks = it.dependencyProject.tasks
            // Find the correct dependency project task
            if (dependencyTasks.findByName("bundle${flavorBuildType}Aar")) {
                decompressTask.dependsOn(dependencyTasks."bundle${flavorBuildType}Aar")
            } else if (dependencyTasks.findByName("bundle${buildType.capitalize()}Aar")) {
                decompressTask.dependsOn(dependencyTasks."bundle${buildType.capitalize()}Aar")
            } else {
                throw new Exception("Can not find dependency project task!")
            }
        }
    }

    private def logV(Object value) {
        if (extension.verboseLog) {
            logLevel2(value)
        }
    }

    /**
     * report to file
     */
    private def reportEmbeddedFiles() {
        def sb = new StringBuilder()
        sb.append("\n-- embedded aar dirs --\n")
        embeddedAarDirs.each {
            sb.append("$it\n")
        }
        sb.append("\n-- embedded jars --\n")
        embeddedJars.each {
            sb.append("$it\n")
        }
        Files.asCharSink(new File("$buildDir/embedDepencies.txt"), Charsets.UTF_8).write(sb.toString())
    }

    /**
     * mergeAndroidManifest
     */
    private def embedManifests(String flavorBuildType) {
        try {
            def mainManifest = new File("$intermediatesDir/merged_manifest/$flavorBuildType/AndroidManifest.xml")
            if (mainManifest == null || !mainManifest.exists()) {
                return
            }
            logV("MainManifestFile: $mainManifest")

            def libraryManifests = new ArrayList<File>()
            embeddedAarDirs.each {
                def aarManifest = new File("$it/AndroidManifest.xml")
                if (!libraryManifests.contains(aarManifest) && aarManifest.exists()) {
                    libraryManifests.add(aarManifest)
                }
            }

            def reportFile = new File("$buildDir/embedManifestReport.txt")
            mergeManifest(mainManifest, libraryManifests, reportFile, "AAR_PACKER_PLACE_HOLDER")
        } catch (Throwable tr) {
            tr.printStackTrace()
            throw new RuntimeException(tr)
        }
    }

}