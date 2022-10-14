package com.mai.aarpacker

import com.android.SdkConstants
import com.android.build.gradle.internal.LoggerWrapper
import com.android.ide.common.symbols.IdProvider
import com.android.ide.common.symbols.ResourceDirectoryParseException
import com.android.ide.common.symbols.Symbol
import com.android.ide.common.symbols.SymbolTable
import com.android.ide.common.symbols.SymbolUtils
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestMerger2.MergeType
import com.android.manifmerger.MergingReport
import com.android.resources.ResourceFolderType
import com.android.resources.ResourceType
import com.google.common.base.Charsets
import com.google.common.io.Files
import groovy.transform.CompileStatic
import groovy.xml.XmlParser
import groovy.xml.XmlUtil
import org.gradle.api.Task
import org.gradle.api.logging.Logging
import org.objectweb.asm.ClassWriter

import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import static org.objectweb.asm.Opcodes.*

@CompileStatic
class Utils {

    static def logLevel1(Object value) {
        println(">> $value")
    }

    static def logLevel2(Object value) {
        println("   $value")
    }


    /**
     * delete string.app_name and network_security_config.xml
     */
    static void deleteAttrs(String aarPath) {
        def files = new File("$aarPath/res").listFiles()
        if (files == null) {
            return
        }
        def removeCount = 0
        files.each { resourceDir ->
            if (!resourceDir.isDirectory()) {
                throw new ResourceDirectoryParseException("$resourceDir.absolutePath is not a directory")
            }
            assert (resourceDir.isDirectory())

            def folderResourceType = ResourceFolderType.getFolderType(resourceDir.name)

            // values and xml
            def folderType = folderResourceType == ResourceFolderType.VALUES ? 1 : folderResourceType == ResourceFolderType.XML ? 2 : 0
            if (folderType > 0) {
                def listFiles = resourceDir.listFiles()
                if (listFiles != null) {
                    listFiles.each { maybeResourceFile ->
                        // ignore directory
                        if (!maybeResourceFile.isDirectory()) {
                            // assert file
                            if (!maybeResourceFile.isFile()) {
                                throw new ResourceDirectoryParseException("$maybeResourceFile.absolutePath is not a file nor directory")
                            }
                            if (folderType == 1) {
                                // string
                                def node = new XmlParser().parse(maybeResourceFile)
                                def stringNode = node.get("string")
                                if (stringNode) {
                                    (stringNode as ArrayList<Node>).each { childNode ->
                                        if ((childNode).attribute("name") == "app_name") {
                                            logLevel2("Found value [app_name] in [$maybeResourceFile.absolutePath] with value ${childNode.value()}")
                                            node.remove(childNode)
                                            Files.asCharSink(maybeResourceFile, Charsets.UTF_8).write(XmlUtil.serialize(node))
                                            removeCount++
                                        }
                                    }
                                }
                            } else {
                                // xml
                                // aar/xml/network_security_config.xml probably have the save name as the project.android:networkSecurityConfig
                                // causing project.config being overwritten
                                if (maybeResourceFile.name == "network_security_config.xml") {
                                    maybeResourceFile.delete()
                                    removeCount++
                                }
                            }
                        }
                    }
                }
            }
        }
        // print delete count
        if (removeCount > 0) {
            logLevel2("Delete $removeCount nodes...")
        }
    }

    /**
     * embed aar/AndroidManifest to project/AndroidManifest
     */
    static void mergeManifest(File mainManifestFile, ArrayList<File> libraryManifestFiles, File reportFile, String placeHolder) {
        def logger = new LoggerWrapper((Logging.getLogger(Task.class)))
        Files.asCharSink(mainManifestFile, Charsets.UTF_8).write(Files.asCharSource(mainManifestFile, Charsets.UTF_8).read().replace("\${applicationId}", placeHolder))
        def invoker = ManifestMerger2.newMerger(mainManifestFile, logger, MergeType.APPLICATION)
        libraryManifestFiles.each {
            // replace "${applicationId}" with "PLACE_HOLDER_TEXT"
            Files.asCharSink(it, Charsets.UTF_8).write(Files.asCharSource(it, Charsets.UTF_8).read().replace("\${applicationId}", placeHolder))
            invoker.addLibraryManifest(it)
        }
        invoker.setMergeReportFile(reportFile)

        def mergingReport = invoker.merge()
        switch (mergingReport.result) {
            case MergingReport.Result.WARNING:
                mergingReport.log(logger)
                break
            case MergingReport.Result.SUCCESS:
                def xmlDoc = mergingReport.getMergedXmlDocument(MergingReport.MergedManifestKind.MERGED)
                // replace "PLACE_HOLDER_TEXT" to "${applicationId}"
                // remove "android:allowBackup"
                Files.asCharSink(mainManifestFile, Charsets.UTF_8).write(xmlDoc.prettyPrint().replace(placeHolder, "\${applicationId}").replace("android:allowBackup=\"false\"", "").replace("android:allowBackup=\"true\"", ""))
                break
            case MergingReport.Result.ERROR:
                mergingReport.log(logger)
                throw new RuntimeException(mergingReport.reportString)
                break
            default:
                throw new RuntimeException("Unhandled result type: $mergingReport.result")
        }
    }

