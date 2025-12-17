package my.hinoki.booxreader.testutils

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestEpubGenerator {

    fun createMinimalEpub(outputFile: File) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // 1. mimetype (Stored, not compressed)
            val mimetype = ZipEntry("mimetype")
            mimetype.method = ZipEntry.STORED
            mimetype.size = "application/epub+zip".length.toLong()
            mimetype.crc = 0x2CAB616F // Calculated sum for "application/epub+zip"
            zos.putNextEntry(mimetype)
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            // 2. META-INF/container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zos.closeEntry()

            // 3. OEBPS/content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookID" version="2.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                        <dc:title>Test Book</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="BookID" opf:scheme="UUID">urn:uuid:12345</dc:identifier>
                    </metadata>
                    <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="ch1"/>
                    </spine>
                </package>
            """.trimIndent().toByteArray())
            zos.closeEntry()

            // 4. OEBPS/toc.ncx
            zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zos.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <head>
                        <meta name="dtb:uid" content="urn:uuid:12345"/>
                        <meta name="dtb:depth" content="1"/>
                        <meta name="dtb:totalPageCount" content="0"/>
                        <meta name="dtb:maxPageNumber" content="0"/>
                    </head>
                    <docTitle><text>Test Book</text></docTitle>
                    <navMap>
                        <navPoint id="navPoint-1" playOrder="1">
                            <navLabel><text>Chapter 1</text></navLabel>
                            <content src="chapter1.xhtml"/>
                        </navPoint>
                    </navMap>
                </ncx>
            """.trimIndent().toByteArray())
            zos.closeEntry()

            // 5. OEBPS/chapter1.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            zos.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 1</title></head>
                <body><h1>Chapter 1</h1><p>This is the first chapter.</p></body>
                </html>
            """.trimIndent().toByteArray())
            zos.closeEntry()
        }
    }
}
