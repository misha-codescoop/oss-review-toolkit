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

import ch.frankel.slf4k.*

import com.here.ort.downloader.DownloadException
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.downloader.WorkingTree
import com.here.ort.model.Package
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.log
import com.here.ort.utils.safeDeleteRecursively
import com.here.ort.utils.searchUpwardsForSubdirectory

import java.io.File
import java.io.IOException
import java.util.regex.Pattern

import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils

typealias CvsFileRevisions = List<Pair<String, String>>

class Cvs : VersionControlSystem(), CommandLineTool {
    private val versionRegex = Pattern.compile("Concurrent Versions System \\(CVS\\) (?<version>[\\d.]+).+")

    override val aliases = listOf("cvs")
    override val latestRevisionNames = emptyList<String>()

    override fun command(workingDir: File?) = "cvs"

    override fun getVersion() =
            getVersion { output ->
                versionRegex.matcher(output.lineSequence().first()).let {
                    if (it.matches()) {
                        it.group("version")
                    } else {
                        ""
                    }
                }
            }

    override fun getWorkingTree(vcsDirectory: File) =
            object : WorkingTree(vcsDirectory, type) {
                private val cvsDirectory = File(workingDir, "CVS")

                override fun isValid(): Boolean {
                    if (!workingDir.isDirectory) {
                        return false
                    }

                    return ProcessCapture(workingDir, "cvs", "status", "-l").isSuccess
                }

                override fun isShallow() = false

                override fun getRemoteUrl() = File(cvsDirectory, "Root").useLines { it.first() }

                override fun getRevision() =
                        // CVS does not have the concept of a global revision, but each file has its own revision. As
                        // we just use the revision to uniquely identify the state of a working tree, simply create an
                        // artificial single revision based on the revisions of all files.
                        getFileRevisionsHash(getFileRevisions())

                private fun getFileRevisions(): CvsFileRevisions {
                    val cvsLog = run(workingDir, "log", "-h")

                    var currentWorkingFile = ""
                    return cvsLog.stdout.lines().mapNotNull { line ->
                        var value = line.removePrefix("Working file: ")
                        if (value.length < line.length) {
                            currentWorkingFile = value
                        } else {
                            value = line.removePrefix("head: ")
                            if (value.length < line.length) {
                                if (currentWorkingFile.isNotBlank() && value.isNotBlank()) {
                                    return@mapNotNull Pair(currentWorkingFile, value)
                                }
                            }
                        }

                        null
                    }.sortedBy { it.first }
                }

                private fun getFileRevisionsHash(fileRevisions: CvsFileRevisions): String {
                    val digest = fileRevisions.fold(DigestUtils.getSha1Digest()) { digest, (file, revision) ->
                        DigestUtils.updateDigest(digest, file)
                        DigestUtils.updateDigest(digest, revision)
                    }.digest()

                    return Hex.encodeHexString(digest)
                }

                override fun getRootPath(): File {
                    val rootDir = workingDir.searchUpwardsForSubdirectory("CVS")
                    return rootDir ?: workingDir
                }

                private fun listSymbolicNames(): Map<String, String> {
                    val cvsLog = run(workingDir, "log", "-h")
                    var tagsSectionStarted = false

                    return cvsLog.stdout.lines().mapNotNull { line ->
                        if (tagsSectionStarted) {
                            if (line.startsWith('\t')) {
                                line.split(':', limit = 2).let {
                                    Pair(it.first().trim(), it.last().trim())
                                }
                            } else {
                                tagsSectionStarted = false
                                null
                            }
                        } else {
                            if (line == "symbolic names:") {
                                tagsSectionStarted = true
                            }
                            null
                        }
                    }.toMap().toSortedMap()
                }

                private fun isBranchVersion(version: String): Boolean {
                    // See http://cvsgui.sourceforge.net/howto/cvsdoc/cvs_5.html#SEC59.
                    val decimals = version.split('.')

                    // "Externally, branch numbers consist of an odd number of dot-separated decimal
                    // integers."
                    return decimals.count() % 2 != 0 ||
                            // "That is not the whole truth, however. For efficiency reasons CVS sometimes inserts
                            // an extra 0 in the second rightmost position."
                            decimals.dropLast(1).last() == "0"
                }

                override fun listRemoteBranches(): List<String> {
                    return listSymbolicNames().mapNotNull { (name, version) ->
                        if (isBranchVersion(version)) name else null
                    }
                }

                override fun listRemoteTags(): List<String> {
                    return listSymbolicNames().mapNotNull { (name, version) ->
                        if (isBranchVersion(version)) null else name
                    }
                }
            }

    override fun isApplicableUrlInternal(vcsUrl: String) = vcsUrl.matches("^:(ext|pserver):[^@]+@.+$".toRegex())

    override fun download(pkg: Package, targetDir: File, allowMovingRevisions: Boolean,
                          recursive: Boolean): WorkingTree {
        log.info { "Using $type version ${getVersion()}." }

        try {
            val path = pkg.vcsProcessed.path.takeUnless { it.isBlank() } ?: "."

            // Create a "fake" checkout as described at https://stackoverflow.com/a/3448891/1127485.
            run(targetDir, "-z3", "-d", pkg.vcsProcessed.url, "checkout", "-l", ".")
            val workingTree = getWorkingTree(targetDir)

            val revision = if (allowMovingRevisions || isFixedRevision(workingTree, pkg.vcsProcessed.revision)) {
                pkg.vcsProcessed.revision
            } else {
                // Create all working tree directories in order to be able to query the log.
                run(targetDir, "update", "-d")

                log.info { "Trying to guess a $type revision for version '${pkg.id.version}'." }

                try {
                    workingTree.guessRevisionName(pkg.id.name, pkg.id.version).also { revision ->
                        log.warn {
                            "Using guessed $type revision '$revision' for version '${pkg.id.version}'. This might " +
                                    "cause the downloaded source code to not match the package version."
                        }
                    }
                } catch (e: IOException) {
                    throw IOException("Unable to determine a revision to checkout.", e)
                } finally {
                    // Clean the temporarily updated working tree again.
                    targetDir.listFiles().forEach {
                        if (it.isDirectory) {
                            if (it.name != "CVS") it.safeDeleteRecursively()
                        } else {
                            it.delete()
                        }
                    }
                }
            }

            // Checkout the working tree of the desired revision.
            run(targetDir, "checkout", "-r", revision, path)

            return workingTree
        } catch (e: IOException) {
            throw DownloadException("$type failed to download from URL '${pkg.vcsProcessed.url}'.", e)
        }
    }
}
