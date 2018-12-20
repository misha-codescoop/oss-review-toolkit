/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.scanner.scanners

import ch.frankel.slf4k.*
import ch.qos.logback.classic.Level

import com.fasterxml.jackson.databind.JsonNode

import com.here.ort.model.EMPTY_JSON_NODE
import com.here.ort.model.LicenseFinding
import com.here.ort.model.LicenseFindingsMap
import com.here.ort.model.OrtIssue
import com.here.ort.model.Provenance
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.model.jsonMapper
import com.here.ort.scanner.AbstractScannerFactory
import com.here.ort.scanner.HTTP_CACHE_PATH
import com.here.ort.scanner.LocalScanner
import com.here.ort.scanner.ScanException
import com.here.ort.scanner.ScanResultsCache
import com.here.ort.spdx.LICENSE_FILE_NAMES
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.OS
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.log
import com.here.ort.utils.unpack

import okhttp3.Request

import okio.Okio

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.time.Instant
import java.util.SortedSet
import java.util.regex.Pattern

import kotlin.math.absoluteValue

/**
 * A wrapper for [ScanCode](https://github.com/nexB/scancode-toolkit).
 *
 * This scanner can be configured in [ScannerConfiguration.scanner] using the key "ScanCode". It offers the following
 * configuration options:
 *
 * * **"commandLine":** Command line options that modify the result. These are added to the [ScannerDetails] when
 *   looking up results from the [ScanResultsCache]. Defaults to [DEFAULT_CONFIGURATION_OPTIONS].
 * * **"commandLineNonConfig":** Command line options that do not modify the result and should therefore not be
 *   considered in [getConfiguration], like "--processes". Defaults to [DEFAULT_NON_CONFIGURATION_OPTIONS].
 * * **"debugCommandLine":** Debug command line options that modify the result. Only used if the [log] level is set to
 *   [Level.DEBUG]. Defaults to [DEFAULT_DEBUG_CONFIGURATION_OPTIONS].
 * * **"debugCommandLineNonConfig":** Debug command line options that do not modify the result and should therefore not
 *   be considered in [getConfiguration]. Only used if the [log] level is set to [Level.DEBUG]. Defaults to
 *   [DEFAULT_DEBUG_NON_CONFIGURATION_OPTIONS].
 */
class ScanCode(config: ScannerConfiguration) : LocalScanner(config) {
    class Factory : AbstractScannerFactory<ScanCode>() {
        override fun create(config: ScannerConfiguration) = ScanCode(config)
    }

    companion object {
        private const val OUTPUT_FORMAT = "json-pp"
        private const val TIMEOUT = 300

        /**
         * Configuration options that are relevant for [getConfiguration] because they change the result file.
         */
        private val DEFAULT_CONFIGURATION_OPTIONS = listOf(
                "--copyright",
                "--license",
                "--info",
                "--strip-root",
                "--timeout", TIMEOUT.toString()
        )

        /**
         * Configuration options that are not relevant for [getConfiguration] because they do not change the result
         * file.
         */
        private val DEFAULT_NON_CONFIGURATION_OPTIONS = listOf(
                "--processes", Math.max(1, Runtime.getRuntime().availableProcessors() - 1).toString()
        )

        /**
         * Debug configuration options that are relevant for [getConfiguration] because they change the result file.
         */
        private val DEFAULT_DEBUG_CONFIGURATION_OPTIONS = listOf("--license-diag")

        /**
         * Debug configuration options that are not relevant for [getConfiguration] because they do not change the
         * result file.
         */
        private val DEFAULT_DEBUG_NON_CONFIGURATION_OPTIONS = listOf("--verbose")

        private val OUTPUT_FORMAT_OPTION = if (OUTPUT_FORMAT.startsWith("json")) {
            "--$OUTPUT_FORMAT"
        } else {
            "--output-$OUTPUT_FORMAT"
        }

        // Note: The "(File: ...)" part in the patterns below is actually added by our own getResult() function.
        private val UNKNOWN_ERROR_REGEX = Pattern.compile(
                "(ERROR: for scanner: (?<scanner>\\w+):\n)?" +
                        "ERROR: Unknown error:\n.+\n(?<error>\\w+Error)(:|\n)(?<message>.*) \\(File: (?<file>.+)\\)",
                Pattern.DOTALL
        )

        private val TIMEOUT_ERROR_REGEX = Pattern.compile(
                "(ERROR: for scanner: (?<scanner>\\w+):\n)?" +
                        "ERROR: Processing interrupted: timeout after (?<timeout>\\d+) seconds. \\(File: (?<file>.+)\\)"
        )
    }

