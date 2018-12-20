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

package com.here.ort.downloader.vcs

import com.here.ort.utils.unpack
import com.here.ort.utils.getUserConfigDirectory
import com.here.ort.utils.safeDeleteRecursively

import io.kotlintest.Description
import io.kotlintest.Spec
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

import java.io.File

class CvsTest : StringSpec() {
    private val cvs = Cvs()
    private lateinit var zipContentDir: File

    override fun beforeSpec(description: Description, spec: Spec) {
        val zipFile = File("src/test/assets/tyrex-2018-01-29-cvs.zip")

        zipContentDir = createTempDir()

        println("Extracting '$zipFile' to '$zipContentDir'...")
        zipFile.unpack(zipContentDir)
    }

    override fun afterSpec(description: Description, spec: Spec) {
        zipContentDir.safeDeleteRecursively(force = true)
    }

    init {
        "Detected CVS version is not empty".config(enabled = cvs.isInPath()) {
            val version = cvs.getVersion()
            println("CVS version $version detected.")
            version shouldNotBe ""
        }

        "CVS detects non-working-trees".config(enabled = cvs.isInPath()) {
            cvs.getWorkingTree(getUserConfigDirectory()).isValid() shouldBe false
        }

        "CVS correctly detects URLs to remote repositories".config(enabled = cvs.isInPath() && false) {
            cvs.isApplicableUrl(":pserver:anonymous@tyrex.cvs.sourceforge.net:/cvsroot/tyrex") shouldBe true
            cvs.isApplicableUrl(":ext:jrandom@cvs.foobar.com:/usr/local/cvs") shouldBe true
            cvs.isApplicableUrl("http://svn.code.sf.net/p/grepwin/code/") shouldBe false
        }

        "Detected CVS working tree information is correct".config(enabled = cvs.isInPath() && false) {
            val workingTree = cvs.getWorkingTree(zipContentDir)

            workingTree.vcsType shouldBe "Cvs"
            workingTree.isValid() shouldBe true
            workingTree.getRemoteUrl() shouldBe ":pserver:anonymous@tyrex.cvs.sourceforge.net:/cvsroot/tyrex"
            workingTree.getRevision() shouldBe "8707a14c78c6e77ffc59e685360fa20071c1afb6"
            workingTree.getRootPath() shouldBe zipContentDir
            workingTree.getPathToRoot(File(zipContentDir, "tomcat")) shouldBe "tomcat"
        }

        "CVS correctly lists remote branches".config(enabled = cvs.isInPath() && false) {
            val expectedBranches = listOf(
                    "Exoffice"
            )

            val workingTree = cvs.getWorkingTree(zipContentDir)
            workingTree.listRemoteBranches().joinToString("\n") shouldBe expectedBranches.joinToString("\n")
        }

        "CVS correctly lists remote tags".config(enabled = cvs.isInPath() && false) {
            val expectedTags = listOf(
                    "A02",
                    "A03",
                    "A04",
                    "DEV0_9_3",
                    "DEV0_9_4",
                    "DEV_0_9_7",
                    "PROD_1_0_1",
                    "PROD_1_0_2",
                    "PROD_1_0_3",
                    "before_SourceForge",
                    "before_debug_changes"
            )

            val workingTree = cvs.getWorkingTree(zipContentDir)
            workingTree.listRemoteTags().joinToString("\n") shouldBe expectedTags.joinToString("\n")
        }
    }
}
