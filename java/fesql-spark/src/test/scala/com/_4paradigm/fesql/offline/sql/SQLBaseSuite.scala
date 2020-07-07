package com._4paradigm.fesql.offline.sql

import java.io.{File, FileInputStream}
import java.sql.{Date, Timestamp}
import java.text.SimpleDateFormat

import com._4paradigm.fesql.offline.api.{FesqlDataframe, FesqlSession}
import com._4paradigm.fesql.offline.SparkTestSuite
import com._4paradigm.fesql.sqlcase.model._
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag


class SQLBaseSuite extends SparkTestSuite {

  private val logger = LoggerFactory.getLogger(this.getClass)

  final private val rootDir = {
    val pwd = new File(System.getProperty("user.dir"))
    if (pwd.getAbsolutePath.endsWith("fesql-spark")) {
      pwd.getParentFile.getParentFile // ../../
    } else {
      pwd.getParentFile // ../
    }
  }

  def testCases(yamlPath: String) {
    val caseFile = loadYaml[CaseFile](yamlPath)
    caseFile.getCases.asScala.filter(c => needFilter(c)).foreach(c => testCase(c))
  }

  def needFilter(sqlCase: SQLCase): Boolean = {
    sqlCase.getMode != ("offline-unsupport") &&
      (sqlCase.getTags == null || ((!sqlCase.getTags.asScala.contains("TODO")) && (!sqlCase.getTags.asScala.contains("todo"))))
  }

  def createSQLString(sql: String, inputNames: ListBuffer[(Int, String)]): String = {
    var new_sql = sql
    inputNames.foreach(item => {
      new_sql = sql.replaceAll("\\{" + item._1 + "\\}", item._2)
    })
    new_sql
  }

  def testCase(sqlCase: SQLCase): Unit = {
    test(SQLBaseSuite.getTestName(sqlCase)) {
      logger.info(s"Test ${sqlCase.getId}:${sqlCase.getDesc}")

      // TODO: may set config of Map("fesql.group.partitions" -> 1)
      val spark = new FesqlSession(getSparkSession)

      var table_id = 0
      val inputNames = mutable.ListBuffer[(Int, String)]()
      sqlCase.getInputs.asScala.foreach(desc => {
        val (name, df) = loadInputData(desc, table_id)
        FesqlDataframe(spark, df).createOrReplaceTempView(name)
        inputNames += Tuple2[Int, String](table_id, name)
      })

      val sql = createSQLString(sqlCase.getSql, inputNames)
      if (sqlCase.getExpect != null && !sqlCase.getExpect.getSuccess) {
        assertThrows[java.lang.RuntimeException] {
          spark.sql(sql).sparkDf
        }
      } else {
        val df = spark.sql(sql).sparkDf
        df.cache()
        df.show()
        if (sqlCase.getExpect != null) {
          checkOutput(df, sqlCase.getExpect)
        }

        // Run SparkSQL to test and compare the generated Spark dataframes
        if (sqlCase.isStandard_sql) {
          logger.info("Use the standard sql, test result with SparkSQL")
          // Remove the ";" in sql text
          var sqlText: String = sqlCase.getSql.trim
          if (sqlText.endsWith(";")) {
            sqlText = sqlText.substring(0, sqlText.length - 1)
          }
          checkTwoDataframe(df, spark.sparksql(sqlText).sparkDf)
        }
      }
    }
  }

  def checkTwoDataframe(df1: DataFrame, df2: DataFrame): Unit = {
    assert(df1.except(df2).count() == df2.except(df1).count())
  }

  def checkOutput(data: DataFrame, expect: OutputDesc): Unit = {
    val expectSchema = if (expect.getSchema != null) parseSchema(expect.getSchema) else parseSchema(expect.getColumns)
    assert(data.schema == expectSchema)

    val expectData = (if (expect.getData != null) parseData(expect.getData, expectSchema) else parseData(expect.getRows, expectSchema))
      .zipWithIndex.sortBy(_._1.mkString(","))

    val actualData = data.collect().map(_.toSeq.toArray)
      .zipWithIndex.sortBy(_._1.mkString(","))

    assert(expectData.lengthCompare(actualData.length) == 0,
      s"Output size mismatch, get ${actualData.length} but expect ${expectData.length}")

    val size = expectData.length
    for (i <- 0 until size) {
      val expectId = expectData(i)._2
      val expectArr = expectData(i)._1
      val outputArr = actualData(i)._1

      assert(expectArr.lengthCompare(outputArr.length) == 0,
        s"Row size mismatch at ${expectId}th row")

      expectArr.zip(outputArr).zipWithIndex.foreach {
        case ((expectVal, outputVal), colIdx) =>
          assert(compareVal(expectVal, outputVal, expectSchema(colIdx).dataType),
            s"${colIdx}th col mismatch at ${expectId}th row: " +
              s"expect $expectVal but get $outputVal\n" +
              s"Expect: ${expectArr.mkString(", ")}\n" +
              s"Output: ${outputArr.mkString(", ")}")
      }
    }
  }

  def compareVal(left: Any, right: Any, dtype: DataType): Boolean = {
    if (left == null) {
      return right == null
    } else if (right == null) {
      return left == null
    }
    dtype match {
      case FloatType =>
        math.abs(toFloat(left) - toFloat(right)) < 1e-5
      case DoubleType =>
        math.abs(toDouble(left) - toDouble(right)) < 1e-5
      case _ =>
        left == right
    }
  }

