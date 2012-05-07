/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
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
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.ArrayList;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.util.Bytes;

import com.yahoo.omid.tso.RowKey;
import com.yahoo.omid.Statistics;
import com.yahoo.omid.IsolationLevel;

/**
 * Provides transactional methods for accessing and modifying a given snapshot of data identified by an opaque
 * {@link TransactionState} object.
 *
 */
public class TransactionalTable extends HTable {

   public static long getsPerformed = 0;
   public static long elementsGotten = 0;
   public static long elementsRead = 0;
   public static long extraGetsPerformed = 0;
   public static double extraVersionsAvg = 3;
   
   private static int CACHE_VERSIONS_OVERHEAD = 3;
//   private int cacheVersions = 3;
   public double versionsAvg = 3;
   private static final double alpha = 0.975;
//   private static final double betha = 1.25;

//   private static Thread monitor = new ThroughputMonitor();
//   private static boolean started = false;
//   {
//      synchronized(monitor) {
//         if (!started) {
//            started = true;
//            monitor.start();
//         }
//      }
//   }

   public TransactionalTable(Configuration conf, byte[] tableName) throws IOException {
      super(conf, tableName);
   }

   public TransactionalTable(Configuration conf, String tableName) throws IOException {
      this(conf, Bytes.toBytes(tableName));
   }

   /**
    * Transactional version of {@link HTable#get(Get)}
    * 
    * @param transactionState Identifier of the transaction
    * @see HTable#get(Get)
    * @throws IOException
    */
   public Result get(TransactionState transactionState, final Get get) throws IOException {
      final long readTimestamp = transactionState.getStartTimestamp();

      if (IsolationLevel.checkForReadWriteConflicts)
         transactionState.addReadRow(new RowKey(get.getRow(), getTableName()));

      final Get tsget = new Get(get.getRow());
      TimeRange timeRange = get.getTimeRange();
      //Added by Maysam Yabandeh
      final long eldest = IsolationLevel.checkForWriteWriteConflicts ? -1 : //-1 means no eldest, i.e., do not worry about it
         transactionState.tsoclient.getEldest();//if we do not check for ww conflicts, we should take elders into account
      int nVersions = (int) (versionsAvg + CACHE_VERSIONS_OVERHEAD);
      //is not used anymore
      boolean nVersionsIsSet = false;
      long startTime = 0;
      long endTime = Math.min(timeRange.getMax(), readTimestamp + 1);
      if (eldest == -1 || eldest >= endTime) {//-1 means no eldest
         nVersionsIsSet = true;
         tsget.setTimeRange(startTime, endTime).setMaxVersions(nVersions);
      } else {//either from 0, or eldest, fetch all
         startTime = eldest;
         //Added by Maysam Yabandeh
         //for rw case, we need all the versions, no max
         tsget.setFilter(new MinVersionsFilter(startTime, endTime, nVersions));
      }
      //long startTime = timeRange.getMin();
      //tsget.setTimeRange(startTime, endTime).setMaxVersions((int) (versionsAvg + CACHE_VERSIONS_OVERHEAD));
      Map<byte[], NavigableSet<byte[]>> kvs = get.getFamilyMap();
      for (Map.Entry<byte[], NavigableSet<byte[]>> entry : kvs.entrySet()) {
         byte[] family = entry.getKey();
         NavigableSet<byte[]> qualifiers = entry.getValue();
         if (qualifiers == null || qualifiers.isEmpty()) {
            tsget.addFamily(family);
         } else {
            for (byte[] qualifier : qualifiers) {
               tsget.addColumn(family, qualifier);
            }
         }
      }
//      Result result;
//      Result filteredResult;
//      do {
//         result = super.get(tsget);
//         filteredResult = filter(super.get(tsget), readTimestamp, maxVersions);
//      } while (!result.isEmpty() && filteredResult == null);
      getsPerformed++;
      Result firstResult = super.get(tsget);
      //this if is for debugging
      if (firstResult == null || firstResult.list() == null || firstResult.list().size() == 0) {
         System.out.println("FFFFFFF row: " + Bytes.toString(get.getRow()) + " eldest= " + eldest + " start: " + startTime + " end: " + endTime);
         for(byte[] col : (NavigableSet<byte[]>)new ArrayList(get.getFamilyMap().values()).get(0)) 
            System.out.println(" c-col: " + Bytes.toString(col));
         for(byte[] col : (NavigableSet<byte[]>)new ArrayList(tsget.getFamilyMap().values()).get(0)) 
            System.out.println(" col(tsget): " + Bytes.toString(col) + " start " + eldest + " " + endTime + " " + " vs " + nVersions);
      }
      Result result = filter(transactionState, firstResult, readTimestamp, nVersionsIsSet, nVersions);
      Statistics.partialReportOver(Statistics.Tag.VSN_PER_CLIENT_GET);
      Statistics.partialReportOver(Statistics.Tag.GET_PER_CLIENT_GET);
      Statistics.partialReportOver(Statistics.Tag.ASKTSO);
      return result == null ? new Result() : result;
//      Scan scan = new Scan(get);
//      scan.setRetainDeletesInOutput(true);
//      ResultScanner rs = this.getScanner(transactionState, scan);
//      Result r = rs.next();
//      if (r == null) {
//         r = new Result();
//      }
//      return r;
   }

