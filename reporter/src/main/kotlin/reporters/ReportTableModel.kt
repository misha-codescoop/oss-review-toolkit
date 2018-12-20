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

package com.here.ort.reporter.reporters

import com.here.ort.model.Identifier
import com.here.ort.model.OrtResult
import com.here.ort.model.Project
import com.here.ort.model.ScanResult
import com.here.ort.model.VcsInfo
import com.here.ort.model.config.ProjectExclude
import com.here.ort.model.config.ScopeExclude
import com.here.ort.utils.zipWithDefault

import java.util.SortedMap
import java.util.SortedSet

fun Collection<ReportTableModel.ResolvableIssue>.containsUnresolved() = any { !it.isResolved }

fun <K> Map<K, Collection<ReportTableModel.ResolvableIssue>>.containsUnresolved() =
        any { it.value.containsUnresolved() }

data class ReportTableModel(
        /**
         * The [VcsInfo] for the scanned project.
         */
        val vcsInfo: VcsInfo,

        /**
         * A list containing all evaluator errors. `null` if no evaluator result is available.
         */
        val evaluatorErrors: List<ResolvableIssue>?,

        /**
         * A [ErrorTable] containing all dependencies that caused errors.
         */
        val errorSummary: ErrorTable,

        /**
         * A [SummaryTable] containing the dependencies of all [Project]s.
         */
        val summary: SummaryTable,

        /**
         * The [ProjectTable]s containing the dependencies for each [Project].
         */
        val projectDependencies: SortedMap<Project, ProjectTable>,

        /**
         * Additional metadata read from the [OrtResult.data] field.
         */
        val metadata: Map<String, String>,

        /**
         * Extra columns that shall be added to the results table by the implementing reporter.
         */
        val extraColumns: List<String>
) {
    data class ProjectTable(
            /**
             * The dependencies of this project.
             */
            val rows: List<DependencyRow>,

            /**
             * Information about if and why the project is excluded.
             */
            val exclude: ProjectExclude? = null
    )

    data class DependencyRow(
            /**
             * The identifier of the package.
             */
            val id: Identifier,

            /**
             * The scopes the package is used in.
             */
            val scopes: SortedMap<String, List<ScopeExclude>>,

            /**
             * The licenses declared by the package.
             */
            val declaredLicenses: SortedSet<String>,

            /**
             * The detected licenses aggregated from all [ScanResult]s for this package.
             */
            val detectedLicenses: SortedSet<String>,

            /**
             * All analyzer errors related to this package.
             */
            val analyzerErrors: List<ResolvableIssue>,

            /**
             * All scan errors related to this package.
             */
            val scanErrors: List<ResolvableIssue>
    ) {
        fun merge(other: DependencyRow) =
                DependencyRow(
                        id = id,
                        scopes = scopes.zipWithDefault(other.scopes, emptyList()) { a, b -> a + b }.toSortedMap(),
                        declaredLicenses = (declaredLicenses + other.declaredLicenses).toSortedSet(),
                        detectedLicenses = (detectedLicenses + other.detectedLicenses).toSortedSet(),
                        analyzerErrors = (analyzerErrors + other.analyzerErrors).distinct(),
                        scanErrors = (scanErrors + other.scanErrors).distinct()
                )
    }

    data class SummaryTable(
            val rows: List<SummaryRow>,
            val projectExcludes: Map<Identifier, ProjectExclude?>
    )

    data class SummaryRow(
            /**
             * The identifier of the package.
             */
            val id: Identifier,

            /**
             * The scopes the package is used in, grouped by the [Identifier] of the [Project] they appear in.
             */
            val scopes: SortedMap<Identifier, SortedMap<String, List<ScopeExclude>>>,

            /**
             * The licenses declared by the package.
             */
            val declaredLicenses: SortedSet<String>,

            /**
             * The detected licenses aggregated from all [ScanResult]s for this package.
             */
            val detectedLicenses: SortedSet<String>,

            /**
             * All analyzer errors related to this package, grouped by the [Identifier] of the [Project] they appear in.
             */
            val analyzerErrors: SortedMap<Identifier, List<ResolvableIssue>>,

            /**
             * All scan errors related to this package, grouped by the [Identifier] of the [Project] they appear in.
             */
            val scanErrors: SortedMap<Identifier, List<ResolvableIssue>>
    ) {
        fun merge(other: SummaryRow): SummaryRow {
            fun <T> plus(left: List<T>, right: List<T>) = left + right

            return SummaryRow(
                    id = id,
                    scopes = scopes.zipWithDefault(other.scopes, sortedMapOf()) { left, right ->
                        left.zipWithDefault(right, emptyList(), ::plus).toSortedMap()
                    }.toSortedMap(),
                    declaredLicenses = (declaredLicenses + other.declaredLicenses).toSortedSet(),
                    detectedLicenses = (detectedLicenses + other.detectedLicenses).toSortedSet(),
                    analyzerErrors = analyzerErrors.zipWithDefault(other.analyzerErrors, emptyList(), ::plus)
                            .toSortedMap(),
                    scanErrors = scanErrors.zipWithDefault(other.scanErrors, emptyList(), ::plus).toSortedMap()
            )
        }
    }

    data class ErrorTable(
            val rows: List<ErrorRow>
    )

    data class ErrorRow(
            /**
             * The identifier of the package.
             */
            val id: Identifier,

            /**
             * All analyzer errors related to this package, grouped by the [Identifier] of the [Project] they appear in.
             */
            val analyzerErrors: SortedMap<Identifier, List<ResolvableIssue>>,

            /**
             * All scan errors related to this package, grouped by the [Identifier] of the [Project] they appear in.
             */
            val scanErrors: SortedMap<Identifier, List<ResolvableIssue>>
    ) {
        fun merge(other: ErrorRow): ErrorRow {
            val plus = { left: List<ResolvableIssue>, right: List<ResolvableIssue> -> left + right }

            return ErrorRow(
                    id = id,
                    analyzerErrors = analyzerErrors.zipWithDefault(other.analyzerErrors, emptyList(), plus)
                            .toSortedMap(),
                    scanErrors = scanErrors.zipWithDefault(other.scanErrors, emptyList(), plus).toSortedMap()
            )
        }
    }

    data class ResolvableIssue(
            val source: String,
            val description: String,
            val resolutionDescription: String,
            val isResolved: Boolean
    )
}
