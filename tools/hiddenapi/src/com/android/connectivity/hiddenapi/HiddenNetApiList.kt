/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("HiddenNetApiList")

package com.android.connectivity.hiddenapi

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.SequenceInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

private data class Options(val clobber: Boolean)
private data class Inputs(val flagsFile: File, val moduleApiStubs: File, val outFile: File)

fun main(args: Array<String>) {
    val (options, arguments) = args.partition { it.startsWith("-c") }
    val clobber = options.contains("-c")
    assertTrue(
        arguments.size == 3,
        "Usage: EXECNAME <-c: clobber> flagsFile moduleApiStubsFile outFile"
    )
    val hiddenApi =
        makeHiddenApiSet(flagsFile = File(arguments[0]), moduleApiStubs = File(arguments[1]))

    val outFile = File(arguments[2])
    if (!clobber) {
        assertTrue(!outFile.exists(), "$outFile already exists")
    }

    BufferedWriter(FileWriter(outFile)).use { writer ->
        writer.appendLine("apis = [")
        hiddenApi.forEach {
            writer.appendLine("  '$it',")
        }
        writer.appendLine("]")
    }
}

private fun makeHiddenApiSet(flagsFile: File, moduleApiStubs: File): Set<String> {
    assertTrue(flagsFile.exists(), "Could not find flags file at $flagsFile")

    // Example method:
    // Landroid/net/NetworkTemplate;->getBackwardsCompatibleMatchRule(I)I,blocked
    // Example constant:
    // Landroid/net/OemNetworkPreferences;->OEM_NETWORK_PREFERENCE_MAX:I,blocked
    val hiddenApiRegex = Regex("""(L.+?;)->(.+)(?:(\(.*\))|:)(.+?),(.+)""")
    val accessibleTags = listOf("core-platform-api", "public-api", "system-api")
    val hiddenApis = mutableMapOf<String, String>()
    BufferedReader(FileReader(flagsFile)).use { reader ->
        reader.forEachLine {
            val match = assertNotNull(
                hiddenApiRegex.matchEntire(it),
                    "$it did not match regex: $hiddenApiRegex"
            )
            val flags = match.groups[5]?.value?.split(",") ?: emptyList<String>()
            if (flags.intersect(accessibleTags).isNotEmpty()) return@forEachLine // continue
            val symbolClass = assertNotNull(match.groups[1]?.value)
            val symbolName = assertNotNull(match.groups[2]?.value)
            // Constants do not have arguments so arguments may be null
            val arguments = match.groups[3]?.value
            val returnType = assertNotNull(match.groups[4]?.value)

            // dexdump format is, example method:
            // Landroid/net/IpPrefix;.contains:(Ljava/net/InetAddress;)Z
            // Example constant:
            // Landroid/net/LinkAddress;.CREATOR:Landroid/os/Parcelable$Creator;
            hiddenApis["$symbolClass.$symbolName:${arguments ?: ""}$returnType"] = it
        }
    }

    // Module API is also considered "blocked". Remove them from the list based on the stubs.
    assertTrue(moduleApiStubs.exists(), "Could not find API stubs file at $moduleApiStubs")

    val moduleApiDump = ProcessBuilder(
        "dexdump",
        "-l",
        "xml",
        "-e",
        "-n",
        moduleApiStubs.absolutePath.toString()
    ).start()

    val parsedHandler = moduleApiDump.inputStream.use { stream ->
        DexParserHandler().also {
            SAXParserFactory.newInstance().newSAXParser().parse(stream, it)
        }
    }
    hiddenApis.keys.removeAll(parsedHandler.parsedSymbols)

    return hiddenApis.values.toSet()
}

class DexParserHandler : DefaultHandler() {
    private var pkg = ""
    private var clazz = ""

    private var currentElementName = ""
    private var currentElementType = ""
    private val currentParameters = mutableListOf<String>()

    val parsedSymbols = mutableSetOf<String>()

    override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes
    ) {
        when (qName) {
            "package" -> pkg = attributes.getValue("name")
            "class" -> clazz = attributes.getValue("name")
            "field" -> {
                currentElementName = attributes.getValue("name")
                currentElementType = toInternalType(attributes.getValue("type"))
            }
            "method" -> {
                currentElementName = attributes.getValue("name")
                currentElementType = toInternalType(attributes.getValue("return"))
                currentParameters.clear()
            }
            "constructor" -> {
                currentElementName = "<init>"
                currentElementType = toInternalType("void")
                currentParameters.clear()
            }
            "parameter" -> {
                currentParameters.add(toInternalType(attributes.getValue("type")))
            }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        when (qName) {
            "field" -> {
                parsedSymbols.add(
                    toInternalType("$pkg.$clazz") +
                        ".$currentElementName:$currentElementType"
                )
            }
            "method", "constructor" -> {
                val parameters = currentParameters.joinToString(separator = "")
                parsedSymbols.add(
                    toInternalType("$pkg.$clazz") +
                        ".$currentElementName:($parameters)$currentElementType"
                )
            }
        }
    }

    /**
     * Convert from dexdump XML output type (java.lang.String or boolean[]) to internal type
     * (Ljava/lang/String; or [Z)
     */
    private fun toInternalType(type: String): String {
        if (type.endsWith("[]")) {
            return "[" + toInternalType(type.substring(0, type.length - 2))
        }
        return when (type) {
            "void" -> "V"
            "byte" -> "B"
            "char" -> "C"
            "double" -> "D"
            "float" -> "F"
            "int" -> "I"
            "long" -> "J"
            "short" -> "S"
            "boolean" -> "Z"
            else -> 'L' + type.replace('.', '/') + ';'
        }
    }
}
