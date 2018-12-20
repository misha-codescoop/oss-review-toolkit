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

package com.here.ort.commands

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters

import com.here.ort.CommandWithHelp
import com.here.ort.evaluator.Evaluator
import com.here.ort.model.OrtResult
import com.here.ort.model.OutputFormat
import com.here.ort.model.readValue
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL
import com.here.ort.utils.log
import com.here.ort.utils.safeMkdirs

import java.io.File

@Parameters(commandNames = ["evaluate"], commandDescription = "Evaluate rules on ORT result files.")
object EvaluatorCommand : CommandWithHelp() {
    @Parameter(description = "The ORT result file to read as input.",
            names = ["--ort-file", "-i"],
            required = true,
            order = PARAMETER_ORDER_MANDATORY)
    private lateinit var ortFile: File

    @Parameter(description = "The name of a script file containing rules.",
            names = ["--rules-file", "-r"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var rulesFile: File? = null

    @Parameter(description = "The name of a script resource on the classpath that contains rules.",
            names = ["--rules-resource"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var rulesResource: String? = null

    @Parameter(description = "The directory to write the evaluation results as ORT result file(s) to, in the " +
            "specified output format(s).",
            names = ["--output-dir", "-o"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var outputDir: File? = null

    @Parameter(description = "The list of output formats to be used for the ORT result file(s).",
            names = ["--output-formats", "-f"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var outputFormats = listOf(OutputFormat.YAML)

    @Parameter(description = "Do not evaluate the script but only check its syntax. No output is written in this case.",
            names = ["--syntax-check"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var syntaxCheck = false

    @Parameter(description = "A file containing the repository configuration. If set the .ort.yml " +
            "overrides the repository configuration contained in the ort result from the input file.",
            names = ["--repository-configuration-file"],
            order = PARAMETER_ORDER_OPTIONAL)
    private var repositoryConfigurationFile: File? = null

    override fun runCommand(jc: JCommander): Int {
        require((rulesFile == null) != (rulesResource == null)) {
            "Either '--rules-file' or '--rules-resource' must be specified."
        }

        var ortResultInput = ortFile.readValue<OrtResult>()
        repositoryConfigurationFile?.let {
            ortResultInput = ortResultInput.replaceConfig(it.readValue())
        }

        val script = rulesFile?.readText() ?: javaClass.classLoader.getResource(rulesResource).readText()

        val evaluator = Evaluator(ortResultInput)

        if (syntaxCheck) {
            return if (evaluator.checkSyntax(script)) 0 else 2
        }

        val evaluatorRun = evaluator.run(script)

        outputDir?.let { dir ->
            require(!dir.exists()) {
                "The output directory '${dir.absolutePath}' must not exist yet."
            }

            dir.safeMkdirs()

            // Note: This overwrites any existing EvaluatorRun from the input file.
            val ortResultOutput = ortResultInput.copy(evaluator = evaluatorRun)

            outputFormats.distinct().forEach { format ->
                val evaluationResultFile = File(dir, "evaluation-result.${format.fileExtension}")
                println("Writing evaluation result to '${evaluationResultFile.absolutePath}'.")
                format.mapper.writerWithDefaultPrettyPrinter().writeValue(evaluationResultFile, ortResultOutput)
            }
        }

        if (log.isErrorEnabled) {
            evaluatorRun.errors.forEach { error ->
                log.error(error.toString())
            }
        }

        return if (evaluatorRun.errors.isEmpty()) 0 else 2
    }
}