   /**
    * Transactional version of {@link HTable#delete(Delete)}
    * 
    * @param transactionState Identifier of the transaction
    * @see HTable#delete(Delete)
    * @throws IOException
    */
   public void delete(TransactionState transactionState, Delete delete) throws IOException {
      final long startTimestamp = transactionState.getStartTimestamp();
      boolean issueGet = false;

      final Put deleteP = new Put(delete.getRow(), startTimestamp);
      final Get deleteG = new Get(delete.getRow());
      Map<byte[], List<KeyValue>> fmap = delete.getFamilyMap();
      if (fmap.isEmpty()) {
         issueGet = true;
      }
      for (List<KeyValue> kvl : fmap.values()) {
         for (KeyValue kv : kvl) {
            switch(KeyValue.Type.codeToType(kv.getType())) {
            case DeleteColumn:
               deleteP.add(kv.getFamily(), kv.getQualifier(), startTimestamp, null);
               break;
            case DeleteFamily:
               deleteG.addFamily(kv.getFamily());
               issueGet = true;
               break;
            case Delete:
               if (kv.getTimestamp() == HConstants.LATEST_TIMESTAMP) {
                  deleteP.add(kv.getFamily(), kv.getQualifier(), startTimestamp, null);
                  break;
               } else {
                  throw new UnsupportedOperationException("Cannot delete specific versions on Snapshot Isolation.");
               }
            }
         }
      }
      if (issueGet) {
         Result result = this.get(deleteG);
         for (Entry<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> entryF : result.getMap().entrySet()) {
            byte[] family = entryF.getKey();
            for (Entry<byte[], NavigableMap<Long, byte[]>> entryQ : entryF.getValue().entrySet()) {
               byte[] qualifier = entryQ.getKey();
               deleteP.add(family, qualifier, null);
            }
         }
      }

      transactionState.addWrittenRow(new RowKeyFamily(delete.getRow(), getTableName(), deleteP.getFamilyMap()));
      
      put(deleteP);
   }

