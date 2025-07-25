/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.input

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.apache.hadoop.io.compress.{CompressionCodecFactory, GzipCodec}

import org.apache.spark.{SparkConf, SparkContext, SparkFunSuite}
import org.apache.spark.io.ZStdCompressionCodec

/**
 * Tests the correctness of
 * [[org.apache.spark.input.WholeTextFileRecordReader WholeTextFileRecordReader]]. A temporary
 * directory is created as fake input. Temporal storage would be deleted in the end.
 */
class WholeTextFileRecordReaderSuite extends SparkFunSuite {
  private var sc: SparkContext = _
  private var factory: CompressionCodecFactory = _

  def getSparkConf(): SparkConf = {
    new SparkConf()
  }

  override def beforeAll(): Unit = {
    // Hadoop's FileSystem caching does not use the Configuration as part of its cache key, which
    // can cause Filesystem.get(Configuration) to return a cached instance created with a different
    // configuration than the one passed to get() (see HADOOP-8490 for more details). This caused
    // hard-to-reproduce test failures, since any suites that were run after this one would inherit
    // the new value of "fs.local.block.size" (see SPARK-5227 and SPARK-5679). To work around this,
    // we disable FileSystem caching in this suite.
    super.beforeAll()
    val conf = getSparkConf().set("spark.hadoop.fs.file.impl.disable.cache", "true")

    sc = new SparkContext("local", "test", conf)

    // Set the block size of local file system to test whether files are split right or not.
    sc.hadoopConfiguration.setLong("fs.local.block.size", 32)
    sc.hadoopConfiguration.set("io.compression.codecs",
      "org.apache.hadoop.io.compress.GzipCodec,org.apache.hadoop.io.compress.DefaultCodec")
    factory = new CompressionCodecFactory(sc.hadoopConfiguration)
  }

  override def afterAll(): Unit = {
    try {
      sc.stop()
    } finally {
      super.afterAll()
    }
  }

  import WholeTextFileRecordReaderSuite.CompressionType

  def createNativeFile(inputDir: File, fileName: String, contents: Array[Byte],
    compressionType: CompressionType.CompressionType = CompressionType.NONE): Unit = {
    val out = if (compressionType == CompressionType.GZIP ||
      compressionType == CompressionType.GZ) {
      val codec = new GzipCodec
      codec.setConf(new Configuration())
      val extension = if (compressionType == CompressionType.GZIP) {
        ".gzip" // Try with non-standard extension
      } else {
        codec.getDefaultExtension
      }
      val path = s"${inputDir.toString}/$fileName${extension}"
      codec.createOutputStream(new DataOutputStream(new FileOutputStream(path)))
    } else if (compressionType == CompressionType.ZSTD || compressionType == CompressionType.ZST) {
      val extension = if (compressionType == CompressionType.ZSTD) ".zstd" else ".zst"
      val path = s"${inputDir.toString}/${fileName}${extension}"
      new ZStdCompressionCodec(sc.conf).compressedOutputStream(new FileOutputStream(path))
    } else {
      val path = s"${inputDir.toString}/$fileName"
      new DataOutputStream(new FileOutputStream(path))
    }
    out.write(contents, 0, contents.length)
    out.close()
  }

  /**
   * This code will test the behaviors of WholeTextFileRecordReader based on local disk. There are
   * three aspects to check:
   *   1) Whether all files are read;
   *   2) Whether paths are read correctly;
   *   3) Does the contents be the same.
   */
  test("Correctness of WholeTextFileRecordReader.") {
    CompressionType.values.foreach { compressionType =>
      withTempDir { dir =>
        logInfo(s"Local disk address is ${dir.toString}.")

        WholeTextFileRecordReaderSuite.files.foreach { case (filename, contents) =>
          createNativeFile(dir, filename, contents, compressionType)
        }

        val res = sc.wholeTextFiles(dir.toString, 3).collect()

        assert(res.length === WholeTextFileRecordReaderSuite.fileNames.length,
          "Number of files read out does not fit with the actual value.")

        for ((filename, contents) <- res) {
          val shortName = compressionType match {
            case CompressionType.NONE => filename.split('/').last
            case _ => filename.split('/').last.split('.').head
          }
          assert(WholeTextFileRecordReaderSuite.fileNames.contains(shortName),
            s"Missing file name $filename.")
          assert(contents === new Text(WholeTextFileRecordReaderSuite.files(shortName)).toString,
            s"file $filename contents can not match.")
        }
      }
    }
  }
}

/**
 * Files to be tested are defined here.
 */
object WholeTextFileRecordReaderSuite {
  private val testWords: IndexedSeq[Byte] = "Spark is easy to use.\n".map(_.toByte)

  private val fileNames = Array("part-00000", "part-00001", "part-00002")
  private val fileLengths = Array(10, 100, 1000)

  private val files = fileLengths.zip(fileNames).map { case (upperBound, filename) =>
    filename -> LazyList.continually(testWords.toList.to(LazyList)).flatten.take(upperBound).toArray
  }.toMap

  object CompressionType extends Enumeration {
    type CompressionType = Value
    val NONE, GZ, GZIP, ZST, ZSTD = Value
  }
}
