import org.apache.spark.sql.functions._
import org.apache.spark.SparkContext
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rdd.read.AlignmentRecordRDD
import org.bdgenomics.adam.sql.AlignmentRecord
import org.bdgenomics.adam.rdd.read.{ AlignmentRecordRDD, AnySAMOutFormatter }
import java.io.File
import sys.process._
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.expressions.Window


def getListOfFiles(dir: File, extensions: List[String]): List[File] = {
    dir.listFiles.filter(_.isFile).toList.filter { file =>
        extensions.exists(file.getName.endsWith(_))
    }
}

val barcodes = (spark.read.format("csv")
        .option("header", "true")
        .load("./barcodes.csv"))
        .withColumnRenamed("sequence", "bc_sequence")


object Hamming {
  def compute(s1: String, s2: String): Int = {
    if (s1.length != s2.length)
      throw new IllegalArgumentException()
    (s1.toList).zip(s2.toList)
               .filter(current => current._1 != current._2)
               .length
  }
}

def hamming(sequence: String, bc: String): String = {
Hamming.compute(sequence.takeRight(bc.length()), bc).toString
}

val hammingUDF = udf[String, String, String](hamming)

val files = getListOfFiles(new File("./"), List("bam"))

val splitZA = udf((attributes:String) => {
  if (attributes.contains("ZA")) {attributes.toString.split("ZA:i:").last.split("\t")(0)
}
  else "0"
})

files.par.foreach(bam_path_temp => {

println(s"file is $bam_path_temp")

var bam_path = bam_path_temp.toString.split("/").last

var reads = sc.loadAlignments(bam_path.toString)

reads.transformDataset(df => {

var temp = df.toDF()

temp.withColumn("oldZA", splitZA($"attributes")).withColumn("ZA", $"oldZA" cast "Int" as "oldZA").withColumn("seqLength", length($"sequence")).filter($"mapq" > 4 and $"ZA" === $"seqLength").join(barcodes, hammingUDF(df("sequence"), barcodes("bc_sequence")) < 3).withColumn("bc1", $"recordGroupSample" cast "String" as "recordGroupSample").withColumn("barcode", concat(lit("A"), $"recordGroupSample", $"id")) .as[org.bdgenomics.adam.sql.AlignmentRecord]}).saveAsSam("demux_" + bam_path, asSingleFile=true)})

//command to exit spark shell
System.exit(0)


