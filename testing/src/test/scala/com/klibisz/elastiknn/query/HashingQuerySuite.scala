package com.klibisz.elastiknn.query

import java.io.File

import com.klibisz.elastiknn.mapper.VectorMapper
import com.klibisz.elastiknn.storage.UnsafeSerialization._
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.codecs.Codec
import org.apache.lucene.codecs.lucene84.Lucene84Codec
import org.apache.lucene.document.{Document, Field, FieldType}
import org.apache.lucene.index._
import org.apache.lucene.search.{HashingQueryJava, IndexSearcher, TermQuery, HashingQuery => LuceneHashingQuery}
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.util.BytesRef
import org.scalatest._

class HashingQuerySuite extends FunSuite with Matchers {

  val vecFieldType = new VectorMapper.FieldType("elastiknn_dense_float_vector")

  def vecHarness[W, R](): (IndexWriter => W) => (IndexReader => R) => Unit =
    harness(vecFieldType.indexAnalyzer.analyzer, new Lucene84Codec())

  def harness[W, R](analyzer: Analyzer, codec: Codec)(write: IndexWriter => W)(read: IndexReader => R): Unit = {
    val tmpDir = new File(s"/tmp/elastiknn-hashing-query-${System.currentTimeMillis()}")
    val indexDir = new MMapDirectory(tmpDir.toPath)
    val indexWriterCfg = new IndexWriterConfig(analyzer).setCodec(codec)
    val indexWriter = new IndexWriter(indexDir, indexWriterCfg)
    try {
      write(indexWriter)
      indexWriter.commit()
      indexWriter.forceMerge(1)
      val indexReader = DirectoryReader.open(indexDir)
      try read(indexReader)
      finally indexReader.close()
    } finally {
      indexWriter.close()
      tmpDir.delete()
    }
  }

  test("empty harness") {
    vecHarness() { (_: IndexWriter) =>
      Assertions.succeed
    } { (_: IndexReader) =>
      Assertions.succeed
    }
  }

  test("minimal id example") {
    vecHarness() { w =>
      val d = new Document()
      val ft = new FieldType()
      ft.setIndexOptions(IndexOptions.DOCS)
      d.add(new Field("id", "foo", ft))
      d.add(new Field("id", "bar", ft))
      w.addDocument(d)
    } { r =>
      val s = new IndexSearcher(r)
      val q = new TermQuery(new Term("id", "foo"))
      val dd = s.search(q, 10)
      dd.scoreDocs should have length 1
    }
  }

  test("minimal HashingQuery") {

    vecHarness() { w =>
      val d = new Document()
      d.add(new Field("vec", writeInt(42), vecFieldType))
      d.add(new Field("vec", writeInt(99), vecFieldType))
      w.addDocument(d)
    } { r =>
      val hashes = Array(new BytesRef(writeInt(42)), new BytesRef(writeInt(99)), new BytesRef(writeInt(22)))
      val q = new HashingQueryJava(
        "vec",
        hashes,
        10,
        r,
        (_: LeafReaderContext) =>
          (docId: Int, numMatchingHashes: Int) => {
            docId shouldBe 0
            numMatchingHashes shouldBe 2
            99d
        }
      )

      // val q = new LuceneHashingQuery("vec", terms, 2, f, r)
      val s = new IndexSearcher(r)
      val dd = s.search(q, 10)
      dd.scoreDocs should have length 1
      dd.scoreDocs.head.score shouldBe 99f
      dd.scoreDocs.head.doc shouldBe 0
      dd.scoreDocs.head.shardIndex shouldBe 0
    }
  }

}