  def formatVal(value: Any): String = {
    value.toString
  }

  def toFloat(value: Any): Float = {
    value match {
      case f: Float => f
      case _ => value.toString.toFloat
    }
  }

  def toDouble(value: Any): Double = {
    value match {
      case f: Double => f
      case _ => value.toString.toDouble
    }
  }

  def loadInputData(inputDesc: InputDesc, table_id: Int): (String, DataFrame) = {
    val sess = getSparkSession
    val name = if (inputDesc.getName == null) "auto_t" + table_id else inputDesc.getName
    if (inputDesc.getResource != null) {
      val (_, df) = loadTable(inputDesc.getResource)
      name -> df
    } else {
      val schema = if (inputDesc.getSchema != null) parseSchema(inputDesc.getSchema) else parseSchema(inputDesc.getColumns)
      val data = (if (inputDesc.getData != null) parseData(inputDesc.getData, schema) else parseData(inputDesc.getRows, schema))
        .map(arr => Row.fromSeq(arr)).toList.asJava
      val df = sess.createDataFrame(data, schema)
      name -> df
    }
  }

  def loadTable(path: String): (String, DataFrame) = {
    val absPath = if (path.startsWith("/")) path else rootDir.getAbsolutePath + "/" + path
    val caseFile = loadYaml[TableFile](absPath)
    val tbl = caseFile.getTable
    val schema = if (tbl.getSchema != null) parseSchema(tbl.getSchema) else parseSchema(tbl.getColumns)
    val data = parseData(tbl.getData, schema)
      .map(arr => Row.fromSeq(arr)).toList.asJava
    val df = getSparkSession.createDataFrame(data, schema)
    tbl.getName -> df
  }

  def parseSchema(schema: String): StructType = {
    val parts = schema.split(",").map(_.trim).filter(_ != "").map(_.split(":"))
    parseSchema(parts);
  }

  def parseSchema(columns: java.util.List[String]): StructType = {

    val parts = columns.toArray.map(_.toString()).map(_.trim).filter(_ != "").map(_.reverse.replaceFirst(" ", ":").reverse.split(":"))
    parseSchema(parts);
  }


  def parseSchema(parts: Array[Array[String]]): StructType = {
    val fields = parts.map(part => {
      val colName = part(0)
      val typeName = part(1)
      val dataType = typeName match {
        case "i16" => ShortType
        case "int16" => ShortType
        case "int" => IntegerType
        case "i32" => IntegerType
        case "int32" => IntegerType
        case "i64" => LongType
        case "bigint" => LongType
        case "int64" => LongType
        case "float" => FloatType
        case "double" => DoubleType
        case "string" => StringType
        case "timestamp" => TimestampType
        case "date" => DateType
        case "bool" => BooleanType
        case _ => throw new IllegalArgumentException(
          s"Unknown type name $typeName")
      }
      StructField(colName, dataType)
    })
    StructType(fields)
  }

  def parseData(data: String, schema: StructType): Array[Array[Any]] = {
    val rows = data.split("\n").map(_.trim).filter(_ != "").map(_.split(",").map(_.trim))
    parseData(rows, schema)
  }

  def parseData(rows: java.util.List[java.util.List[String]], schema: StructType): Array[Array[Any]] = {

    val data = rows.asScala.map(_.asInstanceOf[java.util.List[Object]].asScala.map(_.toString()).toArray).toArray
    parseData(data, schema)
  }

  def parseData(rows: Array[Array[String]], schema: StructType): Array[Array[Any]] = {

    rows.flatMap(parts => {
      if (parts.length != schema.size) {
        logger.error(s"Broken line: $parts")
        None
      } else {
        Some(schema.zip(parts).map {
          case (field, str) =>
            if (str == "NULL" || str == "null") {
              null
            } else {
              field.dataType match {
                case ByteType => str.trim.toByte
                case ShortType => str.trim.toShort
                case IntegerType => str.trim.toInt
                case LongType => str.trim.toLong
                case FloatType => str.trim.toFloat
                case DoubleType => str.trim.toDouble
                case StringType => str
                case TimestampType => new Timestamp(str.trim.toLong)
                case DateType =>
                  new Date(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(str.trim + " 00:00:00").getTime)
                case BooleanType => str.trim.toBoolean
                case _ => throw new IllegalArgumentException(
                  s"Unknown type ${field.dataType}")
              }
            }
        }.toArray)
      }
    })
  }

  def loadYaml[T: ClassTag](path: String): T = {
    val yaml = new Yaml(new Constructor(implicitly[ClassTag[T]].runtimeClass))
    val absPath = if (path.startsWith("/")) path else rootDir.getAbsolutePath + "/" + path
    val is = new FileInputStream(absPath)
    try {
      yaml.load(is).asInstanceOf[T]
    } finally {
      is.close()
    }
  }
}


object SQLBaseSuite {

  private val testNameCounter = mutable.HashMap[String, Int]()

  def getTestName(sqlCase: SQLCase): String = {
    this.synchronized {
      val prefix = sqlCase.getId + "_" + sqlCase.getDesc
      testNameCounter.get(prefix) match {
        case Some(idx) =>
          val res = prefix + "-" + idx
          testNameCounter += prefix -> (idx + 1)
          res

        case None =>
          testNameCounter += prefix -> 1
          prefix
      }
    }
  }
}