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

import com.fasterxml.jackson.annotation.JsonInclude

import com.here.ort.spdx.SpdxExpression

import java.util.SortedSet

/**
 * This class contains curation data for a package. It is used to amend the automatically detected meta data for a
 * package with corrections. This is required because the meta data provided by a package can be wrong (e.g. outdated
 * VCS data) or incomplete.
 */
data class PackageCurationData(
        /**
         * The list of licenses the authors have declared for this package. This does not necessarily correspond to the
         * licenses as detected by a scanner. Both need to be taken into account for any conclusions.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val declaredLicenses: SortedSet<String>? = null,

        /**
         * The concluded license as an [SpdxExpression]. It can be used to correct the license of a package in case the
         * [declaredLicenses] found in the packages metadata or the licenses detected by a scanner do not match reality.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val concludedLicense: SpdxExpression? = null,

        /**
         * The description of the package, as provided by the package manager.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val description: String? = null,

        /**
         * The homepage of the package.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val homepageUrl: String? = null,

        /**
         * The remote artifact where the binary package can be downloaded.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val binaryArtifact: RemoteArtifact? = null,

        /**
         * The remote artifact where the source package can be downloaded.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val sourceArtifact: RemoteArtifact? = null,

        /**
         * VCS-related information.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val vcs: VcsInfo? = null,

        /**
         * A plain-text comment about this curation. Should contain information about how and why the curation was
         * created.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        val comment: String? = null,

        /**
         * A map that holds arbitrary data. Can be used by third-party tools to add custom data to the model.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        val data: CustomData = emptyMap()
) {
    /**
     * Apply the curation data to the provided package, by overriding all values of the original package with non-null
     * values of the curation data.
     *
     * @param base The package to curate.
     *
     * @return The curated package.
     */
    fun apply(base: CuratedPackage): CuratedPackage {
        val curatedVcs = if (vcs != null) {
            VcsInfo(
                    type = vcs.type.takeUnless { it.isBlank() } ?: base.pkg.vcs.type,
                    url = vcs.url.takeUnless { it.isBlank() } ?: base.pkg.vcs.url,
                    revision = vcs.revision.takeUnless { it.isBlank() } ?: base.pkg.vcs.revision,
                    resolvedRevision = vcs.resolvedRevision ?: base.pkg.vcs.resolvedRevision,
                    path = vcs.path.takeUnless { it.isBlank() } ?: base.pkg.vcs.path
            )
        } else {
            base.pkg.vcs
        }

        val curated = base.pkg.let { pkg ->
            Package(
                    id = pkg.id,
                    declaredLicenses = declaredLicenses ?: pkg.declaredLicenses,
                    concludedLicense = concludedLicense ?: pkg.concludedLicense,
                    description = description ?: pkg.description,
                    homepageUrl = homepageUrl ?: pkg.homepageUrl,
                    binaryArtifact = binaryArtifact ?: pkg.binaryArtifact,
                    sourceArtifact = sourceArtifact ?: pkg.sourceArtifact,
                    vcs = curatedVcs
            )
        }

        return CuratedPackage(curated, base.curations + PackageCurationResult(base.pkg.diff(curated), this))
    }
}