    /**
     * generate new R.jar
     *
     * @param tableList Symbols of android resource
     * @param targetPackageName Current package name
     * @param outJar Destination of R.jar
     */
    static void exportToCompiledJava(ArrayList<SymbolTable> tableList, String targetPackageName, Path outJar) {
        def jarOutputStream = new JarOutputStream(new BufferedOutputStream(java.nio.file.Files.newOutputStream(outJar)))
        tableList.each { table ->
            def packageName = table.tablePackage
            def resourceTypes = EnumSet.noneOf(ResourceType.class)
            for (resType in ResourceType.values()) {
                def bytes = generateResourceTypeClass(table, packageName, targetPackageName, resType)
                if (bytes == null) {
                    continue
                }
                resourceTypes.add(resType)
                def innerR = generateInternalName(packageName, resType)
                jarOutputStream.putNextEntry(new ZipEntry(innerR + SdkConstants.DOT_CLASS))
                jarOutputStream.write(bytes)
            }

            // Generate and write this main R class file.
            def packageR = generateInternalName(packageName, null)
            jarOutputStream.putNextEntry(new ZipEntry(packageR + SdkConstants.DOT_CLASS))
            jarOutputStream.write(generateOuterRClass(resourceTypes, packageR))
        }
        if (jarOutputStream != null) {
            jarOutputStream.flush()
            jarOutputStream.close()
        }
    }

    /**
     * generate main R.class
     * like com.a.b.c.R.class
     */
    private static byte[] generateOuterRClass(EnumSet<ResourceType> resourceTypes, String packageR) {
        def classWriter = new ClassWriter(COMPUTE_MAXS)
        // public final class R extends Object {}
        classWriter.visit(
                V1_8,
                ACC_PUBLIC + ACC_FINAL + ACC_SUPER,
                packageR,
                null,
                "java/lang/Object",
                null)
        resourceTypes.each { resourceType ->
            classWriter.visitInnerClass(
                    "$packageR\$${resourceType.name}",
                    packageR,
                    resourceType.name,
                    ACC_PUBLIC + ACC_FINAL + ACC_STATIC)
        }

        // constructor
        // private R() {return;}
        def methodVisitor = classWriter.visitMethod(
                ACC_PRIVATE,
                "<init>",
                "()V",
                null,
                null)
        methodVisitor.visitCode()
        methodVisitor.visitVarInsn(ALOAD, 0)
        methodVisitor.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false)
        methodVisitor.visitInsn(RETURN)
        methodVisitor.visitMaxs(0, 0)
        methodVisitor.visitEnd()

        classWriter.visitEnd()

