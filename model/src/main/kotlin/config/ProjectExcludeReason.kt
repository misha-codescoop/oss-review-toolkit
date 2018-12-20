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

package com.here.ort.model.config

/**
 * Possible reasons for excluding a project.
 */
enum class ProjectExcludeReason {
    /**
     * The project only contains tools used for building source code which are not included in distributed build
     * artifacts.
     */
    BUILD_TOOL_OF,

    /**
     * The project only contains data files such as fonts or images which are not included in distributed build
     * artifacts.
     */
    DATA_FILE_OF,

    /**
     * The project only contains documentation which is not included in distributed build artifacts.
     */
    DOCUMENTATION_OF,

    /**
     * The project only contains source code examples which are not included in distributed build artifacts.
     */
    EXAMPLE_OF,

    /**
     * The project only contains optional components for the code that is built which are not included in distributed
     * build artifacts.
     */
    OPTIONAL_COMPONENT_OF,

    /**
     * The project only contains tools used for testing source code which are not included in distributed build
     * artifacts.
     */
    TEST_TOOL_OF
}
