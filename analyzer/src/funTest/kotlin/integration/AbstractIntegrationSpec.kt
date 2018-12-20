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

package com.here.ort.analyzer.integration

import com.here.ort.analyzer.ManagedProjectFiles
import com.here.ort.analyzer.PackageManager
import com.here.ort.downloader.Downloader
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.Identifier
import com.here.ort.model.Package
import com.here.ort.utils.safeDeleteRecursively
import com.here.ort.utils.test.DEFAULT_ANALYZER_CONFIGURATION
import com.here.ort.utils.test.DEFAULT_REPOSITORY_CONFIGURATION
import com.here.ort.utils.test.ExpensiveTag
import com.here.ort.utils.test.USER_DIR

import io.kotlintest.Description
import io.kotlintest.Spec
import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.collections.containExactly
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

import java.io.File

abstract class AbstractIntegrationSpec : StringSpec() {
    /**
     * The software package to download.
     */
    protected abstract val pkg: Package

    /**
     * The definition files that are expected to be found by the [PackageManager].
     */
    protected abstract val expectedManagedFiles: ManagedProjectFiles

    /**
     * The definition files by package manager that are to be used for testing dependency resolution. Defaults to
     * [expectedManagedFiles], but can be e.g. limited to a subset of files in big projects to speed up the test.
     */
    protected open val managedFilesForTest by lazy { expectedManagedFiles }

    /**
     * The list of package identifiers for which errors are expected.
     */
    protected open val identifiersWithExpectedErrors = setOf<Identifier>()

    /**
     * The temporary parent directory for downloads.
     */
    private lateinit var outputDir: File

    /**
     * The directory where the source code of [pkg] was downloaded to.
     */
    protected lateinit var downloadResult: Downloader.DownloadResult

    override fun beforeSpec(description: Description, spec: Spec) {
        outputDir = createTempDir()
        downloadResult = Downloader().download(pkg, outputDir)
    }

    override fun afterSpec(description: Description, spec: Spec) {
        outputDir.safeDeleteRecursively(force = true)
    }

    init {
        "Source code was downloaded successfully".config(tags = setOf(ExpensiveTag)) {
            val workingTree = VersionControlSystem.forDirectory(downloadResult.downloadDirectory)
            workingTree shouldNotBe null
            workingTree!!.isValid() shouldBe true
            workingTree.vcsType shouldBe pkg.vcs.type
            downloadResult.sourceArtifact shouldBe null
            downloadResult.vcsInfo shouldNotBe null
            downloadResult.vcsInfo!!.type shouldBe workingTree.vcsType
        }

        "All package manager definition files are found".config(tags = setOf(ExpensiveTag)) {
            val managedFiles = PackageManager.findManagedFiles(downloadResult.downloadDirectory)

            managedFiles.size shouldBe expectedManagedFiles.size
            managedFiles.forEach { manager, files ->
                println("Verifying definition files for $manager.")

                // The keys in expected and actual maps of definition files are different instances of package manager
                // factories. So to compare values use the package manager names as keys instead.
                val expectedManagedFilesByName = expectedManagedFiles.mapKeys { (manager, _) ->
                    manager.toString()
                }
                val expectedFiles = expectedManagedFilesByName[manager.toString()]

                expectedFiles shouldNotBe null
                files.sorted().joinToString("\n") shouldBe expectedFiles!!.sorted().joinToString("\n")
            }
        }

        "Analyzer creates one non-empty result per definition file".config(tags = setOf(ExpensiveTag)) {
            managedFilesForTest.forEach { manager, files ->
                println("Resolving $manager dependencies in $files.")
                val results = manager.create(DEFAULT_ANALYZER_CONFIGURATION, DEFAULT_REPOSITORY_CONFIGURATION)
                        .resolveDependencies(USER_DIR, files)

                results.size shouldBe files.size
                results.values.forEach { result ->
                    VersionControlSystem.forType(result.project.vcsProcessed.type) shouldBe
                            VersionControlSystem.forType(pkg.vcs.type)
                    result.project.vcsProcessed.url shouldBe pkg.vcs.url
                    result.project.scopes shouldNot beEmpty()
                    result.packages shouldNot beEmpty()
                    result.collectErrors().keys should containExactly(identifiersWithExpectedErrors)
                }
            }
        }
    }
}