    override val scannerVersion = "2.9.7"
    override val resultFileExt = "json"

    private val scanCodeConfiguration = config.scanner?.get("ScanCode") ?: emptyMap()

    private val configurationOptions = scanCodeConfiguration["commandLine"]?.split(" ")
            ?: DEFAULT_CONFIGURATION_OPTIONS
    private val nonConfigurationOptions = scanCodeConfiguration["commandLineNonConfig"]?.split(" ")
            ?: DEFAULT_NON_CONFIGURATION_OPTIONS
    private val debugConfigurationOptions = scanCodeConfiguration["debugCommandLine"]?.split(" ")
            ?: DEFAULT_DEBUG_CONFIGURATION_OPTIONS
    private val debugNonConfigurationOptions = scanCodeConfiguration["debugCommandLineNonConfig"]?.split(" ")
            ?: DEFAULT_DEBUG_NON_CONFIGURATION_OPTIONS

    val commandLineOptions by lazy {
        mutableListOf<String>().apply {
            addAll(configurationOptions)
            addAll(nonConfigurationOptions)

            if (log.isEnabledFor(Level.DEBUG)) {
                addAll(debugConfigurationOptions)
                addAll(debugNonConfigurationOptions)
            }
        }.toList()
    }

    override fun command(workingDir: File?) = if (OS.isWindows) "scancode.bat" else "scancode"

    override fun getVersion(dir: File): String {
        // Create a temporary tool to get its version from the installation in a specific directory.
        val cmd = command()
        val tool = object : CommandLineTool {
            override fun command(workingDir: File?) = dir.resolve(cmd).absolutePath
        }

        return tool.getVersion(transform = {
            // "scancode --version" returns a string like "ScanCode version 2.0.1.post1.fb67a181", so simply remove
            // the prefix.
            it.substringAfter("ScanCode version ")
        })
    }

    override fun bootstrap(): File {
        val archive = when {
            // Use the .zip file despite it being slightly larger than the .tar.gz file here as the latter for some
            // reason does not complete to unpack on Windows.
            OS.isWindows -> "v$scannerVersion.zip"
            else -> "v$scannerVersion.tar.gz"
        }

        // Use the source code archive instead of the release artifact from S3 to enable OkHttp to cache the download
        // locally. For details see https://github.com/square/okhttp/issues/4355#issuecomment-435679393.
        val url = "https://github.com/nexB/scancode-toolkit/archive/$archive"

        log.info { "Downloading $this from '$url'... " }

        val request = Request.Builder().get().url(url).build()

        return OkHttpClientHelper.execute(HTTP_CACHE_PATH, request).use { response ->
            val body = response.body()

            if (response.code() != HttpURLConnection.HTTP_OK || body == null) {
                throw IOException("Failed to download $this from $url.")
            }

            if (response.cacheResponse() != null) {
                log.info { "Retrieved $this from local cache." }
            }

            val scannerArchive = createTempFile(suffix = url.substringAfterLast("/"))
            Okio.buffer(Okio.sink(scannerArchive)).use { it.writeAll(body.source()) }

            val unpackDir = createTempDir()
            unpackDir.deleteOnExit()

            log.info { "Unpacking '$scannerArchive' to '$unpackDir'... " }
            scannerArchive.unpack(unpackDir)

            val scannerDir = unpackDir.resolve("scancode-toolkit-$scannerVersion")

            scannerDir
        }
    }

    override fun getConfiguration() =
            configurationOptions.toMutableList().run {
                add(OUTPUT_FORMAT_OPTION)
                if (log.isEnabledFor(Level.DEBUG)) {
                    addAll(debugConfigurationOptions)
                }
                joinToString(" ")
            }

