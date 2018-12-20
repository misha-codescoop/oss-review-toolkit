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

package com.here.ort.model

import com.fasterxml.jackson.module.kotlin.readValue

import io.kotlintest.shouldBe
import io.kotlintest.specs.WordSpec

class AnalyzerResultTest : WordSpec() {
    private val package1 = Package.EMPTY.copy(id = Identifier("type-1", "namespace-1", "package-1", "version-1"))
    private val package2 = Package.EMPTY.copy(id = Identifier("type-2", "namespace-2", "package-2", "version-2"))
    private val package3 = Package.EMPTY.copy(id = Identifier("type-3", "namespace-3", "package-3", "version-3"))

    private val pkgRef1 = package1.toReference()
    private val pkgRef2 = package2.toReference(dependencies = sortedSetOf(package3.toReference()))

    private val scope1 = Scope("scope-1", sortedSetOf(pkgRef1))
    private val scope2 = Scope("scope-2", sortedSetOf(pkgRef2))

    private val project1 = Project.EMPTY.copy(
            id = Identifier("type-1", "namespace-1", "project-1", "version-1"),
            scopes = sortedSetOf(scope1)
    )
    private val project2 = Project.EMPTY.copy(
            id = Identifier("type-2", "namespace-2", "project-2", "version-2"),
            scopes = sortedSetOf(scope1, scope2)
    )

    private val error1 = OrtIssue(source = "source-1", message = "message-1")
    private val error2 = OrtIssue(source = "source-2", message = "message-2")

    private val analyzerResult1 = ProjectAnalyzerResult(project1, sortedSetOf(package1.toCuratedPackage()),
            listOf(error1, error2))
    private val analyzerResult2 = ProjectAnalyzerResult(project2,
            sortedSetOf(package1.toCuratedPackage(), package2.toCuratedPackage(), package3.toCuratedPackage()),
            listOf(error2))

    init {
        "AnalyzerResult" should {
            "be serialized and deserialized correctly" {
                val mergedResults = AnalyzerResultBuilder()
                        .addResult(analyzerResult1)
                        .addResult(analyzerResult2)
                        .build()

                val serializedMergedResults = yamlMapper.writeValueAsString(mergedResults)
                val deserializedMergedResults = yamlMapper.readValue<AnalyzerResult>(serializedMergedResults)

                deserializedMergedResults shouldBe mergedResults
            }
        }

        "AnalyzerResultBuilder" should {
            "merge results from all files" {
                val mergedResults = AnalyzerResultBuilder()
                        .addResult(analyzerResult1)
                        .addResult(analyzerResult2)
                        .build()

                mergedResults.projects shouldBe sortedSetOf(project1, project2)
                mergedResults.packages shouldBe sortedSetOf(package1.toCuratedPackage(), package2.toCuratedPackage(),
                        package3.toCuratedPackage())
                mergedResults.errors shouldBe
                        sortedMapOf(project1.id to analyzerResult1.errors, project2.id to analyzerResult2.errors)
            }
        }
    }
}