   /**
    * Transactional version of {@link HTable#put(Put)}
    * 
    * @param transactionState Identifier of the transaction
    * @see HTable#put(Put)
    * @throws IOException
    */
   public void put(TransactionState transactionState, Put put) throws IOException, IllegalArgumentException {
      final long startTimestamp = transactionState.getStartTimestamp();
//      byte[] startTSBytes = Bytes.toBytes(startTimestamp);
      // create put with correct ts
      final Put tsput = new Put(put.getRow(), startTimestamp);
      Map<byte[], List<KeyValue>> kvs = put.getFamilyMap();
      for (List<KeyValue> kvl : kvs.values()) {
         for (KeyValue kv : kvl) {
//            int tsOffset = kv.getTimestampOffset();
//            System.arraycopy(startTSBytes, 0, kv.getBuffer(), tsOffset, Bytes.SIZEOF_LONG);
            tsput.add(new KeyValue(kv.getRow(), kv.getFamily(), kv.getQualifier(), startTimestamp, kv.getValue()));
         }
      }

      // should add the table as well
      transactionState.addWrittenRow(new RowKeyFamily(put.getRow(), getTableName(), put.getFamilyMap()));

      put(tsput);
//      super.getConnection().getRegionServerWithRetries(
//            new ServerCallable<Boolean>(super.getConnection(), super.getTableName(), put.getRow()) {
//               public Boolean call() throws IOException {
//                  server.put(location.getRegionInfo().getRegionName(), tsput);
//                  return true;
//               }
//            });
   }
   /**
    * Transactional version of {@link HTable#getScanner(Scan)}
    * 
    * @param transactionState Identifier of the transaction
    * @see HTable#getScanner(Scan)
    * @throws IOException
    */
   public ResultScanner getScanner(TransactionState transactionState, Scan scan) throws IOException {
      Scan tsscan = new Scan(scan);
//      tsscan.setRetainDeletesInOutput(true);
//      int maxVersions = scan.getMaxVersions();
      tsscan.setMaxVersions((int) (versionsAvg + CACHE_VERSIONS_OVERHEAD));
      tsscan.setTimeRange(0, transactionState.getStartTimestamp() + 1);
      ClientScanner scanner = new ClientScanner(transactionState, tsscan, (int) (versionsAvg + CACHE_VERSIONS_OVERHEAD));
      scanner.initialize();
      return scanner;
   }

   //a wrapper for KeyValue and the corresponding Tc
   private class KeyValueTc {
      KeyValue kv = null;
      long Tc;
      //update kv if the new one is more recent
      void update(KeyValue newkv, long newTc) {
         if (kv == null || Tc < newTc) {
            kv = newkv;
            Tc = newTc;
         }
      }
      boolean isMoreRecentThan(long otherTc) {
         if (kv == null)
            return false;
         return (Tc > otherTc);
      }
      //Here I compare Tc with Ts of another keyvalue
      boolean isMoreRecentThan(KeyValue kvwithTs) {
         if (kv == null)
            return false;
         if (kvwithTs == null)
            return true;
         return (Tc > kvwithTs.getTimestamp());
      }
      boolean isMoreRecentThan(KeyValueTc other) {
         if (kv == null)
            return false;
         if (other.kv == null)
            return true;
         return (Tc > other.Tc);
      }
   }

