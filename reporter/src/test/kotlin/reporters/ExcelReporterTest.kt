/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.WordSpec

import org.apache.poi.xssf.usermodel.XSSFWorkbook

class ExcelReporterTest : WordSpec({
    "createUniqueSheetName()" should {
        "work for sheet names that exceed 31 characters" {
            val workbook = XSSFWorkbook()
            val sheetName = "1234567890123456789012345678901xxx"

            val uniqueName1 = createUniqueSheetName(workbook, sheetName)
            uniqueName1 shouldBe "1234567890123456789012345678901"
            workbook.createSheet(uniqueName1) shouldNotBe null

            val uniqueName2 = createUniqueSheetName(workbook, sheetName)
            uniqueName2 shouldBe "12345678901234567890123456789-1"
            workbook.createSheet(uniqueName2) shouldNotBe null
        }

        "match sheet names case-insensitively" {
            val workbook = XSSFWorkbook()
            val sheetName1 = "CASE-SENSITIVITY"
            val sheetName2 = "case-sensitivity"

            val uniqueName1 = createUniqueSheetName(workbook, sheetName1)
            uniqueName1 shouldBe "CASE-SENSITIVITY"
            workbook.createSheet(uniqueName1) shouldNotBe null

            val uniqueName2 = createUniqueSheetName(workbook, sheetName2)
            uniqueName2 shouldBe "case-sensitivity-1"
            workbook.createSheet(uniqueName2) shouldNotBe null
        }
    }
})
