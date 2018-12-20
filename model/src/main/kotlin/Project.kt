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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

import java.util.SortedSet

/**
 * A class describing a software project. A [Project] is very similar to a [Package] but contains some additional
 * meta-data like e.g. the [homepageUrl]. Most importantly, it defines the dependency scopes that refer to the actual
 * packages.
 */
@JsonIgnoreProperties("aliases")
data class Project(
        /**
         * The unique identifier of this project.
         */
        val id: Identifier,

        /**
         * The path to the definition file of this project, relative to the root of the repository described in [vcs]
         * and [vcsProcessed].
         */
        val definitionFilePath: String,

        /**
         * The list of licenses the authors have declared for this package. This does not necessarily correspond to the
         * licenses as detected by a scanner. Both need to be taken into account for any conclusions.
         */
        val declaredLicenses: SortedSet<String>,

        /**
         * Original VCS-related information as defined in the [Project]'s meta-data.
         */
        val vcs: VcsInfo,

        /**
         * Processed VCS-related information about the [Project] that has e.g. common mistakes corrected.
         */
        val vcsProcessed: VcsInfo = vcs.normalize(),

        /**
         * The URL to the project's homepage.
         */
        val homepageUrl: String,

        /**
         * The dependency scopes defined by this project.
         */
        val scopes: SortedSet<Scope>,

        /**
         * A map that holds arbitrary data. Can be used by third-party tools to add custom data to the model.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        val data: CustomData = emptyMap()
) : Comparable<Project> {
    companion object {
        /**
         * A constant for a [Project] where all properties are empty.
         */
        @JvmField
        val EMPTY = Project(
                id = Identifier.EMPTY,
                definitionFilePath = "",
                declaredLicenses = sortedSetOf(),
                vcs = VcsInfo.EMPTY,
                homepageUrl = "",
                scopes = sortedSetOf()
        )
    }

    /**
     * Return the set of [PackageReference]s in this [Project], up to and including a depth of [maxDepth] where counting
     * starts at 0 (for the [Project] itself) and 1 are direct dependencies etc. A value below 0 means to not limit the
     * depth. If [includeErroneous] is true, [PackageReference]s with errors (but not their dependencies without errors)
     * are excluded, otherwise they are included.
     */
    fun collectDependencies(maxDepth: Int = -1, includeErroneous: Boolean = true) =
            scopes.fold(sortedSetOf<PackageReference>()) { refs, scope ->
                refs.also { it += scope.collectDependencies(maxDepth, includeErroneous) }
            }

    /**
     * Return a de-duplicated list of all errors for the provided [id].
     */
    fun collectErrors(id: Identifier): List<OrtIssue> {
        val collectedErrors = mutableListOf<OrtIssue>()

        fun addErrors(pkgRef: PackageReference) {
            if (pkgRef.id == id) {
                collectedErrors += pkgRef.errors
            }

            pkgRef.dependencies.forEach { addErrors(it) }
        }

        for (scope in scopes) {
            for (dependency in scope.dependencies) {
                addErrors(dependency)
            }
        }

        return collectedErrors.distinct()
    }

    /**
     * A comparison function to sort projects by their identifier.
     */
    override fun compareTo(other: Project) = id.compareTo(other.id)

    /**
     * Return all references to [id] as a dependency in this project.
     */
    fun findReferences(id: Identifier) = scopes.flatMap { it.findReferences(id) }

    /**
     * Return a [Package] representation of this [Project].
     */
    fun toPackage() = Package(
            id = id,
            declaredLicenses = declaredLicenses,
            description = "",
            homepageUrl = homepageUrl,
            binaryArtifact = RemoteArtifact.EMPTY,
            sourceArtifact = RemoteArtifact.EMPTY,
            vcs = vcs,
            vcsProcessed = vcsProcessed
    )
}
