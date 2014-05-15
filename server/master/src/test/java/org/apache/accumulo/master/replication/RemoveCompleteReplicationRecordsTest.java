/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.master.replication;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.WholeRowIterator;
import org.apache.accumulo.core.protobuf.ProtobufUtil;
import org.apache.accumulo.core.replication.ReplicationSchema.StatusSection;
import org.apache.accumulo.core.replication.ReplicationSchema.WorkSection;
import org.apache.accumulo.core.replication.ReplicationTarget;
import org.apache.accumulo.core.replication.StatusUtil;
import org.apache.accumulo.core.replication.proto.Replication.Status;
import org.apache.accumulo.server.replication.ReplicationTable;
import org.apache.hadoop.io.Text;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.google.common.collect.Iterables;

/**
 * 
 */
public class RemoveCompleteReplicationRecordsTest {

  private RemoveCompleteReplicationRecords rcrr;
  private MockInstance inst;
  private Connector conn;

  @Rule
  public TestName test = new TestName();
  
  @Before
  public void initialize() throws Exception {
    inst = new MockInstance(test.getMethodName());
    conn = inst.getConnector("root", new PasswordToken(""));
    rcrr = new RemoveCompleteReplicationRecords(conn);
  }

  @Test
  public void notYetReplicationRecordsIgnored() throws Exception {
    ReplicationTable.create(conn);
    BatchWriter bw = ReplicationTable.getBatchWriter(conn);
    int numRecords = 3;
    for (int i = 0; i < numRecords; i++) {
      String file = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
      Mutation m = new Mutation(file);
      StatusSection.add(m, new Text(Integer.toString(i)), StatusUtil.openWithUnknownLengthValue());
      bw.addMutation(m);
    }

    bw.close();

    Assert.assertEquals(numRecords, Iterables.size(ReplicationTable.getScanner(conn)));

    BatchScanner bs = ReplicationTable.getBatchScanner(conn, 1);
    bs.setRanges(Collections.singleton(new Range()));
    IteratorSetting cfg = new IteratorSetting(50, WholeRowIterator.class);
    bs.addScanIterator(cfg);
    bw = EasyMock.createMock(BatchWriter.class);

    EasyMock.replay(bw);

    rcrr.removeCompleteRecords(conn, ReplicationTable.NAME, bs, bw);
    bs.close();

    Assert.assertEquals(numRecords, Iterables.size(ReplicationTable.getScanner(conn)));
  }

  @Test
  public void partiallyReplicatedRecordsIgnored() throws Exception {
    ReplicationTable.create(conn);
    BatchWriter bw = ReplicationTable.getBatchWriter(conn);
    int numRecords = 3;
    Status.Builder builder = Status.newBuilder();
    builder.setClosed(false);
    builder.setEnd(10000);
    builder.setInfiniteEnd(false);
    for (int i = 0; i < numRecords; i++) {
      String file = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
      Mutation m = new Mutation(file);
      StatusSection.add(m, new Text(Integer.toString(i)), ProtobufUtil.toValue(builder.setBegin(1000*(i+1)).build()));
      bw.addMutation(m);
    }

    bw.close();

    Assert.assertEquals(numRecords, Iterables.size(ReplicationTable.getScanner(conn)));
    

    BatchScanner bs = ReplicationTable.getBatchScanner(conn, 1);
    bs.setRanges(Collections.singleton(new Range()));
    IteratorSetting cfg = new IteratorSetting(50, WholeRowIterator.class);
    bs.addScanIterator(cfg);
    bw = EasyMock.createMock(BatchWriter.class);

    EasyMock.replay(bw);

    // We don't remove any records, so we can just pass in a fake BW for both
    rcrr.removeCompleteRecords(conn, ReplicationTable.NAME, bs, bw);
    bs.close();

    Assert.assertEquals(numRecords, Iterables.size(ReplicationTable.getScanner(conn)));
  }

  @Test
  public void replicatedClosedWorkRecordsAreNotRemovedWithoutClosedStatusRecords() throws Exception {
    ReplicationTable.create(conn);
    BatchWriter replBw = ReplicationTable.getBatchWriter(conn);
    int numRecords = 3;

    Status.Builder builder = Status.newBuilder();
    builder.setClosed(false);
    builder.setEnd(10000);
    builder.setInfiniteEnd(false);

    // Write out numRecords entries to both replication and metadata tables, none of which are fully replicated
    for (int i = 0; i < numRecords; i++) {
      String file = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
      Mutation m = new Mutation(file);
      StatusSection.add(m, new Text(Integer.toString(i)), ProtobufUtil.toValue(builder.setBegin(1000*(i+1)).build()));
      replBw.addMutation(m);
    }

    // Add two records that we can delete
    String fileToRemove = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
    Mutation m = new Mutation(fileToRemove);
    StatusSection.add(m, new Text("5"), ProtobufUtil.toValue(builder.setBegin(10000).setEnd(10000).setClosed(false).build()));
    replBw.addMutation(m);

    numRecords++;

    fileToRemove = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
    m = new Mutation(fileToRemove);
    StatusSection.add(m, new Text("6"), ProtobufUtil.toValue(builder.setBegin(10000).setEnd(10000).setClosed(false).build()));
    replBw.addMutation(m);

    numRecords++;

    replBw.flush();

    // Make sure that we have the expected number of records in both tables
    Assert.assertEquals(numRecords, Iterables.size(ReplicationTable.getScanner(conn)));

    // We should not remove any records because they're missing closed status
    BatchScanner bs = ReplicationTable.getBatchScanner(conn, 1);
    bs.setRanges(Collections.singleton(new Range()));
    IteratorSetting cfg = new IteratorSetting(50, WholeRowIterator.class);
    bs.addScanIterator(cfg);

    try {
      Assert.assertEquals(0l, rcrr.removeCompleteRecords(conn, ReplicationTable.NAME, bs, replBw));
    } finally {
      bs.close();
      replBw.close();
    }
  }