    override fun scanPath(scannerDetails: ScannerDetails, path: File, provenance: Provenance, resultsFile: File)
            : ScanResult {
        val startTime = Instant.now()

        val process = ProcessCapture(
                scannerPath.absolutePath,
                *commandLineOptions.toTypedArray(),
                path.absolutePath,
                OUTPUT_FORMAT_OPTION,
                resultsFile.absolutePath
        )

        val endTime = Instant.now()

        if (process.stderr.isNotBlank()) {
            log.debug { process.stderr }
        }

        val result = getResult(resultsFile)
        val summary = generateSummary(startTime, endTime, result)

        val errors = summary.errors.toMutableList()

        val hasOnlyMemoryErrors = mapUnknownErrors(errors)
        val hasOnlyTimeoutErrors = mapTimeoutErrors(errors)

        with(process) {
            if (isSuccess || hasOnlyMemoryErrors || hasOnlyTimeoutErrors) {
                return ScanResult(provenance, scannerDetails, summary.copy(errors = errors), result)
            } else {
                throw ScanException(errorMessage)
            }
        }

        // TODO: convert json output to spdx
        // TODO: convert json output to html
        // TODO: Add results of license scan to YAML model
    }

    override fun getResult(resultsFile: File): JsonNode {
        return if (resultsFile.isFile && resultsFile.length() > 0L) {
            jsonMapper.readTree(resultsFile)
        } else {
            EMPTY_JSON_NODE
        }
    }

    override fun generateSummary(startTime: Instant, endTime: Instant, result: JsonNode): ScanSummary {
        val fileCount = result["files_count"].intValue()
        val findings = associateFindings(result)
        val errors = mutableListOf<OrtIssue>()

        result["files"]?.forEach { file ->
            val path = file["path"].textValue()
            errors += file["scan_errors"].map {
                OrtIssue(source = javaClass.simpleName, message = "${it.textValue()} (File: $path)")
            }
        }

        return ScanSummary(startTime, endTime, fileCount, findings, errors)
    }

    /**
     * Map messages about unknown errors to a more compact form. Return true if solely memory errors occurred, return
     * false otherwise.
     */
    internal fun mapUnknownErrors(errors: MutableList<OrtIssue>): Boolean {
        if (errors.isEmpty()) {
            return false
        }

        var onlyMemoryErrors = true

        val mappedErrors = errors.map { fullError ->
            UNKNOWN_ERROR_REGEX.matcher(fullError.message).let { matcher ->
                if (matcher.matches()) {
                    val file = matcher.group("file")
                    val error = matcher.group("error")
                    if (error == "MemoryError") {
                        fullError.copy(message = "ERROR: MemoryError while scanning file '$file'.")
                    } else {
                        onlyMemoryErrors = false
                        val message = matcher.group("message").trim()
                        fullError.copy(message = "ERROR: $error while scanning file '$file' ($message).")
                    }
                } else {
                    onlyMemoryErrors = false
                    fullError
                }
            }
        }

        errors.clear()
        errors += mappedErrors.distinctBy { it.message }

        return onlyMemoryErrors
    }

    /**
     * Map messages about timeout errors to a more compact form. Return true if solely timeout errors occurred, return
     * false otherwise.
     */
    internal fun mapTimeoutErrors(errors: MutableList<OrtIssue>): Boolean {
        if (errors.isEmpty()) {
            return false
        }

        var onlyTimeoutErrors = true

        val mappedErrors = errors.map { fullError ->
            TIMEOUT_ERROR_REGEX.matcher(fullError.message).let { matcher ->
                if (matcher.matches() && matcher.group("timeout") == TIMEOUT.toString()) {
                    val file = matcher.group("file")
                    fullError.copy(message = "ERROR: Timeout after $TIMEOUT seconds while scanning file '$file'.")
                } else {
                    onlyTimeoutErrors = false
                    fullError
                }
            }
        }

        errors.clear()
        errors += mappedErrors.distinctBy { it.message }

        return onlyTimeoutErrors
    }

