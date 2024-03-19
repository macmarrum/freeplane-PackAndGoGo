// @ExecutionModes({on_single_node="/menu_bar/file"})
/* Copyright (C) 2011-2012 Volker Boerchers
 * Copyright (C) 2023, 2024   macmarrum
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


import org.freeplane.api.MindMap
import org.freeplane.core.ui.CaseSensitiveFileNameExtensionFilter
import org.freeplane.core.util.LogUtils
import org.freeplane.features.map.clipboard.MapClipboardController
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.MapWriter.Mode
import org.freeplane.features.mode.Controller

import javax.swing.*
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private byte[] getZipBytes(Map<File, String> fileToPathInZipMap, File mapFile, byte[] mapBytes) {
    def byteArrayOutputStream = new ByteArrayOutputStream()
    ZipOutputStream zipOutput = new ZipOutputStream(byteArrayOutputStream)
    fileToPathInZipMap.each { file, path ->
        zipOutput = addZipEntry(zipOutput, file, path)
    }
    logger.info("zipMap: added ${mapFile.name}")
    ZipEntry entry = new ZipEntry(mapFile.name)
    entry.time = mapFile.lastModified()
    zipOutput.putNextEntry(entry)
    zipOutput << mapBytes
    zipOutput.close()
    return byteArrayOutputStream.toByteArray()
}

private ZipOutputStream addZipEntry(ZipOutputStream zipOutput, File file, String path) {
    if (file.isDirectory() && !path.endsWith('/')) {
        path += "/"
    }
    logger.info("zipMap: added $path")
    ZipEntry entry = new ZipEntry(path)
    entry.time = file.lastModified()
    zipOutput.putNextEntry(entry)
    if (file.isFile()) {
        def fileInputStream = new FileInputStream(file)
        zipOutput << fileInputStream
        fileInputStream.close()
    }
    return zipOutput
}

private String getPathInZip(File file, String dependenciesDir, Map<File, String> fileToPathInZipMap) {
    def mappedPath = fileToPathInZipMap[file]
    if (mappedPath)
        return mappedPath
    def path = "${dependenciesDir}/${file.name}"
    if (file.isDirectory())
        path += '/'
    // TODO: include the parent's name in the path for duplicates to make them more readable
    while (contains(fileToPathInZipMap.values(), path)) {
        // if multiple file with the same name (but different directories) are referenced append something to the path
        path = path.replaceFirst('(\\.\\w+)?$', '1$1')
        logger.info("zipMap: mapped $file to $path")
    }
    return path
}

// the inline version did not work - Groovy bug?
static boolean contains(Collection collection, String path) {
    return collection.contains(path)
}

private static byte[] getBytes(MapModel map) {
    StringWriter stringWriter = new StringWriter(4 * 1024)
    BufferedWriter out = new BufferedWriter(stringWriter)
    def mapWriter = Controller.getCurrentModeController().getMapController().getMapWriter()
    try { // since 1.11.8 (2f8e7017)
        mapWriter.writeMapAsXml(map, out, Mode.FILE, MapClipboardController.CopiedNodeSet.ALL_NODES, false)
    } catch (MissingMethodException) { // till 1.11.8 (2f8e7017)
        mapWriter.writeMapAsXml(map, out, Mode.FILE, true, false)
    }
    return stringWriter.buffer.toString().getBytes(StandardCharsets.UTF_8)
}

private boolean confirmOverwrite(File file) {
    def title = getText('Create zip file')
    def question = textUtils.format('file_already_exists', file)
    int selection = JOptionPane.showConfirmDialog(ui.frame, question, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
    return selection == JOptionPane.YES_OPTION
}

private File askForZipFile(File zipFile) {
    def zipFileFilter = new CaseSensitiveFileNameExtensionFilter('zip', 'ZIP files')
    def chooser = new JFileChooser(fileSelectionMode: JFileChooser.FILES_ONLY, fileFilter: zipFileFilter, selectedFile: zipFile)
    if (chooser.showSaveDialog() == JFileChooser.APPROVE_OPTION) {
        if (!chooser.selectedFile.exists() || confirmOverwrite(chooser.selectedFile))
            return chooser.selectedFile
    }
    return null
}

/**
 * It is used to collect unique file paths.
 * Canonical, so that ../ and ./ do not lead to different paths for the same file.
 * @return canonical file for the URI
 */
static File getUriAsCanonicalFile(File mapDir, URI uri) {
    try {
        if (uri == null)
            return null
        def scheme = uri.scheme
        if (scheme == null || scheme == 'file') {
            // uri.path is null when e.g. 'file:abc.txt', therefore uri.schemeSpecificPart
            def path = uri.path ?: uri.schemeSpecificPart
            def file = new File(path)
            return file.absolute ? file.canonicalFile : new File(mapDir, path).canonicalFile
        }
        return new File(uri).canonicalFile
    } catch (Exception e) {
        LogUtils.info("link is not a file uri: $e")
        return null
    }
}