        return classWriter.toByteArray()
    }

    /**
     * generate R$symbol.Class
     * eg. com.a.b.c.R$string.class
     */
    private static byte[] generateResourceTypeClass(SymbolTable symbolTable, String packageName, String targetPackageName, ResourceType resourceType) {
        def symbols = symbolTable.getSymbolByResourceType(resourceType)
        if (symbols.isEmpty()) {
            return null
        }
        def classWriter = new ClassWriter(COMPUTE_MAXS)
        def internalName = generateInternalName(packageName, resourceType)
        // public final class com.a.b.R$string
        classWriter.visit(
                V1_8,
                ACC_PUBLIC + ACC_FINAL + ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null)
        // public static final class string {}
        classWriter.visitInnerClass(
                internalName,
                generateInternalName(packageName, null),
                resourceType.name,
                ACC_PUBLIC + ACC_FINAL + ACC_STATIC)
        symbols.each { symbol ->
            // public static int X_XX_XXX
            classWriter.visitField(
                    ACC_PUBLIC + ACC_STATIC,
                    symbol.name.replace(".", "_"), // styleable
                    symbol.javaType.desc,
                    null,
                    null
            )
            // styleable
            if (symbol instanceof Symbol.StyleableSymbol) {
                symbol.children.each { child ->
                    // public static int PARENT-STYLE_CHILD-FIELD-NAME
                    classWriter.visitField(
                            ACC_PUBLIC + ACC_STATIC,
                            "${symbol.name.replace(".", "_")}_${SymbolUtils.canonicalizeValueResourceName(child)}",
                            "I",
                            null,
                            null
                    )
                }
            }
        }

        // constructor
        // private R$string() {return;}
        def constructor = classWriter.visitMethod(
                ACC_PRIVATE,
                "<init>",
                "()V",
                null,
                null)
        constructor.visitCode()
        constructor.visitVarInsn(ALOAD, 0)
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()

        // <clinit>
        // static { }
        def clinit = classWriter.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        symbols.each { symbol ->
            // com.a.b.R$string.X_XX_XXX = ${OUT_AAR_PACKAGE_NAME}.R$string.X_XX_XXX
            def targetInternalName = generateInternalName(targetPackageName, resourceType)
            def symbolName = symbol.name.replace(".", "_")
            clinit.visitFieldInsn(GETSTATIC, targetInternalName, symbolName, symbol.javaType.desc)
            clinit.visitFieldInsn(PUTSTATIC, internalName, symbolName, symbol.javaType.desc)
            // styleable
            if (symbol instanceof Symbol.StyleableSymbol) {
                symbol.children.forEach { child ->
                    def name =
                            "${symbol.name.replace(".", "_")}_${SymbolUtils.canonicalizeValueResourceName(child)}"
                    clinit.visitFieldInsn(GETSTATIC, targetInternalName, name, "I")
                    clinit.visitFieldInsn(PUTSTATIC, internalName, name, "I")
                }
            }
        }
        clinit.visitInsn(RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()

        classWriter.visitEnd()
        return classWriter.toByteArray()
    }

    private static String generateInternalName(String packageName, ResourceType resourceType) {
        def className = "R" + (resourceType != null ? "\$$resourceType.name" : "")
        return packageName.isEmpty() ? className : "${packageName.replace(".", "/")}/$className"
    }

    /**
     * 兼容AppCompatV26
     * 工程的support-compat为26时，多出的主题会影响找不到资源，导致在api28以上的设备出现NoSuchField错误
     * 应用编译时会根据R.txt和AppCompat的版本判断是否存在
     * 需要在合并时移除V28独有的属性
     */
    private static String[] appCompatV28ThemeList = new String[]{
            "Base_V28_Theme_AppCompat",
            "Base_V28_Theme_AppCompat_Light",
            "RtlOverlay_Widget_AppCompat_PopupMenuItem_Shortcut",
            "RtlOverlay_Widget_AppCompat_PopupMenuItem_SubmenuArrow",
            "RtlOverlay_Widget_AppCompat_PopupMenuItem_Title",
    }

    /**
     * 解析aar的R.txt文件，创建Symbol，合并SymbolTable
     * 部分aar代码硬编码了R.style.AppCompat等属性
     * 直接打包会因为AppCompat不在res的table中，最终在调用时报错NoSuchField
     * 如找不到com.aar.pkg.R$style.Theme_AppCompat
     */
    static SymbolTable parseAarRFile(File rFile, IdProvider idProvider, SymbolTable table) {
        if (rFile.isFile() && rFile.exists()) {
            def builder = new SymbolTable.Builder()
            rFile.readLines().each { line ->
                // "int anim abc_fade_in 0x7f010001"
                def res = line.substring(line.indexOf(" ") + 1)
                // "anim abc_fade_in 0x7f010001"
                res = res.substring(0, res.lastIndexOf(" "))
                // "anim abc_fade_in"
                def split = res.split(" ")
                def resourceType = ResourceType.fromClassName(split[0])
                def symbolName = split[1]
                // STYLE_ONLY && 非本地存在资源 && 非AppCompat28独有属性
                if (resourceType == ResourceType.STYLE && !symbolName.startsWith("aar_name_pattern") && !appCompatV28ThemeList.contains(symbolName)) {
                    try {
                        addIfNotExisting(builder, Symbol.createSymbol(resourceType, symbolName, idProvider, false, false))
                    } catch (Throwable tr) {
                        tr.printStackTrace()
                        throw new RuntimeException(tr)
                    }
                }
            }
            // merge
            return builder.build().merge(table)
        } else {
            throw new IllegalArgumentException("Illegal file $rFile")
        }
    }

    // add new Symbol to SymbolTable
    private static void addIfNotExisting(SymbolTable.Builder builder, Symbol symbol) {
        if (!builder.contains(symbol)) {
            builder.add(symbol)
        }
    }

    /**
     * 合并AAR中的R.txt与工程的R.txt
     * 在generate${FlavorBuildType}RFile前执行，将AAR中不存在的style写入输出的R-def.txt中
     * 后续会根据合并后的R-def.txt生成最终输出的AAR的R.txt
     */
    static def mergeAARV28StyleRFile(File remoteFile, String rText) {
        remoteFile.readLines().each { line ->
            // STYLE_ONLY
            // int style Base_TextAppearance_AppCompat_Tooltip 0x7f150028
            if (line.contains(" style ") && !line.contains("aar_name_pattern")) {
                // "int style Theme_AppCompat 0x7f04008f"
                def res = line.substring(0, line.lastIndexOf(" ")).substring(line.indexOf(" ") + 1)
                // "style Theme_AppCompat"
                def resName = res.substring(res.indexOf(" ") + 1)
                logLevel1("resName:[$resName]")
                // 非AppCompat28独有属性
                if (!appCompatV28ThemeList.contains(resName)) {
                    // 非已存在
                    if (!rText.contains(res)) {
                        rText += "$res\n"
                        logLevel2("add res: $res")
                    }
                }
            }
        }
        return rText
    }

}