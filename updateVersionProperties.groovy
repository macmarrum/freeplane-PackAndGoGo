import groovy.transform.SourceURI

import java.nio.file.Paths
import java.time.LocalDate

@SourceURI URI sourceUri
def me = Paths.get(sourceUri)
def versionPropertiesFile = me.parent.resolve('version.properties')

def version = LocalDate.now().format('Y.MM.dd')

def template = """\
version=${version}
downloadUrl=https://github.com/macmarrum/freeplane-PackAndGoGo/releases/download/v${version}/PackAndGoGo-${version}.addon.mm
freeplaneVersionFrom=1.9.1
"""
println template
versionPropertiesFile.text = template
println "saved to ${versionPropertiesFile}"