   //Added by Maysam Yabandeh
   //This filter assumes that only one column is feteched
   //TODO: generalize it
   //Assume: the writes of all elders are either feteched and rejected in a previous get or are presents in this result variable
   //There are three kinds of committed values:
   //1: Normal values for which I have the commit timestamp Tc
   //2: Normal values for which the Tc is lost (Tc < Tmax)
   //3: Values written by failed elders, i.e., (i) elder, (ii) Tc < Tmax, (iii) Tc is retrivable form the failedElders list
   //The normal values could be read in order of Ts (since Ts order and Tc order is the same), but the all the values of elders must be read since Ts and Tc orders are not the same.
   private Result filter(TransactionState state, Result result, long startTimestamp, boolean nVersionsIsSet, int nVersions) throws IOException {
      Statistics.partialReport(Statistics.Tag.GET_PER_CLIENT_GET, 1);
      if (result == null || result.list() == null) {
         Statistics.fullReport(Statistics.Tag.EMPTY_GET, 1);
         return null;
      }
      List<KeyValue> kvs = result.list();
      Statistics.fullReport(Statistics.Tag.VSN_PER_HBASE_GET, kvs.size());
      Statistics.partialReport(Statistics.Tag.VSN_PER_CLIENT_GET, kvs.size());
      if (kvs.size() == 0)
         Statistics.fullReport(Statistics.Tag.EMPTY_GET, 1);
      ArrayList<KeyValue> resultContent = new ArrayList<KeyValue>();
      Long nextFetchMaxTimestamp = startTimestamp;
      KeyValueTc mostRecentFailedElder = new KeyValueTc();
      KeyValue mostRecentKeyValueWithLostTc = null;
      KeyValueTc mostRecentValueWithTc = new KeyValueTc();
      //start from the highest Ts and compare their Tc till you reach a one with lost Tc (Ts < Tmax) or valid Tc. Then read the rest of the list to make sure that values of elders are also read. Then among the normal value and the failedElder with highest Tc, choose one.
      for (KeyValue kv : kvs) {
         long Ts = kv.getTimestamp();
         if (Ts == startTimestamp) {//if it is my own write, return it
            resultContent.add(kv);
            return new Result(resultContent);
         }
         nextFetchMaxTimestamp = Math.min(nextFetchMaxTimestamp, Ts);
         if (!IsolationLevel.checkForWriteWriteConflicts) {
            //Case 3: Check for failed elder
            Long failedElderTc = state.tsoclient.failedElders.get(Ts);
            if (failedElderTc != null) {
               if (failedElderTc < startTimestamp)//if it could be a valid read
                  mostRecentFailedElder.update(kv, failedElderTc);
               continue;//if is is a failedElder, we are done with probing this kv
            }
         }
         if (mostRecentKeyValueWithLostTc != null) continue;//if it is an elder and we have already seen one with lost Tc, then it was in failedEdler as well.
         long Tc = state.tsoclient.commitTimestamp(Ts, startTimestamp);
         if (Tc == -2) continue;//invalid read
         if (Tc == -1) // means valid read with lost Tc
            //Case 2: Normal value with lost Tc
            mostRecentKeyValueWithLostTc = kv; //Note: a value with lost Tc could also be a failedElder, so do this check after failedEdler check
         else
            //Case 1: Normal with with Tc
            mostRecentValueWithTc.update(kv, Tc); //some kv might be from elders
         if (IsolationLevel.checkForWriteWriteConflicts)//then everything is in order, and the first version is enough
            break;
      }
      if (mostRecentValueWithTc.isMoreRecentThan(mostRecentFailedElder)) {
         resultContent.add(mostRecentValueWithTc.kv);
         return new Result(resultContent);
      }
      if (mostRecentFailedElder.isMoreRecentThan(mostRecentKeyValueWithLostTc)) {
         //if Ts < Tc(elder) => Tc < Tc(elder)
         //this is bacause otherwise tso would have detected the other txn as elder too
         resultContent.add(mostRecentFailedElder.kv);
         return new Result(resultContent);
      }
      if (mostRecentKeyValueWithLostTc != null) {
         resultContent.add(mostRecentKeyValueWithLostTc);
         return new Result(resultContent);
      }


      boolean isMoreLeft = true;
      if (kvs.size() != nVersions)
         isMoreLeft = false;
      if (!isMoreLeft)
         return null;
      // We need to fetch more versions
      // I assume there is at least one item
      KeyValue kv = kvs.get(0);
      Get get = new Get(kv.getRow());
      get.addColumn(kv.getFamily(), kv.getQualifier());
      //Added by Maysam Yabandeh: for the second tries setting max makes sense even for rw
      nVersionsIsSet = true;
      get.setMaxVersions(nVersions);
      get.setTimeRange(0, nextFetchMaxTimestamp);
      extraGetsPerformed++;
      result = this.get(get);
      result = filter(state, result, startTimestamp, nVersionsIsSet, nVersions);
      return result;
   }
   
