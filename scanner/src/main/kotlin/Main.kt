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

package com.here.ort.scanner

import ch.frankel.slf4k.*

import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException

import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.AnalyzerResult
import com.here.ort.model.AnalyzerResultBuilder
import com.here.ort.model.Identifier
import com.here.ort.model.OutputFormat
import com.here.ort.model.ProjectScanScopes
import com.here.ort.model.Provenance
import com.here.ort.model.ScanRecord
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanResultContainer
import com.here.ort.model.ScanSummary
import com.here.ort.model.VcsInfo
import com.here.ort.model.mapper
import com.here.ort.model.yamlMapper
import com.here.ort.scanner.scanners.ScanCode
import com.here.ort.utils.PARAMETER_ORDER_HELP
import com.here.ort.utils.PARAMETER_ORDER_LOGGING
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL
import com.here.ort.utils.collectMessages
import com.here.ort.utils.log
import com.here.ort.utils.printStackTrace
import com.here.ort.utils.showStackTrace

import java.io.File
import java.time.Instant

import kotlin.system.exitProcess

/**
 * The main entry point of the application.
 */
object Main {
    const val TOOL_NAME = "scanner"
    const val HTTP_CACHE_PATH = "$TOOL_NAME/cache/http"

    private class ScannerConverter : IStringConverter<Scanner> {
        override fun convert(scannerName: String): Scanner {
            // TODO: Consider allowing to enable multiple scanners (and potentially running them in parallel).
            return Scanner.ALL.find { it.toString().equals(scannerName, true) }
                    ?: throw ParameterException("The scanner must be one of ${Scanner.ALL}.")
        }
    }

