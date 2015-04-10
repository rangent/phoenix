/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.apache.phoenix.transactions;

import static org.apache.phoenix.util.TestUtil.INDEX_DATA_SCHEMA;
import static org.apache.phoenix.util.TestUtil.TRANSACTIONAL_DATA_TABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.apache.phoenix.end2end.BaseHBaseManagedTimeIT;
import org.apache.phoenix.end2end.Shadower;
import org.apache.phoenix.end2end.index.BaseMutableIndexIT;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.TestUtil;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Maps;

public class TransactionIT extends BaseHBaseManagedTimeIT {
	
	private static final String FULL_TABLE_NAME = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + TRANSACTIONAL_DATA_TABLE;
	
	@Before
    public void setUp() throws SQLException {
		ensureTableCreated(getUrl(), TRANSACTIONAL_DATA_TABLE);
    }
	
	@BeforeClass
    @Shadower(classBeingShadowed = BaseHBaseManagedTimeIT.class)
    public static void doSetup() throws Exception {
        Map<String,String> props = Maps.newHashMapWithExpectedSize(3);
        props.put(QueryServices.DROP_METADATA_ATTRIB, Boolean.toString(true));
        setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
    }
	
	private void setRowKeyColumns(PreparedStatement stmt, int i) throws SQLException {
        // insert row
        stmt.setString(1, "varchar" + String.valueOf(i));
        stmt.setString(2, "char" + String.valueOf(i));
        stmt.setInt(3, i);
        stmt.setLong(4, i);
        stmt.setBigDecimal(5, new BigDecimal(i*0.5d));
        Date date = new Date(DateUtil.parseDate("2015-01-01 00:00:00").getTime() + (i - 1) * TestUtil.NUM_MILLIS_IN_DAY);
        stmt.setDate(6, date);
    }
	
//	private void validateRowKeyColumns(ResultSet rs, int i) throws SQLException {
//		assertTrue(rs.next());
//		assertEquals(rs.getString(1), "varchar" + String.valueOf(i));
//		assertEquals(rs.getString(2), "char" + String.valueOf(i));
//		assertEquals(rs.getInt(3), i);
//		assertEquals(rs.getInt(4), i);
//		assertEquals(rs.getBigDecimal(5), new BigDecimal(i*0.5d));
//		Date date = new Date(DateUtil.parseDate("2015-01-01 00:00:00").getTime() + (i - 1) * TestUtil.NUM_MILLIS_IN_DAY);
//		assertEquals(rs.getDate(6), date);
//	}
//	
//	@Test
//	public void testUpsert() throws Exception {
//		Connection conn = DriverManager.getConnection(getUrl());
//		String selectSql = "SELECT * FROM "+FULL_TABLE_NAME;
//		try {
//			conn.setAutoCommit(false);
//			ResultSet rs = conn.createStatement().executeQuery(selectSql);
//	     	assertFalse(rs.next());
//	     	
//	        String upsert = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk) VALUES(?, ?, ?, ?, ?, ?)";
//	        PreparedStatement stmt = conn.prepareStatement(upsert);
//			// upsert two rows
//			setRowKeyColumns(stmt, 1);
//			stmt.execute();
//			setRowKeyColumns(stmt, 2);
//			stmt.execute();
//	        
//	        // verify no rows returned 
//			rs = conn.createStatement().executeQuery(selectSql);
//	     	assertFalse(rs.next());
//	     	
//	        conn.commit();
//	        // verify row exists
//	        rs = conn.createStatement().executeQuery(selectSql);
//	        validateRowKeyColumns(rs, 1);
//	        validateRowKeyColumns(rs, 2);
//	        assertFalse(rs.next());
//		}
//        finally {
//        	conn.close();
//        }
//	}
	
