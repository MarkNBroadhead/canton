// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.util

import java.io.{BufferedWriter, File, FileWriter}
import scala.io.Source

object TextFileUtil {

  /** writes the given string to the file.
    */
  def writeStringToFile(outputFile: File, s: String): Unit =
    ResourceUtil.withResource(new BufferedWriter(new FileWriter(outputFile)))(_.write(s))

  /** Gets full string content of a file
    */
  def readStringFromFile(inputFile: File): Either[Throwable, String] =
    ResourceUtil.withResourceEither(Source.fromFile(inputFile))(_.getLines().mkString("\n"))
}