    /**
     * Get the SPDX license id (or a fallback) for a license finding.
     */
    private fun getLicenseId(license: JsonNode): String {
        var name = license["spdx_license_key"].asText()

        if (name.isEmpty()) {
            val key = license["key"].asText()
            name = if (key == "unknown") "NOASSERTION" else "LicenseRef-$key"
        }

        return name
    }

    /**
     * Get the license found in one of the commonly named license files, if any, or an empty string otherwise.
     */
    internal fun getRootLicense(result: JsonNode): String {
        val matchersForLicenseFiles = LICENSE_FILE_NAMES.map {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        }

        val rootLicenseFile = result["files"].singleOrNull { file ->
            val path = file["path"].asText()
            try {
                matchersForLicenseFiles.any { it.matches(Paths.get(path)) }
            } catch (e: InvalidPathException) {
                false
            }
        } ?: return ""

        return rootLicenseFile["licenses"].singleOrNull()?.let { getLicenseId(it) } ?: ""
    }

    /**
     * Return the copyright statements in the vicinity, as specified by [toleranceLines], of [startLine]. The default
     * value of [toleranceLines] is set to 5 which seems to be a good balance between associating findings separated by
     * blank lines but not skipping complete license statements.
     */
    internal fun getClosestCopyrightStatements(copyrights: JsonNode, startLine: Int, toleranceLines: Int = 5):
            SortedSet<String> {
        val closestCopyrights = copyrights.filter {
            (it["start_line"].asInt() - startLine).absoluteValue <= toleranceLines
        }

        // While ScanCode 2.9.2 was still using "statements", version 2.9.7 is using "value".
        return closestCopyrights.flatMap {
            it["statements"] ?: listOf(it["value"])
        }.map { it.asText() }.toSortedSet()
    }

    /**
     * Associate copyright findings to license findings within a single file.
     */
    private fun associateFileFindings(licenses: JsonNode, copyrights: JsonNode, rootLicense: String = ""):
            LicenseFindingsMap {
        val copyrightsForLicenses = sortedMapOf<String, MutableSet<String>>()

        // While ScanCode 2.9.2 was still using "statements", version 2.9.7 is using "value".
        val allCopyrightStatements = copyrights.flatMap {
            it["statements"] ?: listOf(it["value"])
        }.map { it.asText() }.toSortedSet()

        when (licenses.size()) {
            0 -> {
                // If there is no license finding but copyright findings, associate them with the root license, if any.
                if (allCopyrightStatements.isNotEmpty() && rootLicense.isNotEmpty()) {
                    copyrightsForLicenses[rootLicense] = allCopyrightStatements
                }
            }

            1 -> {
                // If there is only a single license finding, associate all copyright findings with that license.
                val licenseId = getLicenseId(licenses.first())
                copyrightsForLicenses[licenseId] = allCopyrightStatements
            }

            else -> {
                // If there are multiple license findings in a single file, search for the closest copyright statements
                // for each of these, if any.
                licenses.forEach {
                    val licenseId = getLicenseId(it)
                    val licenseStartLine = it["start_line"].asInt()
                    val closestCopyrights = getClosestCopyrightStatements(copyrights, licenseStartLine)
                    copyrightsForLicenses.getOrPut(licenseId) { sortedSetOf() } += closestCopyrights
                }
            }
        }

        return copyrightsForLicenses
    }

    /**
     * Associate copyright findings to license findings throughout the whole result.
     */
    internal fun associateFindings(result: JsonNode): SortedSet<LicenseFinding> {
        val copyrightsForLicenses = sortedMapOf<String, SortedSet<String>>()
        val rootLicense = getRootLicense(result)

        result["files"].forEach { file ->
            val licenses = file["licenses"] ?: EMPTY_JSON_NODE
            val copyrights = file["copyrights"] ?: EMPTY_JSON_NODE
            val findings = associateFileFindings(licenses, copyrights, rootLicense)
            findings.forEach { license, copyrightsForLicense ->
                copyrightsForLicenses.getOrPut(license) { sortedSetOf() } += copyrightsForLicense
            }
        }

        return copyrightsForLicenses.map { (license, copyrights) ->
            LicenseFinding(license, copyrights)
        }.toSortedSet()
    }
}
