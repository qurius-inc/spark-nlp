package com.johnsnowlabs.util

import org.apache.spark.ml.PipelineModel
import org.apache.spark.sql.SparkSession

import scala.collection.mutable.ArrayBuffer

object CoNLLGenerator {

  def exportConllFiles(spark: SparkSession, filesPath: String, pipelineModel: PipelineModel, outputPath: String): Unit = {
    import spark.implicits._ //for toDS and toDF

    val data = spark.sparkContext.wholeTextFiles(filesPath).toDS.toDF("filename", "text")

    val POSdataset = pipelineModel.transform(data)

    val newPOSDataset = POSdataset.select("finished_token", "finished_pos", "finished_token_metadata").
      as[(Array[String], Array[String], Array[(String, String)])]

    val CoNLLDataset = newPOSDataset.flatMap(row => {
      val newColumns: ArrayBuffer[(String, String, String, String)] = ArrayBuffer()
      val columns = (row._1 zip row._2 zip row._3.map(_._2.toInt)).map{case (a,b) => (a._1, a._2, b)}
      var sentenceId = 1
      columns.foreach(a => {
        if (a._3 != sentenceId){
          newColumns.append(("", "", "", ""))
          sentenceId = a._3
        }
        newColumns.append((a._1, a._2, a._2, "O"))
      })
      newColumns
    })
    CoNLLDataset.coalesce(1).write.format("com.databricks.spark.csv").
      option("delimiter", " ").
      save(outputPath)
  }

  def exportConllFiles(spark: SparkSession, filesPath: String, pipelinePath: String, outputPath: String): Unit = {
    val model = PipelineModel.load(pipelinePath)
    exportConllFiles(spark, filesPath, model, outputPath)
  }

}