    @Parameter(description = "The dependencies analysis file to use. Source code will be downloaded automatically if " +
            "needed. This parameter and --input-path are mutually exclusive.",
            names = ["--dependencies-file", "-d"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var dependenciesFile: File? = null

    @Parameter(description = "The input directory or file to scan. This parameter and --dependencies-file are " +
            "mutually exclusive.",
            names = ["--input-path", "-i"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var inputPath: File? = null

    @Parameter(description = "The list of scopes that shall be scanned. Works only with the " +
            "--dependencies-file parameter. If empty, all scopes are scanned.",
            names = ["--scopes"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var scopesToScan = listOf<String>()

    @Parameter(description = "The output directory to store the scan results in.",
            names = ["--output-dir", "-o"],
            required = true,
            order = PARAMETER_ORDER_MANDATORY)
    @Suppress("LateinitUsage")
    private lateinit var outputDir: File

    @Parameter(description = "The output directory for downloaded source code. Defaults to <output-dir>/downloads.",
            names = ["--download-dir"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var downloadDir: File? = null

    @Parameter(description = "The scanner to use.",
            names = ["--scanner", "-s"],
            converter = ScannerConverter::class,
            order = PARAMETER_ORDER_OPTIONAL)
    private var scanner: Scanner = ScanCode

    @Parameter(description = "The path to the configuration file.",
            names = ["--config", "-c"],
            order = PARAMETER_ORDER_OPTIONAL)
    @Suppress("LateinitUsage")
    private var configFile: File? = null

    @Parameter(description = "The list of file formats for the summary files.",
            names = ["--summary-format", "-f"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var summaryFormats = listOf(OutputFormat.YAML)

    @Parameter(description = "Enable info logging.",
            names = ["--info"],
            order = PARAMETER_ORDER_LOGGING)
    private var info = false

    @Parameter(description = "Enable debug logging and keep any temporary files.",
            names = ["--debug"],
            order = PARAMETER_ORDER_LOGGING)
    private var debug = false

    @Parameter(description = "Print out the stacktrace for all exceptions.",
            names = ["--stacktrace"],
            order = PARAMETER_ORDER_LOGGING)
    private var stacktrace = false

    @Parameter(description = "Display the command line help.",
            names = ["--help", "-h"],
            help = true,
            order = PARAMETER_ORDER_HELP)
    private var help = false

    /**
     * The entry point for the application.
     *
     * @param args The list of application arguments.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val jc = JCommander(this)
        jc.parse(*args)
        jc.programName = TOOL_NAME

        if (info) {
            log.level = ch.qos.logback.classic.Level.INFO
        }

        if (debug) {
            log.level = ch.qos.logback.classic.Level.DEBUG
        }

        if (help) {
            jc.usage()
            exitProcess(1)
        }

        // Make the parameter globally available.
        printStackTrace = stacktrace

        require((dependenciesFile == null) != (inputPath == null)) {
            "Either --dependencies-file or --input-path must be specified."
        }

        require(!outputDir.exists()) {
            "The output directory '${outputDir.absolutePath}' must not exist yet."
        }

        downloadDir?.let {
            require(!it.exists()) {
                "The download directory '${it.absolutePath}' must not exist yet."
            }
        }

        if (configFile != null) {
            ScanResultsCache.configure(yamlMapper.readTree(configFile))
        }

        println("Using scanner '$scanner'.")

        val scanRecord = dependenciesFile?.let { scanDependenciesFile(it) } ?: scanInputPath(inputPath!!)

        writeScanRecord(outputDir, scanRecord)
    }

    private fun scanDependenciesFile(dependenciesFile: File): ScanRecord {
        require(dependenciesFile.isFile) {
            "Provided path is not a file: ${dependenciesFile.absolutePath}"
        }

        val analyzerResult = dependenciesFile.mapper().readValue(dependenciesFile, AnalyzerResult::class.java)

        // Add the projects as packages to scan.
        val projectPackages = analyzerResult.projects.map { it.toPackage().toCuratedPackage() }

        val projectScanScopes = if (scopesToScan.isNotEmpty()) {
            println("Limiting scan to scopes: $scopesToScan")

            analyzerResult.projects.map { project ->
                project.scopes.map { it.name }.partition { it in scopesToScan }.let {
                    ProjectScanScopes(project.id, it.first.toSortedSet(), it.second.toSortedSet())
                }
            }
        } else {
            analyzerResult.projects.map {
                val scopes = it.scopes.map { it.name }
                ProjectScanScopes(it.id, scopes.toSortedSet(), sortedSetOf())
            }
        }.toSortedSet()

        val packagesToScan = if (scopesToScan.isNotEmpty()) {
            projectPackages + analyzerResult.packages.filter { pkg ->
                analyzerResult.projects.any { it.scopes.any { it.name in scopesToScan && pkg.pkg in it } }
            }
        } else {
            projectPackages + analyzerResult.packages
        }.toSortedSet()

        val results = scanner.scan(packagesToScan.map { it.pkg }, outputDir, downloadDir)
        results.forEach { pkg, result ->
            // TODO: Make output format configurable.
            File(outputDir, "scanResults/${pkg.id.toPath()}/scan-results.yml").also {
                yamlMapper.writeValue(it, ScanResultContainer(pkg.id, result))
            }

            println("Declared licenses for '${pkg.id}': ${pkg.declaredLicenses.joinToString()}")
            println("Detected licenses for '${pkg.id}': ${result.flatMap { it.summary.licenses }.joinToString()}")
        }

        val resultContainers = results.map { (pkg, results) ->
            // Remove the raw results from the scan results to reduce the size of the scan result.
            // TODO: Consider adding an option to keep the raw results.
            ScanResultContainer(pkg.id, results.map { it.copy(rawResult = null) })
        }.toSortedSet()

        return ScanRecord(analyzerResult, projectScanScopes, resultContainers, ScanResultsCache.stats)
    }

    private fun scanInputPath(inputPath: File): ScanRecord {
        require(inputPath.exists()) {
            "Provided path does not exist: ${inputPath.absolutePath}"
        }

        require(scanner is LocalScanner) {
            "To scan local files the chosen scanner must be a local scanner."
        }

        val localScanner = scanner as LocalScanner

        println("Scanning path '${inputPath.absolutePath}'...")

        val result = try {
            localScanner.scanPath(inputPath, outputDir).also {
                println("Detected licenses for path '${inputPath.absolutePath}':" +
                        "${it.summary.licenses.joinToString()}")
            }
        } catch (e: ScanException) {
            e.showStackTrace()

            log.error { "Could not scan path '${inputPath.absolutePath}': ${e.message}" }

            val now = Instant.now()
            val summary = ScanSummary(now, now, 0, sortedMapOf(), e.collectMessages().toSortedSet())
            ScanResult(Provenance(now), localScanner.getDetails(), summary)
        }

        val vcsInfo = VersionControlSystem.forDirectory(inputPath.takeIf { it.isDirectory }
                ?: inputPath.parentFile)?.getInfo(inputPath) ?: VcsInfo.EMPTY
        val analyzerResult = AnalyzerResultBuilder(true, vcsInfo).build()

        val scanResultContainer = ScanResultContainer(Identifier("", "", inputPath.absolutePath, ""), listOf(result))

        return ScanRecord(analyzerResult, sortedSetOf(), sortedSetOf(scanResultContainer), ScanResultsCache.stats)
    }

    private fun writeScanRecord(outputDirectory: File, scanRecord: ScanRecord) {
        summaryFormats.forEach { format ->
            val scanRecordFile = File(outputDirectory, "scan-record.${format.fileExtension}")
            println("Writing scan record to ${scanRecordFile.absolutePath}.")
            format.mapper.writerWithDefaultPrettyPrinter().writeValue(scanRecordFile, scanRecord)
        }
    }
}
