package com.lucidworks.spark;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Indexes some docs into Solr and then verifies they were indexed correctly from Spark.
 */
public class BasicIndexingTest extends TestSolrCloudClusterSupport implements Serializable {

  protected transient JavaStreamingContext jssc;

  @Before
  public void setupSparkStreamingContext() {
    SparkConf conf = new SparkConf()
      .setMaster("local")
      .setAppName("test")
      .set("spark.default.parallelism", "1");
    jssc = new JavaStreamingContext(conf, new Duration(500));
  }

  @After
  public void stopSparkStreamingContext() {
    jssc.stop(true, true);
  }

  @Test
  public void testIndexing() throws Exception {
    // create a collection named "test"
    String confName = "testConfig";
    File confDir = new File("src/test/resources/conf");
    String testCollection = "test";
    int numShards = 1;
    int replicationFactor = 1;

    createCollection(testCollection, numShards, replicationFactor, confName, confDir);

    // Create a stream of input docs to be indexed
    String[] inputDocs = new String[] {
      "1,foo,bar",
      "2,foo,baz",
      "3,bar,baz"
    };

    // transform the test RDD into an input stream
    JavaRDD<String> input = jssc.sparkContext().parallelize(Arrays.asList(inputDocs),1);
    LinkedBlockingDeque<JavaRDD<String>> queue = new LinkedBlockingDeque<JavaRDD<String>>();
    queue.add(input);

    // map input data to SolrInputDocument objects to be indexed
    JavaDStream<SolrInputDocument> docs = jssc.queueStream(queue).map(
      new Function<String, SolrInputDocument>() {
        public SolrInputDocument call(String row) {
          String[] fields = row.split(",");
          SolrInputDocument doc = new SolrInputDocument();
          doc.setField("id", fields[0]);
          doc.setField("field1", fields[1]);
          doc.setField("field2", fields[2]);
          return doc;
        }
      }
    );

    // Send to Solr
    String zkHost = cluster.getZkServer().getZkAddress();
    SolrSupport.indexDStreamOfDocs(zkHost, testCollection, 1, docs);

    // Actually start processing the stream here ...
    jssc.start();

    // let the docs flow through the streaming job
    Thread.sleep(2000);

    // verify docs got indexed ... relies on soft auto-commits firing frequently
    SolrRDD solrRDD = new SolrRDD(zkHost, testCollection);
    JavaRDD<SolrDocument> resultsRDD =
      solrRDD.query(jssc.sparkContext(), new SolrQuery("*:*"), false);

    long numFound = resultsRDD.count();
    assertTrue("expected "+inputDocs.length+" docs in query results, but got "+numFound,
      numFound == inputDocs.length);

    JavaRDD<SolrDocument> doc1 = solrRDD.get(jssc.sparkContext(), "1");
    assertEquals("foo", doc1.collect().get(0).getFirstValue("field1"));
  }
}