	@Test
	public void testUpsert2() throws Exception {
		Connection conn = DriverManager.getConnection(getUrl());
		String ddl = "CREATE TABLE t (k1 INTEGER PRIMARY KEY, k2 INTEGER) transactional=true";
		try {
			conn.setAutoCommit(false);
			conn.createStatement().execute(ddl);
			// upsert one row
			PreparedStatement stmt = conn
					.prepareStatement("UPSERT INTO t VALUES(?,?)");
			stmt.setInt(1, 1);
			stmt.setInt(2, 1);
			stmt.execute();
			stmt.setInt(1, 2);
			stmt.setInt(2, 2);
			stmt.execute();
			conn.commit();
			// verify row exists
			ResultSet rs = conn.createStatement().executeQuery(
					"SELECT * FROM t");
			assertTrue(rs.next());
			assertEquals(1, rs.getInt(1));
			assertTrue(rs.next());
			assertEquals(2, rs.getInt(1));
			assertFalse(rs.next());
		} finally {
			conn.close();
		}
	}
	
	@Test
	public void testAutocommitQueryEmptyTable() throws Exception {
		Connection conn = DriverManager.getConnection(getUrl());
		try {
			conn.setAutoCommit(true);
			// verify no rows returned
			ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM "+FULL_TABLE_NAME);
			assertFalse(rs.next());
		} finally {
			conn.close();
		}
	}
	
	@Test
	public void testColConflicts() throws Exception {
		Connection conn1 = DriverManager.getConnection(getUrl());
		Connection conn2 = DriverManager.getConnection(getUrl());
		try {
			conn1.setAutoCommit(false);
			conn2.setAutoCommit(false);
			String selectSql = "SELECT * FROM "+FULL_TABLE_NAME;
			conn1.setAutoCommit(false);
			ResultSet rs = conn1.createStatement().executeQuery(selectSql);
	     	assertFalse(rs.next());
			// upsert row using conn1
			String upsertSql = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk, a.int_col1) VALUES(?, ?, ?, ?, ?, ?, ?)";
			PreparedStatement stmt = conn1.prepareStatement(upsertSql);
			setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 10);
	        stmt.execute();
	        // upsert row using conn2
 			stmt = conn2.prepareStatement(upsertSql);
 			setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 11);
	        stmt.execute();
 	        
 	        conn1.commit();
	        //second commit should fail
 	        try {
 	 	        conn2.commit();
 	 	        fail();
 	        }	
 	        catch (SQLException e) {
 	        	assertEquals(e.getErrorCode(), SQLExceptionCode.TRANSACTION_CONFLICT_EXCEPTION.getErrorCode());
 	        }
		}
        finally {
        	conn1.close();
        }
	}
	
	@Test
	public void testRowConflicts() throws Exception {
		Connection conn1 = DriverManager.getConnection(getUrl());
		Connection conn2 = DriverManager.getConnection(getUrl());
		try {
			conn1.setAutoCommit(false);
			conn2.setAutoCommit(false);
			String selectSql = "SELECT * FROM "+FULL_TABLE_NAME;
			conn1.setAutoCommit(false);
			ResultSet rs = conn1.createStatement().executeQuery(selectSql);
	     	assertFalse(rs.next());
			// upsert row using conn1
			String upsertSql = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk, a.int_col1) VALUES(?, ?, ?, ?, ?, ?, ?)";
			PreparedStatement stmt = conn1.prepareStatement(upsertSql);
			setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 10);
	        stmt.execute();
	        // upsert row using conn2
	        upsertSql = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk, b.int_col2) VALUES(?, ?, ?, ?, ?, ?, ?)";
 			stmt = conn2.prepareStatement(upsertSql);
 			setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 11);
 	        stmt.execute();
 	        
 	        conn1.commit();
	        //second commit should fail
 	        try {
 	 	        conn2.commit();
 	 	        fail();
 	        }	
 	        catch (SQLException e) {
 	        	assertEquals(e.getErrorCode(), SQLExceptionCode.TRANSACTION_CONFLICT_EXCEPTION.getErrorCode());
 	        }
		}
        finally {
        	conn1.close();
        }
	}



}