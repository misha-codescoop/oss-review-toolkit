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

/**
 * Bundles information about a remote artifact.
 */
data class RemoteArtifact(
        /**
         * The URL of the remote artifact.
         */
        val url: String,

        /**
         * The hash value of the remote artifact.
         */
        val hash: String,

        /**
         * The name of the algorithm used to calculate the [hash].
         */
        val hashAlgorithm: HashAlgorithm,

        /**
         * A map that holds arbitrary data. Can be used by third-party tools to add custom data to the model.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        val data: CustomData = emptyMap()
) {
    companion object {
        /**
         * A constant for a [RemoteArtifact] where all properties are empty strings.
         */
        @JvmField
        val EMPTY = RemoteArtifact(
                url = "",
                hash = "",
                hashAlgorithm = HashAlgorithm.UNKNOWN
        )
    }
}