   /*
   private Result filter(TransactionState state, Result result, long startTimestamp, int localVersions) throws IOException {
      if (result == null) {
         return null;
      }
      List<KeyValue> kvs = result.list();
      if (kvs == null) {
         return result;
      }
      Map<ByteArray, Map<ByteArray, Integer>> occurrences = new HashMap<ByteArray, Map<ByteArray,Integer>>();
      Map<ByteArray, Map<ByteArray, Long>> minTimestamp = new HashMap<ByteArray, Map<ByteArray,Long>>();
      List<KeyValue> nonDeletes = new ArrayList<KeyValue>();
      List<KeyValue> filtered = new ArrayList<KeyValue>();
      Map<ByteArray, Set<ByteArray>> read = new HashMap<ByteArray, Set<ByteArray>>();
      DeleteTracker tracker = new DeleteTracker();
      for (KeyValue kv : kvs) {
         ByteArray family = new ByteArray(kv.getFamily());
         ByteArray qualifier = new ByteArray(kv.getQualifier());
         Set<ByteArray> readQualifiers = read.get(family);
         if (readQualifiers == null) {
            readQualifiers = new HashSet<ByteArray>();
            read.put(family, readQualifiers);
         } else if (readQualifiers.contains(qualifier)) continue;
//         RowKey rk = new RowKey(kv.getRow(), getTableName());
         if (state.tsoclient.validRead(kv.getTimestamp(), startTimestamp)) {
            if (!tracker.addDeleted(kv))
               nonDeletes.add(kv);
            {
               // Read valid value
               readQualifiers.add(qualifier);
               
//                statistics
//               elementsGotten++;
               Map<ByteArray, Integer> occurrencesCols = occurrences.get(family);
               Integer times = null;
               if (occurrencesCols != null) {
                  times = occurrencesCols.get(qualifier);
               }
               if (times != null) {
//                  elementsRead += times;
                  versionsAvg = times > versionsAvg ? times : alpha * versionsAvg + (1 - alpha) * times;
//                  extraVersionsAvg = times > extraVersionsAvg ? times : alpha * extraVersionsAvg + (1 - alpha) * times;
               } else {
//                  elementsRead++;
                  versionsAvg = alpha * versionsAvg + (1 - alpha);
//                  extraVersionsAvg = alpha * extraVersionsAvg + (1 - alpha);
               }
            }
         } else {
            Map<ByteArray, Integer> occurrencesCols = occurrences.get(family);
            Map<ByteArray, Long> minTimestampCols = minTimestamp.get(family);
            if (occurrencesCols == null) {
               occurrencesCols = new HashMap<ByteArray, Integer>();
               minTimestampCols = new HashMap<ByteArray, Long>();
               occurrences.put(family, occurrencesCols);
               minTimestamp.put(family, minTimestampCols);
            }
            Integer times = occurrencesCols.get(qualifier);
            Long timestamp = minTimestampCols.get(qualifier);
            if (times == null) {
               times = 0;
               timestamp = kv.getTimestamp();
            }
            times++;
            timestamp = Math.min(timestamp, kv.getTimestamp());
            if (times == localVersions) {
               // We need to fetch more versions
               Get get = new Get(kv.getRow());
               get.addColumn(kv.getFamily(), kv.getQualifier());
               get.setMaxVersions(localVersions);
               Result r;
               GOTRESULT: do {
                  extraGetsPerformed++;
                  get.setTimeRange(0, timestamp);
                  r = this.get(get);
                  List<KeyValue> list = r.list();
                  if (list == null) break;
                  for (KeyValue t : list) {
                     times++;
                     timestamp = Math.min(timestamp, t.getTimestamp());
//                     rk = new RowKey(kv.getRow(), getTableName());
                     if (state.tsoclient.validRead(t.getTimestamp(), startTimestamp)) {
                        if (!tracker.addDeleted(t))
                           nonDeletes.add(t);
                        readQualifiers.add(qualifier);
                        elementsGotten++;
                        elementsRead += times;
                        versionsAvg = times > versionsAvg ? times : alpha * versionsAvg + (1 - alpha) * times;
                        extraVersionsAvg = times > extraVersionsAvg ? times : alpha * extraVersionsAvg + (1 - alpha) * times;
                        break GOTRESULT;
                     }
                  }
               } while (r.size() == localVersions);
            } else {
               occurrencesCols.put(qualifier, times);
               minTimestampCols.put(qualifier, timestamp);
            }
         }
      }
      for (KeyValue kv : nonDeletes) {
         if (!tracker.isDeleted(kv)) {
            filtered.add(kv);
         }
      }
//      cacheVersions = (int) versionsAvg;
      if (filtered.isEmpty()) {
         return null;
      }
      return new Result(filtered);
   }
   */
   
   private class DeleteTracker {
      Map<ByteArray, Long> deletedRows = new HashMap<ByteArray, Long>();
      Map<ByteArray, Long> deletedFamilies = new HashMap<ByteArray, Long>();
      Map<ByteArray, Long> deletedColumns = new HashMap<ByteArray, Long>();
      
