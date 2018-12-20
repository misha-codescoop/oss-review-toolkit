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

package com.here.ort.scanner

import com.here.ort.model.config.ArtifactoryCacheConfiguration

import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.WordSpec

@Suppress("UnsafeCallOnNullableType", "UnsafeCast")
class ScanResultsCacheTest : WordSpec() {
    private fun ArtifactoryCache.getStringField(name: String): String {
        javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            return it.get(this) as String
        }
    }

    private fun ArtifactoryCache.getApiToken() = getStringField("apiToken")

    private fun ArtifactoryCache.getUrl() = getStringField("url")

    init {
        "ScanResultsCache.configure" should {
            "fail if the Artifactory URL is empty" {
                val exception = shouldThrow<IllegalArgumentException> {
                    val config = ArtifactoryCacheConfiguration("", "someApiToken")

                    ScanResultsCache.configure(config)
                }
                exception.message shouldBe "URL for Artifactory cache is missing."
            }

            "fail if the Artifactory apiToken is empty" {
                val exception = shouldThrow<IllegalArgumentException> {
                    val config = ArtifactoryCacheConfiguration("someUrl", "")

                    ScanResultsCache.configure(config)
                }
                exception.message shouldBe "API token for Artifactory cache is missing."
            }

            "configure the Artifactory cache correctly" {
                val config = ArtifactoryCacheConfiguration("someUrl", "someApiToken")

                ScanResultsCache.configure(config)

                ScanResultsCache.cache shouldNotBe null
                ScanResultsCache.cache::class shouldBe ArtifactoryCache::class
                (ScanResultsCache.cache as ArtifactoryCache).getApiToken() shouldBe "someApiToken"
                (ScanResultsCache.cache as ArtifactoryCache).getUrl() shouldBe "someUrl"
            }
        }
    }
}
