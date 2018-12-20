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

package com.here.ort.utils

import io.kotlintest.Description
import io.kotlintest.TestResult
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

import java.io.File

class ArchiveUtilsTest : StringSpec() {
    private lateinit var outputDir: File

    override fun beforeTest(description: Description) {
        outputDir = createTempDir()
    }

    override fun afterTest(description: Description, result: TestResult) {
        outputDir.safeDeleteRecursively(force = true)
    }

    init {
        "Tar GZ archive can be unpacked" {
            val archive = File("src/test/assets/test.tar.gz")

            archive.unpack(outputDir)

            val fileA = File(outputDir, "a")
            val fileB = File(outputDir, "dir/b")

            fileA.exists() shouldBe true
            fileA.readText() shouldBe "a\n"
            fileB.exists() shouldBe true
            fileB.readText() shouldBe "b\n"
        }

        "Tar bzip2 archive can be unpacked" {
            val archive = File("src/test/assets/test.tar.bz2")

            archive.unpack(outputDir)

            val fileA = File(outputDir, "a")
            val fileB = File(outputDir, "dir/b")

            fileA.exists() shouldBe true
            fileA.readText() shouldBe "a\n"
            fileB.exists() shouldBe true
            fileB.readText() shouldBe "b\n"
        }

        "Zip archive can be unpacked" {
            val archive = File("src/test/assets/test.zip")

            archive.unpack(outputDir)

            val fileA = File(outputDir, "a")
            val fileB = File(outputDir, "dir/b")

            fileA.exists() shouldBe true
            fileA.readText() shouldBe "a\n"
            fileB.exists() shouldBe true
            fileB.readText() shouldBe "b\n"
        }
    }
}
