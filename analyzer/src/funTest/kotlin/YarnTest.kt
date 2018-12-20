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

package com.here.ort.analyzer

import com.here.ort.analyzer.managers.Yarn
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.yamlMapper
import com.here.ort.utils.normalizeVcsUrl
import com.here.ort.utils.safeDeleteRecursively
import com.here.ort.utils.test.DEFAULT_ANALYZER_CONFIGURATION
import com.here.ort.utils.test.DEFAULT_REPOSITORY_CONFIGURATION
import com.here.ort.utils.test.USER_DIR
import com.here.ort.utils.test.patchExpectedResult

import io.kotlintest.Description
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.specs.WordSpec

import java.io.File

class YarnTest : WordSpec() {
    private val projectDir = File("src/funTest/assets/projects/synthetic/yarn")
    private val vcsDir = VersionControlSystem.forDirectory(projectDir)!!
    private val vcsUrl = vcsDir.getRemoteUrl()
    private val vcsRevision = vcsDir.getRevision()

    override fun afterTest(description: Description, result: TestResult) {
        // Make sure the node_modules directory is always deleted from each subdirectory to prevent side-effects
        // from failing tests.
        projectDir.listFiles().forEach {
            if (it.isDirectory) {
                val nodeModulesDir = File(it, "node_modules")
                val gitKeepFile = File(nodeModulesDir, ".gitkeep")
                if (nodeModulesDir.isDirectory && !gitKeepFile.isFile) {
                    nodeModulesDir.safeDeleteRecursively(force = true)
                }
            }
        }
    }

    init {
        "yarn" should {
            "resolve dependencies correctly" {
                val packageFile = File(projectDir, "package.json")
                val result = Yarn(DEFAULT_ANALYZER_CONFIGURATION, DEFAULT_REPOSITORY_CONFIGURATION)
                        .resolveDependencies(USER_DIR, listOf(packageFile))[packageFile]
                val vcsPath = vcsDir.getPathToRoot(projectDir)
                val expectedResult = patchExpectedResult(
                        File(projectDir.parentFile, "yarn-expected-output.yml"),
                        definitionFilePath = "$vcsPath/package.json",
                        url = normalizeVcsUrl(vcsUrl),
                        revision = vcsRevision,
                        path = vcsPath
                )

                yamlMapper.writeValueAsString(result) shouldBe expectedResult
            }
        }
    }
}
