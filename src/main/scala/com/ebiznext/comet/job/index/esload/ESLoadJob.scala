/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package com.ebiznext.comet.job.index.esload

import com.ebiznext.comet.config.Settings
import com.ebiznext.comet.schema.handlers.{SchemaHandler, StorageHandler}
import com.ebiznext.comet.schema.model.Schema
import com.ebiznext.comet.utils.{JobResult, SparkJob, SparkJobResult}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.types.StructField
import org.apache.commons.httpclient.auth.AuthScope
import org.apache.commons.httpclient.UsernamePasswordCredentials
import org.apache.commons.httpclient.methods.{DeleteMethod, PutMethod, StringRequestEntity}

import scala.util.{Failure, Success, Try}
import org.apache.spark.sql.functions._

import scala.collection.JavaConverters._

class ESLoadJob(
  cliConfig: ESLoadConfig,
  storageHandler: StorageHandler,
  schemaHandler: SchemaHandler
)(implicit val settings: Settings)
    extends SparkJob {

  /** Set extra spark conf here otherwise it will be too late once the spark session is created.
    * @return
    */
  override def withExtraSparkConf(): Map[String, String] = cliConfig.options

  val esresource = Some(("es.resource.write", s"${cliConfig.getResource()}"))
  val esId = cliConfig.id.map("es.mapping.id" -> _)
  val esCliConf = cliConfig.options ++ List(esresource, esId).flatten.toMap
  val path = cliConfig.getDataset()
  val format = cliConfig.format
  val dataset = cliConfig.dataset

  override def name: String = s"Index $path"

  /** Just to force any spark job to implement its entry point within the "run" method
    *
    * @return
    *   : Spark Session used for the job
    */
  override def run(): Try[JobResult] = {
    logger.info(s"Indexing resource ${cliConfig.getResource()} with $cliConfig")
    val inputDF =
      path match {
        case Left(path) =>
          format match {
            case "json" =>
              session.read
                .option("multiline", value = true)
                .json(path.toString)

            case "json-array" =>
              val jsonDS = session.read.textFile(path.toString)
              session.read.json(jsonDS)

            case "parquet" =>
              session.read.parquet(path.toString)
          }
        case Right(df) =>
          df
      }

    // Convert timestamp field to ISO8601 date time, so that ES Hadoop can handle it correctly.
    val df = cliConfig.getTimestampCol().map { tsCol =>
      inputDF
        .withColumn("comet_es_tmp", date_format(col(tsCol), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        .drop(tsCol)
        .withColumnRenamed("comet_es_tmp", tsCol)
    } getOrElse inputDF

    val content = cliConfig.mapping.map(storageHandler.read).getOrElse {
      val dynamicTemplate = for {
        domain <- schemaHandler.getDomain(cliConfig.domain)
        schema <- domain.schemas.find(_.name == cliConfig.schema)
      } yield schema.mapping(domain.mapping(schema), domain.name, schemaHandler)

      dynamicTemplate.getOrElse {
        // Handle datasets without YAML schema
        // We handle only index name like idx-{...}
        Schema.mapping(
          cliConfig.domain,
          cliConfig.schema,
          StructField("ignore", df.schema),
          schemaHandler
        )
      }
    }
    logger.info(
      s"Registering template ${cliConfig.domain.toLowerCase}_${cliConfig.schema.toLowerCase} -> $content"
    )
    val esOptions = settings.comet.elasticsearch.options.asScala.toMap
    val host: String = esOptions.getOrElse("es.nodes", "localhost")
    val port = esOptions.getOrElse("es.port", "9200").toInt
    val ssl = esOptions.getOrElse("es.net.ssl", "false").toBoolean
    val protocol = if (ssl) "https" else "http"
    val username = esOptions.get("net.http.auth.user")
    val password = esOptions.get("net.http.auth.password")

    val client = new org.apache.commons.httpclient.HttpClient()
    (username, password) match {
      case (Some(username), Some(password)) =>
        val defaultcreds = new UsernamePasswordCredentials(username, password)
        client
          .getState()
          .setCredentials(new AuthScope(host, port), defaultcreds)
      case (_, _) =>
    }

    val templateUri = s"$protocol://$host:$port/_template/${cliConfig.getIndexName()}"
    val delMethod = new DeleteMethod(templateUri)
    delMethod.setRequestHeader("Content-Type", "application/json")
    val _ = client.executeMethod(delMethod)

    val putMethod = new PutMethod(templateUri)
    val requestEntity = new StringRequestEntity(content, "application/json", "UTF-8")
    putMethod.setRequestEntity(requestEntity)
    val responseCode = client.executeMethod(putMethod)

    val ok = (200 to 299) contains responseCode
    if (ok) {
      val allConf = esOptions.toList ++ esCliConf.toList
      logger.whenDebugEnabled {
        logger.debug(s"sending ${df.count()} documents to Elasticsearch using $allConf")
      }
      val writer = allConf
        .foldLeft(df.write) { case (w, (k, v)) => w.option(k, v) }
        .format("org.elasticsearch.spark.sql")
        .mode(SaveMode.Overwrite)
      if (settings.comet.isElasticsearchSupported())
        writer.save(cliConfig.getResource())
      Success(SparkJobResult(None))
    } else {
      Failure(throw new Exception("Failed to create template"))
    }
  }
}