  @Test
  public void replicatedClosedRowsAreRemoved() throws Exception {
    ReplicationTable.create(conn);
    BatchWriter replBw = ReplicationTable.getBatchWriter(conn);
    int numRecords = 3;

    Status.Builder builder = Status.newBuilder();
    builder.setClosed(false);
    builder.setEnd(10000);
    builder.setInfiniteEnd(false);

    // Write out numRecords entries to both replication and metadata tables, none of which are fully replicated
    for (int i = 0; i < numRecords; i++) {
      String file = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
      Mutation m = new Mutation(file);
      StatusSection.add(m, new Text(Integer.toString(i)), ProtobufUtil.toValue(builder.setBegin(1000*(i+1)).build()));
      replBw.addMutation(m);
    }

    Set<String> filesToRemove = new HashSet<>();
    int finalNumRecords = numRecords;

    // Add two records that we can delete
    String fileToRemove = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
    filesToRemove.add(fileToRemove);
    Mutation m = new Mutation(fileToRemove);
    ReplicationTarget target = new ReplicationTarget("peer1", "5", "5");
    Value value = ProtobufUtil.toValue(builder.setBegin(10000).setEnd(10000).setClosed(true).build());
    StatusSection.add(m, new Text("5"), value);
    WorkSection.add(m, target.toText(), value);
    replBw.addMutation(m);

    numRecords += 2;

    // Add a record with some stuff we replicated
    fileToRemove = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
    filesToRemove.add(fileToRemove);
    m = new Mutation(fileToRemove);
    target = new ReplicationTarget("peer1", "6", "6");
    StatusSection.add(m, new Text("6"), value);
    WorkSection.add(m, target.toText(), value);
    replBw.addMutation(m);

    numRecords += 2;

    replBw.flush();

    // Make sure that we have the expected number of records in both tables
    Assert.assertEquals(numRecords, Iterables.size(ReplicationTable.getScanner(conn)));

    // We should remove the two fully completed records we inserted
    BatchScanner bs = ReplicationTable.getBatchScanner(conn, 1);
    bs.setRanges(Collections.singleton(new Range()));
    IteratorSetting cfg = new IteratorSetting(50, WholeRowIterator.class);
    bs.addScanIterator(cfg);

    try {
      Assert.assertEquals(4l, rcrr.removeCompleteRecords(conn, ReplicationTable.NAME, bs, replBw));
    } finally {
      bs.close();
      replBw.close();
    }

    int actualRecords = 0;
    for (Entry<Key,Value> entry : ReplicationTable.getScanner(conn)) {
      Assert.assertFalse(filesToRemove.contains(entry.getKey().getRow().toString()));
      actualRecords++;
    }

    Assert.assertEquals(finalNumRecords, actualRecords);
  }

  @Test
  public void partiallyReplicatedEntriesPrecludeRowDeletion() throws Exception {
    ReplicationTable.create(conn);
    BatchWriter replBw = ReplicationTable.getBatchWriter(conn);
    int numRecords = 3;

    Status.Builder builder = Status.newBuilder();
    builder.setClosed(false);
    builder.setEnd(10000);
    builder.setInfiniteEnd(false);

    // Write out numRecords entries to both replication and metadata tables, none of which are fully replicated
    for (int i = 0; i < numRecords; i++) {
      String file = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
      Mutation m = new Mutation(file);
      StatusSection.add(m, new Text(Integer.toString(i)), ProtobufUtil.toValue(builder.setBegin(1000*(i+1)).build()));
      replBw.addMutation(m);
    }

    // Add two records that we can delete
    String fileToRemove = "/accumulo/wal/tserver+port/" + UUID.randomUUID();
    Mutation m = new Mutation(fileToRemove);
    ReplicationTarget target = new ReplicationTarget("peer1", "5", "5");
    Value value = ProtobufUtil.toValue(builder.setBegin(10000).setEnd(10000).setClosed(true).build());
    StatusSection.add(m, new Text("5"), value);
    WorkSection.add(m, target.toText(), value);
    target = new ReplicationTarget("peer2", "5", "5");
    WorkSection.add(m, target.toText(), value);
    target = new ReplicationTarget("peer3", "5", "5");
    WorkSection.add(m, target.toText(), ProtobufUtil.toValue(builder.setClosed(false).build()));
    replBw.addMutation(m);

    numRecords += 4;

    replBw.flush();

    // Make sure that we have the expected number of records in both tables
    Assert.assertEquals(numRecords, Iterables.size(ReplicationTable.getScanner(conn)));

    // We should remove the two fully completed records we inserted
    BatchScanner bs = ReplicationTable.getBatchScanner(conn, 1);
    bs.setRanges(Collections.singleton(new Range()));
    IteratorSetting cfg = new IteratorSetting(50, WholeRowIterator.class);
    bs.addScanIterator(cfg);

    try {
      Assert.assertEquals(0l, rcrr.removeCompleteRecords(conn, ReplicationTable.NAME, bs, replBw));
    } finally {
      bs.close();
      replBw.close();
    }
  }
}