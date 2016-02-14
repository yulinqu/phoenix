/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end.index;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.SimpleRegionObserver;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.end2end.BaseOwnClusterHBaseManagedTimeIT;
import org.apache.phoenix.end2end.NeedsOwnMiniClusterTest;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.hbase.index.Indexer;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.StringUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.Maps;
/**
 * 
 * Test for failure of region server to write to index table.
 * For some reason dropping tables after running this test
 * fails unless it runs its own mini cluster. 
 * 
 * 
 * @since 2.1
 */

@Category(NeedsOwnMiniClusterTest.class)
public class ReadOnlyIndexFailureIT extends BaseOwnClusterHBaseManagedTimeIT {
    private static final String FAIL_ON_FIRST_PUT = "bbb";

    private String tableName;
    private String indexName;
    private String fullTableName;
    private String fullIndexName;

    public ReadOnlyIndexFailureIT() {
        this.tableName = TestUtil.DEFAULT_DATA_TABLE_NAME;
        this.indexName = "IDX";
        this.fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
        this.fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
    }

    @BeforeClass
    public static void doSetup() throws Exception {
        Map<String, String> serverProps = Maps.newHashMapWithExpectedSize(10);
        serverProps.put("hbase.client.retries.number", "2");
        serverProps.put("hbase.client.pause", "5000");
        serverProps.put("hbase.balancer.period", String.valueOf(Integer.MAX_VALUE));
        serverProps.put(QueryServices.INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_TIME_ATTRIB, "0");
        serverProps.put(QueryServices.INDEX_FAILURE_BLOCK_WRITE, "true");
        serverProps.put(QueryServices.INDEX_FAILURE_HANDLING_REBUILD_ATTRIB, "true");
        serverProps.put(QueryServices.INDEX_FAILURE_HANDLING_REBUILD_INTERVAL_ATTRIB, "1000");
        serverProps.put("hbase.coprocessor.region.classes", FailingRegionObserver.class.getName());
        serverProps.put("hbase.coprocessor.abortonerror", "false");
        serverProps.put(Indexer.CHECK_VERSION_CONF_KEY, "false");
        Map<String, String> clientProps = 
                Collections.singletonMap(QueryServices.TRANSACTIONS_ENABLED, "true");
        NUM_SLAVES_BASE = 4;
        setUpTestDriver(new ReadOnlyProps(serverProps.entrySet().iterator()), 
                new ReadOnlyProps(clientProps.entrySet().iterator()));
    }

    @Test
    public void testWriteFailureReadOnlyLocalIndex() throws Exception {
        helpTestWriteFailureReadOnlyIndex(true);
    }

    @Test
    public void testWriteFailureReadOnlyIndex() throws Exception {
        helpTestWriteFailureReadOnlyIndex(false);
    }

    public void helpTestWriteFailureReadOnlyIndex(boolean localIndex) throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        try (Connection conn = driver.connect(url, props)) {
            String query;
            ResultSet rs;
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "CREATE TABLE " + fullTableName + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)");
            query = "SELECT * FROM " + fullTableName;
            rs = conn.createStatement().executeQuery(query);
            assertFalse(rs.next());

            if(localIndex) {
                conn.createStatement().execute(
                        "CREATE LOCAL INDEX " + indexName + " ON " + fullTableName 
                        + " (v1) INCLUDE (v2)");
            } else {
                conn.createStatement().execute(
                        "CREATE INDEX " + indexName + " ON " + fullTableName 
                        + " (v1) INCLUDE (v2)");
            }

            query = "SELECT * FROM " + fullIndexName;
            rs = conn.createStatement().executeQuery(query);
            assertFalse(rs.next());

