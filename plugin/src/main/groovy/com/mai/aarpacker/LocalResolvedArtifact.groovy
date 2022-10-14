package com.mai.aarpacker

import com.google.common.io.Files
import groovy.transform.CompileStatic
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedModuleVersion
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.ivyservice.modulecache.dynamicversions.DefaultResolvedModuleVersion
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
import java.io.File

@CompileStatic
@SuppressWarnings("UnstableApiUsage")
class LocalResolvedArtifact implements ResolvedArtifact {

    private final File mFile

    LocalResolvedArtifact(File file) {
        this.mFile = file
    }

    @Override
    File getFile() {
        return mFile
    }

    @Override
    ResolvedModuleVersion getModuleVersion() {
        return new DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId("unspecified", name, "unspecified"))
    }

    @Override
    String getName() {
        return Files.getNameWithoutExtension(mFile.name)
    }

    @Override
    String getType() {
        return Files.getFileExtension(mFile.name)
    }

    @Override
    String getExtension() {
        return Files.getFileExtension(mFile.name)
    }

    @Override
    String getClassifier() {
        return null
    }

    @Override
    ComponentArtifactIdentifier getId() {
        return new OpaqueComponentArtifactIdentifier(mFile)
    }

    @Override
    String toString() {
        return "$name (${mFile.absolutePath})"
    }

}