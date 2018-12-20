/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
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

package com.here.ort.reporter.reporters

import ch.frankel.slf4k.*

import com.here.ort.model.LicenseFindingsMap
import com.here.ort.model.OrtResult
import com.here.ort.model.config.CopyrightGarbage
import com.here.ort.model.processStatements
import com.here.ort.model.removeGarbage
import com.here.ort.reporter.Reporter
import com.here.ort.reporter.ResolutionProvider
import com.here.ort.spdx.SpdxLicenseMapping
import com.here.ort.spdx.getLicenseText
import com.here.ort.utils.ScriptRunner
import com.here.ort.utils.log
import com.here.ort.utils.zipWithDefault

import java.io.File
import java.util.SortedSet

class NoticeReporter : Reporter() {
    companion object {
        private const val NOTICE_SEPARATOR = "\n----\n\n"
        private const val NOTICE_FILE_NAME = "NOTICE"
    }

    data class NoticeReport(
            val headers: List<String>,
            val findings: LicenseFindingsMap,
            val footers: List<String>
    )

    class PostProcessor(ortResult: OrtResult, noticeReport: NoticeReport, copyrightGarbage: CopyrightGarbage)
        : ScriptRunner() {
        override val preface = """
            import com.here.ort.model.*
            import com.here.ort.model.config.*
            import com.here.ort.spdx.*
            import com.here.ort.utils.*
            import com.here.ort.reporter.reporters.NoticeReporter.NoticeReport

            import java.util.*

            // Input:
            val ortResult = bindings["ortResult"] as OrtResult
            val noticeReport = bindings["noticeReport"] as NoticeReport
            val copyrightGarbage = bindings["copyrightGarbage"] as CopyrightGarbage

            var headers = noticeReport.headers
            var findings = noticeReport.findings
            var footers = noticeReport.footers

        """.trimIndent()

        override val postface = """

            // Output:
            NoticeReport(headers, findings, footers)
        """.trimIndent()

        init {
            engine.put("ortResult", ortResult)
            engine.put("noticeReport", noticeReport)
            engine.put("copyrightGarbage", copyrightGarbage)
        }

        override fun run(script: String): NoticeReport = super.run(script) as NoticeReport
    }

    override fun generateReport(
            ortResult: OrtResult,
            resolutionProvider: ResolutionProvider,
            copyrightGarbage: CopyrightGarbage,
            outputDir: File,
            postProcessingScript: String?
    ): File {
        requireNotNull(ortResult.scanner) {
            "The provided ORT result file does not contain a scan result."
        }

        val licenseFindings = getLicenseFindings(ortResult)

        // Remove all non-SPDX licenses because we can currently only get the license texts for SPDX licenses.
        val spdxLicenseFindings = mapSpdxLicenses(licenseFindings)

        val header = if (spdxLicenseFindings.isEmpty()) {
            "This project neither contains or depends on any third-party software components.\n"
        } else {
            "This project contains or depends on third-party software components pursuant to the following licenses:\n"
        }

        val noticeReport = if (postProcessingScript != null) {
            PostProcessor(
                    ortResult,
                    NoticeReport(listOf(header), spdxLicenseFindings, emptyList()),
                    copyrightGarbage
            ).run(postProcessingScript)
        } else {
            val processedFindings = spdxLicenseFindings.removeGarbage(copyrightGarbage).processStatements()
            NoticeReport(listOf(header), processedFindings, emptyList())
        }

        val outputFile = File(outputDir, NOTICE_FILE_NAME)
        writeNoticeReport(noticeReport, outputFile)

        return outputFile
    }

    private fun getLicenseFindings(ortResult: OrtResult): LicenseFindingsMap {
        val excludes = ortResult.repository.config.excludes
        val analyzerResult = ortResult.analyzer!!.result
        val scanRecord = ortResult.scanner!!.results

        val licenseFindings = sortedMapOf<String, MutableSet<String>>()

        scanRecord.scanResults.forEach { container ->
            if (excludes?.isExcluded(container.id, analyzerResult) != true) {
                container.results.forEach { result ->
                    result.summary.licenseFindingsMap.forEach { (license, copyrights) ->
                        licenseFindings.getOrPut(license) { mutableSetOf() } += copyrights
                    }
                }
            }
        }

        return licenseFindings
    }

    private fun mapSpdxLicenses(licenseFindings: LicenseFindingsMap): LicenseFindingsMap {
        var result = mapOf<String, SortedSet<String>>()

        licenseFindings.forEach { finding ->
            SpdxLicenseMapping.map(finding.key).map { spdxLicense ->
                sortedMapOf(spdxLicense.id to finding.value)
            }.forEach {
                result = result.zipWithDefault(it, sortedSetOf()) { left, right ->
                    (left + right).toSortedSet()
                }
            }
        }

        return result.toSortedMap()
    }

    private fun writeNoticeReport(noticeReport: NoticeReport, outputFile: File) {
        log.info { "Writing $NOTICE_FILE_NAME file to '${outputFile.absolutePath}'." }

        val headers = noticeReport.headers.joinToString(NOTICE_SEPARATOR)
        outputFile.appendText(headers)

        if (noticeReport.findings.isNotEmpty()) {
            val findings = noticeReport.findings.map { (license, copyrights) ->
                val notice = buildString {
                    // Note: Do not use appendln() here as that would write out platform-native line endings, but we
                    // want to normalize on Unix-style line endings for consistency.
                    copyrights.forEach { copyright ->
                        append("$copyright\n")
                    }

                    if (copyrights.isNotEmpty()) append("\n")

                    val licenseText = getLicenseText(license, true)
                    append("$licenseText\n")
                }

                // Trim lines and remove consecutive blank lines as the license text formatting in SPDX JSON files is
                // broken, see https://github.com/spdx/LicenseListPublisher/issues/30.
                var previousLine = ""
                val trimmedNoticeLines = notice.lines().mapNotNull { line ->
                    val trimmedLine = line.trim()
                    trimmedLine.takeIf { it.isNotBlank() || previousLine.isNotBlank() }
                            .also { previousLine = trimmedLine }
                }

                trimmedNoticeLines.joinToString("\n")
            }.joinToString(separator = NOTICE_SEPARATOR, prefix = NOTICE_SEPARATOR)
            outputFile.appendText(findings)
        }

        if (noticeReport.footers.isNotEmpty()) {
            val footers = noticeReport.footers.joinToString(separator = NOTICE_SEPARATOR, prefix = NOTICE_SEPARATOR)
            outputFile.appendText(footers)
        }
    }
}