            // Verify the metadata for index is correct.
            rs = conn.getMetaData().getTables(null, 
                    StringUtil.escapeLike(TestUtil.DEFAULT_SCHEMA_NAME), indexName,
                    new String[] { PTableType.INDEX.toString() });
            assertTrue(rs.next());
            assertEquals(indexName, rs.getString(3));
            assertEquals(PIndexState.ACTIVE.toString(), rs.getString("INDEX_STATE"));
            assertFalse(rs.next());

            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + fullTableName 
                    + " VALUES(?,?,?)");
            stmt.setString(1, "1");
            stmt.setString(2, "aaa");
            stmt.setString(3, "a1");
            stmt.execute();
            conn.commit();

            stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?,?)");
            stmt.setString(1, "2");
            stmt.setString(2, FAIL_ON_FIRST_PUT);
            stmt.setString(3, "b2");
            stmt.execute();
            try {
                conn.commit();
                fail();
            } catch (SQLException e) {
            }

            // Only successfully committed row should be seen
            query = "SELECT /*+ NO_INDEX*/ v1 FROM " + fullTableName;
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("aaa", rs.getString(1));
            assertFalse(rs.next());
            
            // Verify the metadata for index is correct.
            rs = conn.getMetaData().getTables(null, 
                    StringUtil.escapeLike(TestUtil.DEFAULT_SCHEMA_NAME), indexName,
                    new String[] { PTableType.INDEX.toString() });
            assertTrue(rs.next());
            assertEquals(indexName, rs.getString(3));
            // the index is always active for tables upon index table write failure
            assertEquals(PIndexState.ACTIVE.toString(), rs.getString("INDEX_STATE"));
            assertFalse(rs.next());

            // if the table is transactional the write to the index table will fail because the
            // index has not been disabled
            // Verify UPSERT on data table is blocked  after index write failed
            stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?,?)");
            stmt.setString(1, "3");
            stmt.setString(2, "ccc");
            stmt.setString(3, "3c");
            try {
                stmt.execute();
                /* Writes would be blocked */
                conn.commit();
                fail();
            } catch (SQLException e) {
                assertEquals(SQLExceptionCode.INDEX_FAILURE_BLOCK_WRITE.getErrorCode(), e.getErrorCode());
            }

            // Second attempt at writing will succeed
            int retries = 0;
            do {
                Thread.sleep(5 * 1000); // sleep 5 secs
                if(!hasIndexDisableTimestamp(conn, indexName)){
                    break;
                }
                if (++retries == 5) {
                    fail("Failed to rebuild index with allowed time");
                }
            } while(true);

            // Verify UPSERT on data table still work after index table is recreated
            stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?,?)");
            stmt.setString(1, "4");
            stmt.setString(2, "ddd");
            stmt.setString(3, "4d");
            stmt.execute();
            conn.commit();

            // verify index table has data
            query = "SELECT count(1) FROM " + indexName;
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            
            query = "SELECT v1 FROM " + fullTableName;
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("aaa", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("bbb", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("ddd", rs.getString(1));
            assertFalse(rs.next());

            query = "SELECT /*+ NO_INDEX*/ v1 FROM " + fullTableName;
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("aaa", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("bbb", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("ddd", rs.getString(1));
            assertFalse(rs.next());
        }
    }
    
    private static boolean hasIndexDisableTimestamp(Connection conn, String indexName) throws SQLException {
        ResultSet rs = conn.createStatement().executeQuery("SELECT " + PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP +
                " FROM " + PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME + 
                " WHERE " + PhoenixDatabaseMetaData.COLUMN_NAME + " IS NULL" +
                " AND " + PhoenixDatabaseMetaData.TENANT_ID + " IS NULL" +
                " AND " + PhoenixDatabaseMetaData.TABLE_SCHEM + " IS NULL" +
                " AND " + PhoenixDatabaseMetaData.TABLE_NAME +  " = '" + indexName + "'");
        assertTrue(rs.next());
        long ts = rs.getLong(1);
        return (!rs.wasNull() && ts > 0);
    }

    public static class FailingRegionObserver extends SimpleRegionObserver {
        private Integer failCount = new Integer(0);
        
        @Override
        public void prePut(ObserverContext<RegionCoprocessorEnvironment> c, Put put, WALEdit edit,
                final Durability durability) throws HBaseIOException {
            if (shouldFailUpsert(c, put)) {
                synchronized (failCount) {
                    failCount++;
                    if (failCount.intValue() == 1) {
                        // throwing anything other than instances of IOException result
                        // in this coprocessor being unloaded
                        // DoNotRetryIOException tells HBase not to retry this mutation
                        // multiple times
                        throw new DoNotRetryIOException();
                    }
                }
            }
        }
        
        private boolean shouldFailUpsert(ObserverContext<RegionCoprocessorEnvironment> c, Put put) {
            return Bytes.contains(put.getRow(), Bytes.toBytes(FAIL_ON_FIRST_PUT));
        }
        
    }
}