      public boolean addDeleted(KeyValue kv) {
         if (kv.getValue().length == 0) {
            deletedColumns.put(new ByteArray(Bytes.add(kv.getFamily(), kv.getQualifier())), kv.getTimestamp());
            return true;
         }
         return false;
      }
      
      public boolean isDeleted(KeyValue kv) {
         Long timestamp;
         timestamp = deletedRows.get(new ByteArray(kv.getRow()));
         if (timestamp != null && kv.getTimestamp() < timestamp) return true;
         timestamp = deletedFamilies.get(new ByteArray(kv.getFamily()));
         if (timestamp != null && kv.getTimestamp() < timestamp) return true;
         timestamp = deletedColumns.get(new ByteArray(Bytes.add(kv.getFamily(), kv.getQualifier())));
         if (timestamp != null && kv.getTimestamp() < timestamp) return true;
         return false;
      }
   }

   protected class ClientScanner extends HTable.ClientScanner {
      private TransactionState state;
      private int maxVersions;

      ClientScanner(TransactionState state, Scan scan, int maxVersions) {
         super(scan);
         this.state = state;
         this.maxVersions = maxVersions;
      }

      @Override
      public Result next() throws IOException {
         Result result;
         Result filteredResult;
         do {
            result = super.next();
            filteredResult = filter(state, result, state.getStartTimestamp(), true, maxVersions);
         } while(result != null && filteredResult == null);
         if (result != null) {
            state.addReadRow(new RowKey(result.getRow(), getTableName()));
         }
         return filteredResult;
      }
      
      @Override
      public Result[] next(int nbRows) throws IOException {
         Result [] results = super.next(nbRows);
         for (int i = 0; i < results.length; i++) {
            results[i] = filter(state, results[i], state.getStartTimestamp(), true, maxVersions);
            if (results[i] != null) {
               state.addReadRow(new RowKey(results[i].getRow(), getTableName()));
            }
         }
         return results;
      }

   }
   
//   public static class ThroughputMonitor extends Thread {
//      private static final Log LOG = LogFactory.getLog(ThroughputMonitor.class);
//      
//      /**
//       * Constructor
//       */
//      public ThroughputMonitor() {
//      }
//      
//      @Override
//      public void run() {
//         try {
//            long oldAskedTSO = TSOClient.askedTSO;
//            long oldElementsGotten = TransactionalTable.elementsGotten;
//            long oldElementsRead = TransactionalTable.elementsRead;
//            long oldExtraGetsPerformed = TransactionalTable.extraGetsPerformed;
//            long oldGetsPerformed = TransactionalTable.getsPerformed;
//            for (;;) {
//               Thread.sleep(10000);
//
//               long newGetsPerformed = TransactionalTable.getsPerformed;
//               long newElementsGotten = TransactionalTable.elementsGotten;
//               long newElementsRead = TransactionalTable.elementsRead;
//               long newExtraGetsPerformed = TransactionalTable.extraGetsPerformed;
//               long newAskedTSO = TSOClient.askedTSO;
//               
//               System.out.println(String.format("TSO CLIENT: GetsPerformed: %d ElsGotten: %d ElsRead: %d ExtraGets: %d AskedTSO: %d AvgVersions: %f",
//                     newGetsPerformed - oldGetsPerformed,
//                     newElementsGotten - oldElementsGotten,
//                     newElementsRead - oldElementsRead,
//                     newExtraGetsPerformed - oldExtraGetsPerformed,
//                     newAskedTSO - oldAskedTSO,
//                     TransactionalTable.extraVersionsAvg)
//                 );
//
//               oldAskedTSO = newAskedTSO;
//               oldElementsGotten = newElementsGotten;
//               oldElementsRead = newElementsRead;
//               oldExtraGetsPerformed = newExtraGetsPerformed;
//               oldGetsPerformed = newGetsPerformed;
//            }
//         } catch (InterruptedException e) {
//            // Stop monitoring asked
//            return;
//         }
//      }
//   }

}