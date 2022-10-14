package com.mai.aarpacker

import groovy.transform.CompileStatic

import static com.mai.aarpacker.Utils.logLevel2

@CompileStatic
class AarPackerExtension {
    boolean verboseLog = false
    boolean ignoreAndroidSupport = true
    private List<String> ignoreDependencies = new ArrayList<>()

    void ignoreDependencies(String... dependency) {
        logLevel2("ignore dependencies: $dependency")
        for (String dep : dependency) {
            if (!ignoreDependencies.contains(dep))
                ignoreDependencies.add(dep)
        }
    }

    List<String> getIgnoreDependencies() {
        return ignoreDependencies
    }
}