// searches the map for file references that have to be mapped to a file in the zip
private createFileToPathInZipMap(MindMap newMindMap, String dependenciesDir) {
    File mapDir = node.mindMap.file.parentFile
    // closure, re-usable for text, details and notes
    def handleHtmlText = { String text, Map<File, String> map ->
        if (!text)
            return text
        // regex needs to cover single or double quotes surrounding the url
        // special case: href="abc.mm#at(/**/'A%201%20b')"
        def links = ~/(href|src)=(["'])(.+)\2/
        def m = links.matcher(text)
        // optimize for the regular case: no StringBuffer et al if there is no need for it
        if (m.find()) {
            def buffer = new StringBuffer()
            for (; ;) {
                def ref = m.group(3)
                def xpath = getMappedPath(ref, map, mapDir, dependenciesDir)
                if (xpath) {
                    logger.info("patching inline reference ${m.group(0)}")
                    m.appendReplacement(buffer, "${m.group(1)}=${m.group(2)}${xpath}${m.group(2)}")
                } else {
                    m.appendReplacement(buffer, m.group(0))
                }
                // Groovy has no do..while loop
                if (!m.find())
                    break
            }
            m.appendTail(buffer)
            return buffer.toString()
        }
        return text
    }
    def fileToPathInZipMap = newMindMap.root.findAll().inject(new LinkedHashMap<File, String>()) { map, node ->
        def path
        // == link
        path = getMappedPath(node.link.uri, map, mapDir, dependenciesDir)
        if (path)
            node.link.text = path
        // == external object
        path = getMappedPath(node.externalObject.uri, map, mapDir, dependenciesDir)
        if (path) {
            // when setting a string, externalObject.uri goes via new URL(str).toURI(), which requires protocol, hence fails for relative paths
            node.externalObject.uri = URI.create(path)
        }
        // == attributes
        def attributes = node.attributes
        attributes.eachWithIndex { value, i ->
            if (value instanceof URI) {
                path = getMappedPath(value, map, mapDir, dependenciesDir)
                if (path)
                    attributes.set(i, new URI(path))
            }
        }
        def nodeText = node.text
        if (htmlUtils.isHtmlNode(nodeText))
            node.text = handleHtmlText(nodeText, map)
        def detailsText = node.detailsText
        if (detailsText)
            node.detailsText = handleHtmlText(detailsText, map)
        def noteText = node.noteText
        if (noteText)
            node.noteText = handleHtmlText(noteText, map)

        return map
    }
    return fileToPathInZipMap
}

private String getMappedPath(Object uriObject, Map<File, String> fileToPathInZipMap, File mapDir, String dependenciesDir) {
    if (!uriObject)
        return null
    URI uri = (uriObject instanceof URI) ? uriObject : new URI(uriObject.toString())
    def f = getUriAsCanonicalFile(mapDir, uri)
    if (f != null && f.exists()) {
        def path = getPathInZip(f, dependenciesDir, fileToPathInZipMap)
        fileToPathInZipMap[f] = path
        path = urlEncode(path)
        return uri.rawFragment ? path + '#' + uri.rawFragment : path
    }
    return null
}

private static urlEncode(String string) {
    def uri = new URI(null, string, null)
    return uri.rawPath
}

private static String getText(String key, Object... parameters) {
    if (parameters)
        return MessageFormat.format(key, parameters)
    return key
}

boolean zipMap(File file) {
    if (file == null) {
        ui.errorMessage(getText('You have to save this map first'))
        return
    }
    if (!node.mindMap.isSaved()) {
        def question = getText('Do you want to save {0} first?', node.mindMap.name)
        def title = getText('Create zip file')
        final int selection = JOptionPane.showConfirmDialog(ui.frame, question, title, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
        if (selection == JOptionPane.YES_OPTION)
            node.mindMap.save(false)
        else if (selection == JOptionPane.CANCEL_OPTION)
            return
    }
    def baseName = file.name.replaceFirst('\\.mm', '')
    def zipFile = askForZipFile(new File(file.parentFile, baseName + '.zip'))
    if (zipFile == null)
        return
    def dependenciesDir = "${baseName}-files"
    MindMap newMindMap = c.mapLoader(file).unsetMapLocation().mindMap
    if (newMindMap == null) {
        ui.errorMessage(getText('Can not create a copy of {0}', file))
        return
    }
    // original file -> zip file name
    def fileToPathInZipMap = createFileToPathInZipMap(newMindMap, dependenciesDir)
    // add the map itself
    def bytes = getZipBytes(fileToPathInZipMap, file, getBytes(newMindMap.delegate))

    zipFile.bytes = bytes
    logger.info("zipMap: wrote ${zipFile.absolutePath}")
    c.statusInfo = getText('wrote {0}', zipFile.absolutePath)
}

///////////////////// MAIN //////////////////////////
zipMap(node.mindMap.file)
