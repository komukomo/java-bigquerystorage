/*
 * Copyright 2019 Google LLC
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
 */

package com.example.bigquerystorage;

// [START bigquerystorage_quickstart]
import com.google.api.gax.rpc.ServerStream;
import com.google.cloud.bigquery.storage.v1.AvroRows;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.bigquery.storage.v1.ReadSession.TableModifiers;
import com.google.cloud.bigquery.storage.v1.ReadSession.TableReadOptions;
import com.google.common.base.Preconditions;
import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;

public class StorageSampleParallel {

  /*
   * SimpleRowReader handles deserialization of the Avro-encoded row blocks transmitted
   * from the storage API using a generic datum decoder.
   */
  private static class SimpleRowReader {

    private final DatumReader<GenericRecord> datumReader;

    // Decoder object will be reused to avoid re-allocation and too much garbage collection.
    private BinaryDecoder decoder = null;

    // GenericRecord object will be reused.
    private GenericRecord row = null;

    public SimpleRowReader(Schema schema) {
      Preconditions.checkNotNull(schema);
      datumReader = new GenericDatumReader<>(schema);
    }

    /**
     * Sample method for processing AVRO rows which only validates decoding.
     *
     * @param avroRows object returned from the ReadRowsResponse.
     */
    public long processRows(AvroRows avroRows) throws IOException {
      decoder =
          DecoderFactory.get()
              .binaryDecoder(avroRows.getSerializedBinaryRows().toByteArray(), decoder);

      long total = 0;
      while (!decoder.isEnd()) {
        // Reusing object row
        row = datumReader.read(row, decoder);
        total++;
      }
      return total;
    }
  }


  private static class Worker implements Runnable {
    BigQueryReadClient client;
    String avroSchema;
    String streamName;
    public Worker(BigQueryReadClient client, String avroSchema, String streamName) {
      this.client = client;
      this.avroSchema = avroSchema;
      this.streamName = streamName;
    }

    public void run() {
      long start = System.currentTimeMillis();
      SimpleRowReader reader =
          new SimpleRowReader(new Schema.Parser().parse(avroSchema));

      ReadRowsRequest readRowsRequest =
          ReadRowsRequest.newBuilder().setReadStream(streamName).build();

      // Process each block of rows as they arrive and decode using our simple row reader.
      ServerStream<ReadRowsResponse> stream = client.readRowsCallable().call(readRowsRequest);
      long total = 0;
      try {
        for (ReadRowsResponse response : stream) {
          // Preconditions.checkState(response.hasAvroRows());
          total += reader.processRows(response.getAvroRows());
        }
        long finish = System.currentTimeMillis();
        long timeElapsed = finish - start;
        System.out.printf("total: %d\n", total);
        System.out.printf("timeElapsed: %d ms\n", timeElapsed);
      } catch (IOException err) {
        throw new Error(err.toString());
      }
    }
  }

  public static void main(String... args) throws Exception {
    // Sets your Google Cloud Platform project ID.
    // String projectId = "YOUR_PROJECT_ID";
    String projectId = args[0];
    Integer snapshotMillis = null;
    if (args.length > 1) {
      snapshotMillis = Integer.parseInt(args[1]);
    }

    try (BigQueryReadClient client = BigQueryReadClient.create()) {
      String parent = String.format("projects/%s", projectId);

      // This example uses baby name data from the public datasets.
      String srcTable =
          String.format(
              "projects/%s/datasets/%s/tables/%s",
              "bigquery-public-data", "usa_names", "usa_1910_current");

      // We specify the columns to be projected by adding them to the selected fields,
      // and set a simple filter to restrict which rows are transmitted.
      TableReadOptions options =
          TableReadOptions.newBuilder()
              .addSelectedFields("name")
              .addSelectedFields("number")
              .addSelectedFields("state")
              .setRowRestriction("state = \"WA\"")
              .build();

      long start = System.currentTimeMillis();

      // Start specifying the read session we want created.
      ReadSession.Builder sessionBuilder =
          ReadSession.newBuilder()
              .setTable(srcTable)
              // This API can also deliver data serialized in Apache Avro format.
              // This example leverages Apache Avro.
              .setDataFormat(DataFormat.AVRO)
              .setReadOptions(options);

      // Optionally specify the snapshot time.  When unspecified, snapshot time is "now".
      if (snapshotMillis != null) {
        Timestamp t =
            Timestamp.newBuilder()
                .setSeconds(snapshotMillis / 1000)
                .setNanos((int) ((snapshotMillis % 1000) * 1000000))
                .build();
        TableModifiers modifiers = TableModifiers.newBuilder().setSnapshotTime(t).build();
        sessionBuilder.setTableModifiers(modifiers);
      }

      // Begin building the session creation request.
      CreateReadSessionRequest.Builder builder =
          CreateReadSessionRequest.newBuilder()
              .setParent(parent)
              .setReadSession(sessionBuilder)
              .setMaxStreamCount(1000);

      // Request the session creation.
      ReadSession session = client.createReadSession(builder.build());

      // SimpleRowReader reader =
      //     new SimpleRowReader(new Schema.Parser().parse(session.getAvroSchema().getSchema()));

      // Assert that there are streams available in the session.  An empty table may not have
      // data available.  If no sessions are available for an anonymous (cached) table, consider
      // writing results of a query to a named table rather than consuming cached results directly.
      System.out.printf("stream count %d\n", session.getStreamsCount());
      Preconditions.checkState(session.getStreamsCount() > 0);

      String avroSchema = session.getAvroSchema().getSchema();

      ArrayList<Thread> threads = new ArrayList<>();
      for (int i = 0; i < session.getStreamsCount(); i++) {
        String streamName = session.getStreams(i).getName();
        Thread th = new Thread(new Worker(client, avroSchema, streamName));
        th.start();
        threads.add(th);
      }
      for (int i = 0; i < session.getStreamsCount(); i++) {
        threads.get(i).join();
      }

      long finish = System.currentTimeMillis();
      long timeElapsed = finish - start;
      System.out.printf("ToalTimeElapsed: %d ms\n", timeElapsed);
    }
  }
}
// [END bigquerystorage_quickstart]
