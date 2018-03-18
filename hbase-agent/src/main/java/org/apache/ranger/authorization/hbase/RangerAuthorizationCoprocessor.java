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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ranger.authorization.hbase;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.ProcedureInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.MasterCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionServerCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.ipc.RpcServer;
import org.apache.hadoop.hbase.master.procedure.MasterProcedureEnv;
import org.apache.hadoop.hbase.procedure2.ProcedureExecutor;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.protobuf.generated.AccessControlProtos;
import org.apache.hadoop.hbase.protobuf.generated.AccessControlProtos.AccessControlService;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.protobuf.generated.QuotaProtos.Quotas;
import org.apache.hadoop.hbase.protobuf.generated.SecureBulkLoadProtos.CleanupBulkLoadRequest;
import org.apache.hadoop.hbase.protobuf.generated.SecureBulkLoadProtos.PrepareBulkLoadRequest;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.security.AccessDeniedException;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.access.Permission;
import org.apache.hadoop.hbase.security.access.Permission.Action;
import org.apache.hadoop.hbase.security.access.RangerAccessControlLists;
import org.apache.hadoop.hbase.security.access.TablePermission;
import org.apache.hadoop.hbase.security.access.UserPermission;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.security.AccessControlException;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.authorization.hadoop.config.RangerConfiguration;
import org.apache.ranger.authorization.hadoop.constants.RangerHadoopConstants;
import org.apache.ranger.authorization.utils.StringUtil;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResultProcessor;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.GrantRevokeRequest;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;
import org.apache.ranger.plugin.util.RangerPerfTracer;

public class RangerAuthorizationCoprocessor extends RangerAuthorizationCoprocessorBase implements AccessControlService.Interface, CoprocessorService {
	private static final Log LOG = LogFactory.getLog(RangerAuthorizationCoprocessor.class.getName());
	private static final Log PERF_HBASEAUTH_REQUEST_LOG = RangerPerfTracer.getPerfLogger("hbaseauth.request");
	private static boolean UpdateRangerPoliciesOnGrantRevoke = RangerHadoopConstants.HBASE_UPDATE_RANGER_POLICIES_ON_GRANT_REVOKE_DEFAULT_VALUE;
	private static final String GROUP_PREFIX = "@";

    private RegionCoprocessorEnvironment regionEnv;
	private Map<InternalScanner, String> scannerOwners = new MapMaker().weakKeys().makeMap();
	
	/*
	 * These are package level only for testability and aren't meant to be exposed outside via getters/setters or made available to derived classes.
	 */
	final HbaseFactory _factory = HbaseFactory.getInstance();
	final HbaseUserUtils _userUtils = _factory.getUserUtils();
	final HbaseAuthUtils _authUtils = _factory.getAuthUtils();
	private static volatile RangerHBasePlugin hbasePlugin = null;
	
	// Utilities Methods
	protected byte[] getTableName(RegionCoprocessorEnvironment e) {
		Region region = e.getRegion();
		byte[] tableName = null;
		if (region != null) {
			HRegionInfo regionInfo = region.getRegionInfo();
			if (regionInfo != null) {
				tableName = regionInfo.getTable().getName();
			}
		}
		return tableName;
	}
	protected void requireSystemOrSuperUser(Configuration conf) throws IOException {
		User user = User.getCurrent();
		if (user == null) {
			throw new IOException("Unable to obtain the current user, authorization checks for internal operations will not work correctly!");
		}
		String systemUser = user.getShortName();
		User activeUser = getActiveUser();
		if (!Objects.equal(systemUser, activeUser.getShortName()) && !_userUtils.isSuperUser(activeUser)) {
			throw new AccessDeniedException("User '" + user.getShortName() + "is not system or super user.");
		}
	}
	protected boolean isSpecialTable(HRegionInfo regionInfo) {
		return isSpecialTable(regionInfo.getTable().getName());
	}
	protected boolean isSpecialTable(byte[] tableName) {
		return isSpecialTable(Bytes.toString(tableName));
	}
	protected boolean isSpecialTable(String input) {
		final String[] specialTables = new String[] { "hbase:meta", "-ROOT-", ".META.", "hbase:acl", "hbase:namespace"};
		for (String specialTable : specialTables ) {
			if (specialTable.equals(input)) {
				return true;
			}
		}
			
		return false;
	}
	protected boolean isAccessForMetaTables(RegionCoprocessorEnvironment env) {
		HRegionInfo hri = env.getRegion().getRegionInfo();
		
		if (hri.isMetaTable() || hri.isMetaRegion()) {
			return true;
		} else {
			return false;
		}
	}

	private User getActiveUser() {
		User user = RpcServer.getRequestUser();
		if (user == null) {
			// for non-rpc handling, fallback to system user
			try {
				user = User.getCurrent();
			} catch (IOException e) {
				LOG.error("Unable to find the current user");
				user = null;
			}
		}
		return user;
	}
	
	private String getRemoteAddress() {
		InetAddress remoteAddr = RpcServer.getRemoteAddress();

		if(remoteAddr == null) {
			remoteAddr = RpcServer.getRemoteIp();
		}

		String strAddr = remoteAddr != null ? remoteAddr.getHostAddress() : null;

		return strAddr;
	}

	// Methods that are used within the CoProcessor
	private void requireScannerOwner(InternalScanner s) throws AccessDeniedException {
     if (!RpcServer.isInRpcCallContext()) {
       return;
     }

     String requestUserName = RpcServer.getRequestUserName();
     String owner = scannerOwners.get(s);
     if (owner != null && !owner.equals(requestUserName)) {
       throw new AccessDeniedException("User '"+ requestUserName +"' is not the scanner owner!");
     }	
	}
	/**
	 * @param families
	 * @return empty map if families is null, would never have empty or null keys, would never have null values, values could be empty (non-null) set
	 */
	Map<String, Set<String>> getColumnFamilies(Map<byte[], ? extends Collection<?>> families) {
		if (families == null) {
			// null families map passed.  Ok, returning empty map.
			return Collections.<String, Set<String>>emptyMap();
		}
		Map<String, Set<String>> result = new HashMap<String, Set<String>>();
		for (Map.Entry<byte[], ? extends Collection<?>> anEntry : families.entrySet()) {
			byte[] familyBytes = anEntry.getKey();
			String family = Bytes.toString(familyBytes);
			if (family == null || family.isEmpty()) {
				LOG.error("Unexpected Input: got null or empty column family (key) in families map! Ignoring...");
			} else {
				Collection<?> columnCollection = anEntry.getValue();
				if (CollectionUtils.isEmpty(columnCollection)) {
					// family points to null map, OK.
					result.put(family, Collections.<String> emptySet());
				} else {
					Iterator<String> columnIterator = new ColumnIterator(columnCollection);
					Set<String> columns = new HashSet<String>();
					try {
						while (columnIterator.hasNext()) {
							String column = columnIterator.next();
							columns.add(column);
						}
					} catch (Throwable t) {
						LOG.error("Exception encountered when converting family-map to set of columns. Ignoring and returning empty set of columns for family[" + family + "]", t);
						LOG.error("Ignoring exception and returning empty set of columns for family[" + family +"]");
						columns.clear();
					}
					result.put(family, columns);
				}
			}
		}
		return result;
	}
	
	static class ColumnFamilyAccessResult {
		final boolean _everythingIsAccessible;
		final boolean _somethingIsAccessible;
		final List<AuthzAuditEvent> _accessAllowedEvents;
		final List<AuthzAuditEvent> _familyLevelAccessEvents;
		final AuthzAuditEvent _accessDeniedEvent;
		final String _denialReason;
		final RangerAuthorizationFilter _filter;
		final String _clusterName;

		ColumnFamilyAccessResult(boolean everythingIsAccessible, boolean somethingIsAccessible,
								 List<AuthzAuditEvent> accessAllowedEvents, List<AuthzAuditEvent> familyLevelAccessEvents, AuthzAuditEvent accessDeniedEvent, String denialReason,
								 RangerAuthorizationFilter filter, String clusterName) {
			_everythingIsAccessible = everythingIsAccessible;
			_somethingIsAccessible = somethingIsAccessible;
			// WARNING: we are just holding on to reference of the collection.  Potentially risky optimization
			_accessAllowedEvents = accessAllowedEvents;
			_familyLevelAccessEvents = familyLevelAccessEvents;
			_accessDeniedEvent = accessDeniedEvent;
			_denialReason = denialReason;
			// cached values of access results
			_filter = filter;
			_clusterName = clusterName;
		}
		
		@Override
		public String toString() {
			return Objects.toStringHelper(getClass())
					.add("everythingIsAccessible", _everythingIsAccessible)
					.add("somethingIsAccessible", _somethingIsAccessible)
					.add("accessAllowedEvents", _accessAllowedEvents)
					.add("familyLevelAccessEvents", _familyLevelAccessEvents)
					.add("accessDeniedEvent", _accessDeniedEvent)
					.add("denialReason", _denialReason)
					.add("filter", _filter)
					.add("clusterName", _clusterName)
					.toString();
			
		}
	}
	
	ColumnFamilyAccessResult evaluateAccess(String operation, Action action, final RegionCoprocessorEnvironment env,
											final Map<byte[], ? extends Collection<?>> familyMap) throws AccessDeniedException {

		String access = _authUtils.getAccess(action);
		User user = getActiveUser();
		String userName = _userUtils.getUserAsString(user);

		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("evaluateAccess: entered: user[%s], Operation[%s], access[%s], families[%s]",
					userName, operation, access, getColumnFamilies(familyMap).toString()));
		}

		byte[] tableBytes = getTableName(env);
		if (tableBytes == null || tableBytes.length == 0) {
			String message = "evaluateAccess: Unexpected: Couldn't get table from RegionCoprocessorEnvironment. Access denied, not audited";
			LOG.debug(message);
			throw new AccessDeniedException("Insufficient permissions for operation '" + operation + "',action: " + action);
		}
		String table = Bytes.toString(tableBytes);
		String clusterName = hbasePlugin.getClusterName();

		final String messageTemplate = "evaluateAccess: exiting: user[%s], Operation[%s], access[%s], families[%s], verdict[%s]";
		ColumnFamilyAccessResult result;
		if (canSkipAccessCheck(operation, access, table) || canSkipAccessCheck(operation, access, env)) {
			LOG.debug("evaluateAccess: exiting: isKnownAccessPattern returned true: access allowed, not audited");
			result = new ColumnFamilyAccessResult(true, true, null, null, null, null, null, null);
			if (LOG.isDebugEnabled()) {
				Map<String, Set<String>> families = getColumnFamilies(familyMap);
				String message = String.format(messageTemplate, userName, operation, access, families.toString(), result.toString());
				LOG.debug(message);
			}
			return result;
		}

		// let's create a session that would be reused.  Set things on it that won't change.
		HbaseAuditHandler auditHandler = _factory.getAuditHandler();
		AuthorizationSession session = new AuthorizationSession(hbasePlugin)
				.operation(operation)
				.remoteAddress(getRemoteAddress())
				.auditHandler(auditHandler)
				.user(user)
				.access(access)
				.table(table)
				.clusterName(clusterName);
		Map<String, Set<String>> families = getColumnFamilies(familyMap);
		if (LOG.isDebugEnabled()) {
			LOG.debug("evaluateAccess: families to process: " + families.toString());
		}
		if (families == null || families.isEmpty()) {
			LOG.debug("evaluateAccess: Null or empty families collection, ok.  Table level access is desired");
			session.buildRequest()
				.authorize();
			boolean authorized = session.isAuthorized();
			String reason = "";
			if (authorized) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("evaluateAccess: table level access granted [" + table + "]");
				}
			} else {
				reason = String.format("Insufficient permissions for user ‘%s',action: %s, tableName:%s, no column families found.", user.getName(), operation, table);
			}
			AuthzAuditEvent event = auditHandler.getAndDiscardMostRecentEvent(); // this could be null, of course, depending on audit settings of table.
			// if authorized then pass captured events as access allowed set else as access denied set.
			result = new ColumnFamilyAccessResult(authorized, authorized,
						authorized ? Collections.singletonList(event) : null,
						null, authorized ? null : event, reason, null, clusterName);
			if (LOG.isDebugEnabled()) {
				String message = String.format(messageTemplate, userName, operation, access, families.toString(), result.toString());
				LOG.debug(message);
			}
			return result;
		} else {
			LOG.debug("evaluateAccess: Families collection not null.  Skipping table-level check, will do finer level check");
		}
		
		boolean everythingIsAccessible = true;
		boolean somethingIsAccessible = false;
		/*
		 * we would have to accumulate audits of all successful accesses and any one denial (which in our case ends up being the last denial)
		 * We need to keep audit events for family level access check seperate because we don't want them logged in some cases.
		 */
		List<AuthzAuditEvent> authorizedEvents = new ArrayList<AuthzAuditEvent>();
		List<AuthzAuditEvent> familyLevelAccessEvents = new ArrayList<AuthzAuditEvent>();
		AuthzAuditEvent deniedEvent = null;
		String denialReason = null;
		// we need to cache the auths results so that we can create a filter, if needed
		Map<String, Set<String>> columnsAccessAllowed = new HashMap<String, Set<String>>();
		Set<String> familesAccessAllowed = new HashSet<String>();
		Set<String> familesAccessDenied = new HashSet<String>();
		Set<String> familesAccessIndeterminate = new HashSet<String>();

		for (Map.Entry<String, Set<String>> anEntry : families.entrySet()) {
			String family = anEntry.getKey();
			session.columnFamily(family);
			if (LOG.isDebugEnabled()) {
				LOG.debug("evaluateAccess: Processing family: " + family);
			}
			Set<String> columns = anEntry.getValue();
			if (columns == null || columns.isEmpty()) {
				LOG.debug("evaluateAccess: columns collection null or empty, ok.  Family level access is desired.");
				session.column(null) // zap stale column from prior iteration of this loop, if any
						.buildRequest()
						.authorize();
				AuthzAuditEvent auditEvent = auditHandler.getAndDiscardMostRecentEvent(); // capture it only for success
				if (session.isAuthorized()) {
					somethingIsAccessible = true;
					if (LOG.isDebugEnabled()) {
						LOG.debug("evaluateAccess: has family level access [" + family + "]. Checking if [" + family + "] descendants have access.");
					}
					session.resourceMatchingScope(RangerAccessRequest.ResourceMatchingScope.SELF_OR_DESCENDANTS)
							.buildRequest()
							.authorize();
					auditEvent = auditHandler.getAndDiscardMostRecentEvent(); // capture it only for failure
					if (session.isAuthorized()) {
						if (LOG.isDebugEnabled()) {
							LOG.debug("evaluateAccess: [" + family + "] descendants have access");
						}
						familesAccessAllowed.add(family);
						if (auditEvent != null) {
							LOG.debug("evaluateAccess: adding to family-level-access-granted-event-set");
							familyLevelAccessEvents.add(auditEvent);
						}
					} else {
						if (LOG.isDebugEnabled()) {
							LOG.debug("evaluateAccess: has partial access (of some type) in family [" + family + "]");
						}
						everythingIsAccessible = false;
						familesAccessIndeterminate.add(family);
						if (auditEvent != null && deniedEvent == null) { // we need to capture just one denial event
							LOG.debug("evaluateAccess: Setting denied access audit event with last auth failure audit event.");
							deniedEvent = auditEvent;
						}
					}
					// Restore the headMatch setting
					session.resourceMatchingScope(RangerAccessRequest.ResourceMatchingScope.SELF);
				} else {
					if (LOG.isDebugEnabled()) {
						LOG.debug("evaluateAccess: has no access of [" + access + "] type in family [" + family + "]");
					}
					everythingIsAccessible = false;
					familesAccessDenied.add(family);
					denialReason = String.format("Insufficient permissions for user ‘%s',action: %s, tableName:%s, family:%s.", user.getName(), operation, table, family);
					if (auditEvent != null && deniedEvent == null) { // we need to capture just one denial event
						LOG.debug("evaluateAccess: Setting denied access audit event with last auth failure audit event.");
						deniedEvent = auditEvent;
					}
				}
			} else {
				LOG.debug("evaluateAccess: columns collection not empty.  Skipping Family level check, will do finer level access check.");
				Set<String> accessibleColumns = new HashSet<String>(); // will be used in to populate our results cache for the filter
 				for (String column : columns) {
 					if (LOG.isDebugEnabled()) {
 						LOG.debug("evaluateAccess: Processing column: " + column);
 					}
 					session.column(column)
 						.buildRequest()
 						.authorize();
					AuthzAuditEvent auditEvent = auditHandler.getAndDiscardMostRecentEvent();
 					if (session.isAuthorized()) {
						if (LOG.isDebugEnabled()) {
							LOG.debug("evaluateAccess: has column level access [" + family + ", " + column + "]");
						}
 						// we need to do 3 things: housekeeping, capturing audit events, building the results cache for filter
 						somethingIsAccessible = true;
 						accessibleColumns.add(column);
						if (auditEvent != null) {
							LOG.debug("evaluateAccess: adding to access-granted-audit-event-set");
							authorizedEvents.add(auditEvent);
						}
 					} else {
						if (LOG.isDebugEnabled()) {
							LOG.debug("evaluateAccess: no column level access [" + family + ", " + column + "]");
						}
 						everythingIsAccessible = false;
 						denialReason = String.format("Insufficient permissions for user ‘%s',action: %s, tableName:%s, family:%s, column: %s", user.getName(), operation, table, family, column);
						if (auditEvent != null && deniedEvent == null) { // we need to capture just one denial event
							LOG.debug("evaluateAccess: Setting denied access audit event with last auth failure audit event.");
							deniedEvent = auditEvent;
						}
 					}
					if (!accessibleColumns.isEmpty()) {
						columnsAccessAllowed.put(family, accessibleColumns);
					}
				}
			}
		}
		// Cache of auth results are encapsulated the in the filter. Not every caller of the function uses it - only preGet and preOpt will.
		RangerAuthorizationFilter filter = new RangerAuthorizationFilter(session, familesAccessAllowed, familesAccessDenied, familesAccessIndeterminate, columnsAccessAllowed);
		result = new ColumnFamilyAccessResult(everythingIsAccessible, somethingIsAccessible, authorizedEvents, familyLevelAccessEvents, deniedEvent, denialReason, filter, clusterName);
		if (LOG.isDebugEnabled()) {
			String message = String.format(messageTemplate, userName, operation, access, families.toString(), result.toString());
			LOG.debug(message);
		}
		return result;
	}

	Filter authorizeAccess(String operation, Action action, final RegionCoprocessorEnvironment env, final Map<byte[], NavigableSet<byte[]>> familyMap) throws AccessDeniedException {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> authorizeAccess");
		}
		RangerPerfTracer perf = null;

		try {
			perf = RangerPerfTracer.getPerfTracer(PERF_HBASEAUTH_REQUEST_LOG, "RangerAuthorizationCoprocessor.authorizeAccess(request=Operation[" + operation + "]");

			ColumnFamilyAccessResult accessResult = evaluateAccess(operation, action, env, familyMap);
			RangerDefaultAuditHandler auditHandler = new RangerDefaultAuditHandler();
			if (accessResult._everythingIsAccessible) {
				auditHandler.logAuthzAudits(accessResult._accessAllowedEvents);
				auditHandler.logAuthzAudits(accessResult._familyLevelAccessEvents);
				LOG.debug("authorizeAccess: exiting: No filter returned since all access was allowed");
				return null; // no filter needed since we are good to go.
			} else if (accessResult._somethingIsAccessible) {
				// NOTE: audit logging is split beween logging here (in scope of preOp/preGet) and logging in the filter component for those that couldn't be determined
				auditHandler.logAuthzAudits(accessResult._accessAllowedEvents);
				LOG.debug("authorizeAccess: exiting: Filter returned since some access was allowed");
				return accessResult._filter;
			} else {
				// If we are here then it means nothing was accessible!  So let's log one denial (in our case, the last denial) and throw an exception
				auditHandler.logAuthzAudit(accessResult._accessDeniedEvent);
				LOG.debug("authorizeAccess: exiting: Throwing exception since nothing was accessible");
				throw new AccessDeniedException(accessResult._denialReason);
			}
		} finally {
			RangerPerfTracer.log(perf);
			if (LOG.isDebugEnabled()) {
				LOG.debug("<== authorizeAccess");
			}
		}
	}
	
	Filter combineFilters(Filter filter, Filter existingFilter) {
		Filter combinedFilter = filter;
		if (existingFilter != null) {
			combinedFilter = new FilterList(FilterList.Operator.MUST_PASS_ALL, Lists.newArrayList(filter, existingFilter));
		}
		return combinedFilter;
	}

	void requirePermission(final String operation, final Action action, final RegionCoprocessorEnvironment regionServerEnv, final Map<byte[], ? extends Collection<?>> familyMap)
			throws AccessDeniedException {

		RangerPerfTracer perf = null;

		try {
			if (RangerPerfTracer.isPerfTraceEnabled(PERF_HBASEAUTH_REQUEST_LOG)) {
				perf = RangerPerfTracer.getPerfTracer(PERF_HBASEAUTH_REQUEST_LOG, "RangerAuthorizationCoprocessor.requirePermission(request=Operation[" + operation + "]");
			}
			ColumnFamilyAccessResult accessResult = evaluateAccess(operation, action, regionServerEnv, familyMap);
			RangerDefaultAuditHandler auditHandler = new RangerDefaultAuditHandler();
			if (accessResult._everythingIsAccessible) {
				auditHandler.logAuthzAudits(accessResult._accessAllowedEvents);
				auditHandler.logAuthzAudits(accessResult._familyLevelAccessEvents);
				LOG.debug("requirePermission: exiting: all access was allowed");
				return;
			} else {
				auditHandler.logAuthzAudit(accessResult._accessDeniedEvent);
				LOG.debug("requirePermission: exiting: throwing exception as everything wasn't accessible");
				throw new AccessDeniedException(accessResult._denialReason);
			}
		} finally {
			RangerPerfTracer.log(perf);
		}
	}
	
	/**
	 * This could run s
	 * @param operation
	 * @param otherInformation
	 * @param table
	 * @param columnFamily
	 * @param column
	 * @return
	 * @throws AccessDeniedException
	 */
	void authorizeAccess(String operation, String otherInformation, Action action, String table, String columnFamily, String column) throws AccessDeniedException {
		
		String access = _authUtils.getAccess(action);
		if (LOG.isDebugEnabled()) {
			final String format = "authorizeAccess: %s: Operation[%s], Info[%s], access[%s], table[%s], columnFamily[%s], column[%s]";
			String message = String.format(format, "Entering", operation, otherInformation, access, table, columnFamily, column);
			LOG.debug(message);
		}
		
		final String format =  "authorizeAccess: %s: Operation[%s], Info[%s], access[%s], table[%s], columnFamily[%s], column[%s], allowed[%s], reason[%s]";
		if (canSkipAccessCheck(operation, access, table)) {
			if (LOG.isDebugEnabled()) {
				String message = String.format(format, "Exiting", operation, otherInformation, access, table, columnFamily, column, true, "can skip auth check");
				LOG.debug(message);
			}
			return;
		}
		User user = getActiveUser();
		String clusterName = hbasePlugin.getClusterName();
		
		HbaseAuditHandler auditHandler = _factory.getAuditHandler();
		AuthorizationSession session = new AuthorizationSession(hbasePlugin)
			.operation(operation)
			.otherInformation(otherInformation)
			.remoteAddress(getRemoteAddress())
			.auditHandler(auditHandler)
			.user(user)
			.access(access)
			.table(table)
			.columnFamily(columnFamily)
			.column(column)
			.clusterName(clusterName)
			.buildRequest()
			.authorize();
		
		if (LOG.isDebugEnabled()) {
			boolean allowed = session.isAuthorized();
			String reason = session.getDenialReason();
			String message = String.format(format, "Exiting", operation, otherInformation, access, table, columnFamily, column, allowed, reason);
			LOG.debug(message);
		}
		
		session.publishResults();
	}
	
	boolean canSkipAccessCheck(final String operation, String access, final String table)
			throws AccessDeniedException {
		
		User user = getActiveUser();
		boolean result = false;
		if (user == null) {
			String message = "Unexpeceted: User is null: access denied, not audited!";
			LOG.warn("canSkipAccessCheck: exiting" + message);
			throw new AccessDeniedException("No user associated with request (" + operation + ") for action: " + access + "on table:" + table);
		} else if (isAccessForMetadataRead(access, table)) {
			LOG.debug("canSkipAccessCheck: true: metadata read access always allowed, not audited");
			result = true;
		} else {
			LOG.debug("Can't skip access checks");
		}
		
		return result;
	}
	
	boolean canSkipAccessCheck(final String operation, String access, final RegionCoprocessorEnvironment regionServerEnv) throws AccessDeniedException {

		String clusterName = hbasePlugin.getClusterName();
		User user = getActiveUser();
		// read access to metadata tables is always allowed and isn't audited.
		if (isAccessForMetaTables(regionServerEnv) && _authUtils.isReadAccess(access)) {
			LOG.debug("isKnownAccessPattern: exiting: Read access for metadata tables allowed, not audited!");
			return true;
		}
		// if write access is desired to metatables then global create access is sufficient
		if (_authUtils.isWriteAccess(access) && isAccessForMetaTables(regionServerEnv)) {
			String createAccess = _authUtils.getAccess(Action.CREATE);
			AuthorizationSession session = new AuthorizationSession(hbasePlugin)
				.operation(operation)
				.remoteAddress(getRemoteAddress())
				.user(user)
				.access(createAccess)
				.clusterName(clusterName)
				.buildRequest()
				.authorize();
			if (session.isAuthorized()) {
				// NOTE: this access isn't logged
				LOG.debug("isKnownAccessPattern: exiting: User has global create access, allowed!");
				return true;
			}
		}
		return false;
	}
	
	boolean isAccessForMetadataRead(String access, String table) {
		if (_authUtils.isReadAccess(access) && isSpecialTable(table)) {
			LOG.debug("isAccessForMetadataRead: Metadata tables read: access allowed!");
			return true;
		}
		return false;
	}

	// Check if the user has global permission ...
	protected void requireGlobalPermission(String request, String objName, Permission.Action action) throws AccessDeniedException {
		authorizeAccess(request, objName, action, null, null, null);
	}

	protected void requirePermission(String request, Permission.Action action) throws AccessDeniedException {
		requirePermission(request, null, action);
	}

	protected void requirePermission(String request, byte[] tableName, Permission.Action action) throws AccessDeniedException {
		String table = Bytes.toString(tableName);

		authorizeAccess(request, null, action, table, null, null);
	}
	
	protected void requirePermission(String request, byte[] aTableName, byte[] aColumnFamily, byte[] aQualifier, Permission.Action action) throws AccessDeniedException {

		String table = Bytes.toString(aTableName);
		String columnFamily = Bytes.toString(aColumnFamily);
		String column = Bytes.toString(aQualifier);

		authorizeAccess(request, null, action, table, columnFamily, column);
	}
	
	protected void requirePermission(String request, Permission.Action perm, RegionCoprocessorEnvironment env, Collection<byte[]> families) throws IOException {
		HashMap<byte[], Set<byte[]>> familyMap = new HashMap<byte[], Set<byte[]>>();

		if(families != null) {
			for (byte[] family : families) {
				familyMap.put(family, null);
			}
		}
		requirePermission(request, perm, env, familyMap);
	}
	
	@Override
	public void postScannerClose(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s) throws IOException {
		scannerOwners.remove(s);
	}
	@Override
	public RegionScanner postScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Scan scan, RegionScanner s) throws IOException {
		User user = getActiveUser();
		if (user != null && user.getShortName() != null) {
			scannerOwners.put(s, user.getShortName());
		}
		return s;
	}
	@Override
	public void postStartMaster(ObserverContext<MasterCoprocessorEnvironment> ctx) throws IOException {
		if(UpdateRangerPoliciesOnGrantRevoke) {
			RangerAccessControlLists.init(ctx.getEnvironment().getMasterServices());
		}
	}
	@Override
	public void preAddColumn(ObserverContext<MasterCoprocessorEnvironment> c, TableName tableName, HColumnDescriptor column) throws IOException {
		requirePermission("addColumn", tableName.getName(), null, null, Action.CREATE);
	}
	@Override
	public Result preAppend(ObserverContext<RegionCoprocessorEnvironment> c, Append append) throws IOException {
		requirePermission("append", TablePermission.Action.WRITE, c.getEnvironment(), append.getFamilyCellMap());
		return null;
	}
	@Override
	public void preAssign(ObserverContext<MasterCoprocessorEnvironment> c, HRegionInfo regionInfo) throws IOException {
		requirePermission("assign", regionInfo.getTable().getName(), null, null, Action.ADMIN);
	}
	@Override
	public void preBalance(ObserverContext<MasterCoprocessorEnvironment> c) throws IOException {
		requirePermission("balance", Permission.Action.ADMIN);
	}
	@Override
	public boolean preBalanceSwitch(ObserverContext<MasterCoprocessorEnvironment> c, boolean newValue) throws IOException {
		requirePermission("balanceSwitch", Permission.Action.ADMIN);
		return newValue;
	}
	@Override
	public void preBulkLoadHFile(ObserverContext<RegionCoprocessorEnvironment> ctx, List<Pair<byte[], String>> familyPaths) throws IOException {
		List<byte[]> cfs = new LinkedList<byte[]>();
		for (Pair<byte[], String> el : familyPaths) {
			cfs.add(el.getFirst());
		}
		requirePermission("bulkLoadHFile", Permission.Action.WRITE, ctx.getEnvironment(), cfs);
	}
	@Override
	public boolean preCheckAndDelete(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareOp compareOp, ByteArrayComparable comparator, Delete delete, boolean result) throws IOException {
		Collection<byte[]> familyMap = Arrays.asList(new byte[][] { family });
		requirePermission("checkAndDelete", TablePermission.Action.READ, c.getEnvironment(), familyMap);
		requirePermission("checkAndDelete", TablePermission.Action.WRITE, c.getEnvironment(), familyMap);
		return result;
	}
	@Override
	public boolean preCheckAndPut(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareOp compareOp, ByteArrayComparable comparator, Put put, boolean result) throws IOException {
		Collection<byte[]> familyMap = Arrays.asList(new byte[][] { family });
		requirePermission("checkAndPut", TablePermission.Action.READ, c.getEnvironment(), familyMap);
		requirePermission("checkAndPut", TablePermission.Action.WRITE, c.getEnvironment(), familyMap);
		return result;
	}
	@Override
	public void preCloneSnapshot(ObserverContext<MasterCoprocessorEnvironment> ctx, SnapshotDescription snapshot, HTableDescriptor hTableDescriptor) throws IOException {
		requirePermission("cloneSnapshot", hTableDescriptor.getTableName().getName(), Permission.Action.ADMIN);
	}
	@Override
	public void preClose(ObserverContext<RegionCoprocessorEnvironment> e, boolean abortRequested) throws IOException {
		requirePermission("close", getTableName(e.getEnvironment()), Permission.Action.ADMIN);
	}
	@Override
	public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> e, Store store, InternalScanner scanner,ScanType scanType) throws IOException {
		requirePermission("compact", getTableName(e.getEnvironment()), null, null, Action.CREATE);
		return scanner;
	}
	@Override
	public void preCompactSelection(ObserverContext<RegionCoprocessorEnvironment> e, Store store, List<StoreFile> candidates) throws IOException {
		requirePermission("compactSelection", getTableName(e.getEnvironment()), null, null, Action.CREATE);
	}

	@Override
	public void preCreateTable(ObserverContext<MasterCoprocessorEnvironment> c, HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
		requirePermission("createTable", desc.getTableName().getName(), Permission.Action.CREATE);
	}
	@Override
	public void preDelete(ObserverContext<RegionCoprocessorEnvironment> c, Delete delete, WALEdit edit, Durability durability) throws IOException {
		requirePermission("delete", TablePermission.Action.WRITE, c.getEnvironment(), delete.getFamilyCellMap());
	}
	@Override
	public void preDeleteColumn(ObserverContext<MasterCoprocessorEnvironment> c, TableName tableName, byte[] col) throws IOException {
		requirePermission("deleteColumn", tableName.getName(), null, null, Action.CREATE);
	}
	@Override
	public void preDeleteSnapshot(ObserverContext<MasterCoprocessorEnvironment> ctx, SnapshotDescription snapshot) throws IOException {
		requirePermission("deleteSnapshot", snapshot.getTableBytes().toByteArray(), Permission.Action.ADMIN);
	}
	@Override
	public void preDeleteTable(ObserverContext<MasterCoprocessorEnvironment> c, TableName tableName) throws IOException {
		requirePermission("deleteTable", tableName.getName(), null, null, Action.CREATE);
	}
	@Override
	public void preDisableTable(ObserverContext<MasterCoprocessorEnvironment> c, TableName tableName) throws IOException {
		requirePermission("disableTable", tableName.getName(), null, null, Action.CREATE);
	}
	@Override
	public void preEnableTable(ObserverContext<MasterCoprocessorEnvironment> c, TableName tableName) throws IOException {
		requirePermission("enableTable", tableName.getName(), null, null, Action.CREATE);
	}
	@Override
	public boolean preExists(ObserverContext<RegionCoprocessorEnvironment> c, Get get, boolean exists) throws IOException {
		requirePermission("exists", TablePermission.Action.READ, c.getEnvironment(), get.familySet());
		return exists;
	}
	@Override
	public void preFlush(ObserverContext<RegionCoprocessorEnvironment> e) throws IOException {
		requirePermission("flush", getTableName(e.getEnvironment()), null, null, Action.CREATE);
	}
	@Override
	public void preGetClosestRowBefore(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, Result result) throws IOException {
		requirePermission("getClosestRowBefore", TablePermission.Action.READ, c.getEnvironment(), (family != null ? Lists.newArrayList(family) : null));
	}
	@Override
	public Result preIncrement(ObserverContext<RegionCoprocessorEnvironment> c, Increment increment) throws IOException {
		requirePermission("increment", TablePermission.Action.WRITE, c.getEnvironment(), increment.getFamilyCellMap().keySet());
		
		return null;
	}
	@Override
	public long preIncrementColumnValue(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, long amount, boolean writeToWAL) throws IOException {
		requirePermission("incrementColumnValue", TablePermission.Action.READ, c.getEnvironment(), Arrays.asList(new byte[][] { family }));
		requirePermission("incrementColumnValue", TablePermission.Action.WRITE, c.getEnvironment(), Arrays.asList(new byte[][] { family }));
		return -1;
	}
	@Override
	public void preModifyColumn(ObserverContext<MasterCoprocessorEnvironment> c, TableName tableName, HColumnDescriptor descriptor) throws IOException {
		requirePermission("modifyColumn", tableName.getName(), null, null, Action.CREATE);
	}
	@Override
	public void preModifyTable(ObserverContext<MasterCoprocessorEnvironment> c, TableName tableName, HTableDescriptor htd) throws IOException {
		requirePermission("modifyTable", tableName.getName(), null, null, Action.CREATE);
	}
	@Override
	public void preMove(ObserverContext<MasterCoprocessorEnvironment> c, HRegionInfo region, ServerName srcServer, ServerName destServer) throws IOException {
		requirePermission("move", region.getTable().getName() , null, null, Action.ADMIN);
	}

	@Override
	public void preAbortProcedure(ObserverContext<MasterCoprocessorEnvironment> observerContext, ProcedureExecutor<MasterProcedureEnv> procEnv, long procId) throws IOException {
		if(!procEnv.isProcedureOwner(procId, this.getActiveUser())) {
			requirePermission("abortProcedure", Action.ADMIN);
		}
	}

	@Override
	public void postListProcedures(ObserverContext<MasterCoprocessorEnvironment> observerContext, List<ProcedureInfo> procInfoList) throws IOException {
		if(!procInfoList.isEmpty()) {
			Iterator<ProcedureInfo> itr = procInfoList.iterator();
			User user = this.getActiveUser();

			while(itr.hasNext()) {
				ProcedureInfo procInfo = itr.next();

				try {
					if(!ProcedureInfo.isProcedureOwner(procInfo, user)) {
						requirePermission("listProcedures", Action.ADMIN);
					}
				} catch (AccessDeniedException var7) {
					itr.remove();
				}
			}

		}
	}

	@Override
	public void preOpen(ObserverContext<RegionCoprocessorEnvironment> e) throws IOException {
		RegionCoprocessorEnvironment env = e.getEnvironment();
		final Region region = env.getRegion();
		if (region == null) {
			LOG.error("NULL region from RegionCoprocessorEnvironment in preOpen()");
		} else {
			HRegionInfo regionInfo = region.getRegionInfo();
			if (isSpecialTable(regionInfo)) {
				requireSystemOrSuperUser(regionEnv.getConfiguration());
			} else {
				requirePermission("open", getTableName(e.getEnvironment()), Action.ADMIN);
			}
		}
	}
	@Override
	public void preRestoreSnapshot(ObserverContext<MasterCoprocessorEnvironment> ctx, SnapshotDescription snapshot, HTableDescriptor hTableDescriptor) throws IOException {
		requirePermission("restoreSnapshot", hTableDescriptor.getTableName().getName(), Permission.Action.ADMIN);
	}

	@Override
	public void preScannerClose(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s) throws IOException {
		requireScannerOwner(s);
	}
	@Override
	public boolean preScannerNext(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s, List<Result> result, int limit, boolean hasNext) throws IOException {
		requireScannerOwner(s);
		return hasNext;
	}
	@Override
	public RegionScanner preScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Scan scan, RegionScanner s) throws IOException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> preScannerOpen");
		}

		try {
			RegionCoprocessorEnvironment e = c.getEnvironment();

			Map<byte[], NavigableSet<byte[]>> familyMap = scan.getFamilyMap();
			String operation = "scannerOpen";
			Filter filter = authorizeAccess(operation, Action.READ, e, familyMap);
			if (filter == null) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("preScannerOpen: Access allowed for all families/column.  No filter added");
				}
			} else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("preScannerOpen: Access allowed for some of the families/column. New filter added.");
				}
				Filter existingFilter = scan.getFilter();
				Filter combinedFilter = combineFilters(filter, existingFilter);
				scan.setFilter(combinedFilter);
			}
			return s;
		} finally {
			if (LOG.isDebugEnabled()) {
				LOG.debug("<== preScannerOpen");
			}
		}
	}
	@Override
	public void preShutdown(ObserverContext<MasterCoprocessorEnvironment> c) throws IOException {
		requirePermission("shutdown", Permission.Action.ADMIN);
	}
	@Override
	public void preSnapshot(ObserverContext<MasterCoprocessorEnvironment> ctx, SnapshotDescription snapshot, HTableDescriptor hTableDescriptor) throws IOException {
		requirePermission("snapshot", hTableDescriptor.getTableName().getName(), Permission.Action.ADMIN);
	}
	@Override
	public void preSplit(ObserverContext<RegionCoprocessorEnvironment> e) throws IOException {
		requirePermission("split", getTableName(e.getEnvironment()), null, null, Action.ADMIN);
	}
	@Override
	public void preStopMaster(ObserverContext<MasterCoprocessorEnvironment> c) throws IOException {
		requirePermission("stopMaster", Permission.Action.ADMIN);
	}
	@Override
	public void preStopRegionServer(ObserverContext<RegionServerCoprocessorEnvironment> env) throws IOException {
		requirePermission("stop", Permission.Action.ADMIN);
	}
	@Override
	public void preUnassign(ObserverContext<MasterCoprocessorEnvironment> c, HRegionInfo regionInfo, boolean force) throws IOException {
		requirePermission("unassign", regionInfo.getTable().getName(), null, null, Action.ADMIN);
	}

  @Override
  public void preSetUserQuota(final ObserverContext<MasterCoprocessorEnvironment> ctx,
      final String userName, final Quotas quotas) throws IOException {
    requireGlobalPermission("setUserQuota", null, Action.ADMIN);
  }

  @Override
  public void preSetUserQuota(final ObserverContext<MasterCoprocessorEnvironment> ctx,
      final String userName, final TableName tableName, final Quotas quotas) throws IOException {
    requirePermission("setUserTableQuota", tableName.getName(), null, null, Action.ADMIN);
  }

  @Override
  public void preSetUserQuota(final ObserverContext<MasterCoprocessorEnvironment> ctx,
      final String userName, final String namespace, final Quotas quotas) throws IOException {
    requireGlobalPermission("setUserNamespaceQuota", namespace, Action.ADMIN);
  }

  @Override
  public void preSetTableQuota(final ObserverContext<MasterCoprocessorEnvironment> ctx,
      final TableName tableName, final Quotas quotas) throws IOException {
    requirePermission("setTableQuota", tableName.getName(), null, null, Action.ADMIN);
  }

  @Override
  public void preSetNamespaceQuota(final ObserverContext<MasterCoprocessorEnvironment> ctx,
      final String namespace, final Quotas quotas) throws IOException {
    requireGlobalPermission("setNamespaceQuota", namespace, Action.ADMIN);
  }

	private String coprocessorType = "unknown";
	private static final String MASTER_COPROCESSOR_TYPE = "master";
	private static final String REGIONAL_COPROCESSOR_TYPE = "regional";
	private static final String REGIONAL_SERVER_COPROCESSOR_TYPE = "regionalServer";

	@Override
	public void start(CoprocessorEnvironment env) throws IOException {
		String appType = "unknown";

		if (env instanceof MasterCoprocessorEnvironment) {
			coprocessorType = MASTER_COPROCESSOR_TYPE;
			appType = "hbaseMaster";
		} else if (env instanceof RegionServerCoprocessorEnvironment) {
			coprocessorType = REGIONAL_SERVER_COPROCESSOR_TYPE;
			appType = "hbaseRegional";
		} else if (env instanceof RegionCoprocessorEnvironment) {
			regionEnv = (RegionCoprocessorEnvironment) env;
			coprocessorType = REGIONAL_COPROCESSOR_TYPE;
			appType = "hbaseRegional";
		}
		
		Configuration conf = env.getConfiguration();
		HbaseFactory.initialize(conf);

		// create and initialize the plugin class
		RangerHBasePlugin plugin = hbasePlugin;

		if(plugin == null) {
			synchronized(RangerAuthorizationCoprocessor.class) {
				plugin = hbasePlugin;
				
				if(plugin == null) {
					plugin = new RangerHBasePlugin(appType);

					plugin.init();

					UpdateRangerPoliciesOnGrantRevoke = RangerConfiguration.getInstance().getBoolean(RangerHadoopConstants.HBASE_UPDATE_RANGER_POLICIES_ON_GRANT_REVOKE_PROP, RangerHadoopConstants.HBASE_UPDATE_RANGER_POLICIES_ON_GRANT_REVOKE_DEFAULT_VALUE);

					hbasePlugin = plugin;
				}
			}
		}
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("Start of Coprocessor: [" + coprocessorType + "]");
		}
	}
	@Override
	public void prePut(ObserverContext<RegionCoprocessorEnvironment> c, Put put, WALEdit edit, Durability durability) throws IOException {
		requirePermission("put", TablePermission.Action.WRITE, c.getEnvironment(), put.getFamilyCellMap());
	}
	
	@Override
	public void preGetOp(final ObserverContext<RegionCoprocessorEnvironment> rEnv, final Get get, final List<Cell> result) throws IOException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> preGetOp");
		}
		try {
			RegionCoprocessorEnvironment e = rEnv.getEnvironment();
			Map<byte[], NavigableSet<byte[]>> familyMap = get.getFamilyMap();

			String operation = "get";
			Filter filter = authorizeAccess(operation, Action.READ, e, familyMap);
			if (filter == null) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("preGetOp: all access allowed, no filter returned");
				}
			} else {
				Filter existingFilter = get.getFilter();
				Filter combinedFilter = combineFilters(filter, existingFilter);
				get.setFilter(combinedFilter);
				if (LOG.isDebugEnabled()) {
					LOG.debug("preGetOp: partial access, new filter added");
				}
			}
		} finally {
			if (LOG.isDebugEnabled()) {
				LOG.debug("<== preGetOp");
			}
		}
	}
	@Override
	public void preRegionOffline(ObserverContext<MasterCoprocessorEnvironment> c, HRegionInfo regionInfo) throws IOException {
	    requirePermission("regionOffline", regionInfo.getTable().getName(), null, null, Action.ADMIN);
	}
	@Override
	public void preCreateNamespace(ObserverContext<MasterCoprocessorEnvironment> ctx, NamespaceDescriptor ns) throws IOException {
		requireGlobalPermission("createNamespace", ns.getName(), Action.ADMIN);
	}
	@Override
	public void preDeleteNamespace(ObserverContext<MasterCoprocessorEnvironment> ctx, String namespace) throws IOException {
		requireGlobalPermission("deleteNamespace", namespace, Action.ADMIN);
	}
	@Override
	public void preModifyNamespace(ObserverContext<MasterCoprocessorEnvironment> ctx, NamespaceDescriptor ns) throws IOException {
		requireGlobalPermission("modifyNamespace", ns.getName(), Action.ADMIN);
	}

	@Override
	public void postGetTableDescriptors(ObserverContext<MasterCoprocessorEnvironment> ctx, List<TableName> tableNamesList, List<HTableDescriptor> descriptors, String regex) throws IOException {
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("==> postGetTableDescriptors(count(tableNamesList)=%s, count(descriptors)=%s, regex=%s)", tableNamesList == null ? 0 : tableNamesList.size(),
					descriptors == null ? 0 : descriptors.size(), regex));
		}
		String clusterName = hbasePlugin.getClusterName();

		if (CollectionUtils.isNotEmpty(descriptors)) {
			// Retains only those which passes authorization checks
			User user = getActiveUser();
			String access = _authUtils.getAccess(Action.CREATE);
			HbaseAuditHandler auditHandler = _factory.getAuditHandler();  // this will accumulate audits for all tables that succeed.
			AuthorizationSession session = new AuthorizationSession(hbasePlugin)
				.operation("getTableDescriptors")
				.otherInformation("regex=" + regex)
				.remoteAddress(getRemoteAddress())
				.auditHandler(auditHandler)
				.user(user)
				.access(access)
				.clusterName(clusterName);
	
			Iterator<HTableDescriptor> itr = descriptors.iterator();
			while (itr.hasNext()) {
				HTableDescriptor htd = itr.next();
				String tableName = htd.getTableName().getNameAsString();
				session.table(tableName).buildRequest().authorize();
				if (!session.isAuthorized()) {
					List<AuthzAuditEvent> events = null;
					itr.remove();
					AuthzAuditEvent event = auditHandler.getAndDiscardMostRecentEvent();
					if (event != null) {
						events = Lists.newArrayList(event);
					}
					auditHandler.logAuthzAudits(events);
				}
			}
			if (descriptors.size() > 0) {
				session.logCapturedEvents();
			}
		}
		
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("<== postGetTableDescriptors(count(tableNamesList)=%s, count(descriptors)=%s, regex=%s)", tableNamesList == null ? 0 : tableNamesList.size(),
					descriptors == null ? 0 : descriptors.size(), regex));
		}
	}

    @Override
	public void preMerge(ObserverContext<RegionServerCoprocessorEnvironment> ctx, Region regionA, Region regionB) throws IOException {
		requirePermission("mergeRegions", regionA.getTableDesc().getTableName().getName(), null, null, Action.ADMIN);
	}

	public void prePrepareBulkLoad(ObserverContext<RegionCoprocessorEnvironment> ctx, PrepareBulkLoadRequest request) throws IOException {
		List<byte[]> cfs = null;

		requirePermission("prePrepareBulkLoad", Permission.Action.WRITE, ctx.getEnvironment(), cfs);
	}

	public void preCleanupBulkLoad(ObserverContext<RegionCoprocessorEnvironment> ctx, CleanupBulkLoadRequest request) throws IOException {
		List<byte[]> cfs = null;

		requirePermission("preCleanupBulkLoad", Permission.Action.WRITE, ctx.getEnvironment(), cfs);
	}
	
	@Override
	public void grant(RpcController controller, AccessControlProtos.GrantRequest request, RpcCallback<AccessControlProtos.GrantResponse> done) {
		boolean isSuccess = false;

		if(UpdateRangerPoliciesOnGrantRevoke) {
			GrantRevokeRequest grData = null;
	
			try {
				grData = createGrantData(request);

				RangerHBasePlugin plugin = hbasePlugin;

				if(plugin != null) {

					String clusterName = plugin.getClusterName();
					grData.setClusterName(clusterName);
					
					RangerAccessResultProcessor auditHandler = new RangerDefaultAuditHandler();

					plugin.grantAccess(grData, auditHandler);

					isSuccess = true;
				}
			} catch(AccessControlException excp) {
				LOG.warn("grant() failed", excp);

				ResponseConverter.setControllerException(controller, new AccessDeniedException(excp));
			} catch(IOException excp) {
				LOG.warn("grant() failed", excp);

				ResponseConverter.setControllerException(controller, excp);
			} catch (Exception excp) {
				LOG.warn("grant() failed", excp);

				ResponseConverter.setControllerException(controller, new CoprocessorException(excp.getMessage()));
			}
		}

		AccessControlProtos.GrantResponse response = isSuccess ? AccessControlProtos.GrantResponse.getDefaultInstance() : null;

		done.run(response);
	}

	@Override
	public void revoke(RpcController controller, AccessControlProtos.RevokeRequest request, RpcCallback<AccessControlProtos.RevokeResponse> done) {
		boolean isSuccess = false;

		if(UpdateRangerPoliciesOnGrantRevoke) {
			GrantRevokeRequest grData = null;

			try {
				grData = createRevokeData(request);

				RangerHBasePlugin plugin = hbasePlugin;

				if(plugin != null) {
					String clusterName = plugin.getClusterName();
					grData.setClusterName(clusterName);
					
					RangerAccessResultProcessor auditHandler = new RangerDefaultAuditHandler();

					plugin.revokeAccess(grData, auditHandler);

					isSuccess = true;
				}
			} catch(AccessControlException excp) {
				LOG.warn("revoke() failed", excp);

				ResponseConverter.setControllerException(controller, new AccessDeniedException(excp));
			} catch(IOException excp) {
				LOG.warn("revoke() failed", excp);

				ResponseConverter.setControllerException(controller, excp);
			} catch (Exception excp) {
				LOG.warn("revoke() failed", excp);

				ResponseConverter.setControllerException(controller, new CoprocessorException(excp.getMessage()));
			}
		}

		AccessControlProtos.RevokeResponse response = isSuccess ? AccessControlProtos.RevokeResponse.getDefaultInstance() : null;

		done.run(response);
	}

	@Override
	public void checkPermissions(RpcController controller, AccessControlProtos.CheckPermissionsRequest request, RpcCallback<AccessControlProtos.CheckPermissionsResponse> done) {
		LOG.debug("checkPermissions(): ");
	}

	@Override
	public void getUserPermissions(RpcController controller, AccessControlProtos.GetUserPermissionsRequest request, RpcCallback<AccessControlProtos.GetUserPermissionsResponse> done) {
		LOG.debug("getUserPermissions(): ");
	}

	@Override
	public Service getService() {
	    return AccessControlProtos.AccessControlService.newReflectiveService(this);
	}

	private GrantRevokeRequest createGrantData(AccessControlProtos.GrantRequest request) throws Exception {
		AccessControlProtos.UserPermission up   = request.getUserPermission();
		AccessControlProtos.Permission     perm = up == null ? null : up.getPermission();

		UserPermission      userPerm  = up == null ? null : ProtobufUtil.toUserPermission(up);
		Permission.Action[] actions   = userPerm == null ? null : userPerm.getActions();
		String              userName  = userPerm == null ? null : Bytes.toString(userPerm.getUser());
		String              nameSpace = null;
		String              tableName = null;
		String              colFamily = null;
		String              qualifier = null;

		if(perm == null) {
			throw new Exception("grant(): invalid data - permission is null");
		}

		if(StringUtil.isEmpty(userName)) {
			throw new Exception("grant(): invalid data - username empty");
		}

		if ((actions == null) || (actions.length == 0)) {
			throw new Exception("grant(): invalid data - no action specified");
		}

		switch(perm.getType()) {
			case Global:
				tableName = colFamily = qualifier = RangerHBaseResource.WILDCARD;
			break;

			case Table:
				tableName = Bytes.toString(userPerm.getTableName().getName());
				colFamily = Bytes.toString(userPerm.getFamily());
				qualifier = Bytes.toString(userPerm.getQualifier());
			break;

			case Namespace:
				nameSpace = userPerm.getNamespace();
			break;
		}
		
		if(StringUtil.isEmpty(nameSpace) && StringUtil.isEmpty(tableName) && StringUtil.isEmpty(colFamily) && StringUtil.isEmpty(qualifier)) {
			throw new Exception("grant(): namespace/table/columnFamily/columnQualifier not specified");
		}

		tableName = StringUtil.isEmpty(tableName) ? RangerHBaseResource.WILDCARD : tableName;
		colFamily = StringUtil.isEmpty(colFamily) ? RangerHBaseResource.WILDCARD : colFamily;
		qualifier = StringUtil.isEmpty(qualifier) ? RangerHBaseResource.WILDCARD : qualifier;

		if(! StringUtil.isEmpty(nameSpace)) {
			tableName = nameSpace + RangerHBaseResource.NAMESPACE_SEPARATOR + tableName;
		}

		User   activeUser = getActiveUser();
		String grantor    = activeUser != null ? activeUser.getShortName() : null;
		String[] groups   = activeUser != null ? activeUser.getGroupNames() : null;

		Set<String> grantorGroups = null;

		if (groups != null && groups.length > 0) {
			grantorGroups = new HashSet<>(Arrays.asList(groups));
		}

		Map<String, String> mapResource = new HashMap<String, String>();
		mapResource.put(RangerHBaseResource.KEY_TABLE, tableName);
		mapResource.put(RangerHBaseResource.KEY_COLUMN_FAMILY, colFamily);
		mapResource.put(RangerHBaseResource.KEY_COLUMN, qualifier);

		GrantRevokeRequest ret = new GrantRevokeRequest();

		ret.setGrantor(grantor);
		ret.setGrantorGroups(grantorGroups);
		ret.setDelegateAdmin(Boolean.FALSE);
		ret.setEnableAudit(Boolean.TRUE);
		ret.setReplaceExistingPermissions(Boolean.TRUE);
		ret.setResource(mapResource);
		ret.setClientIPAddress(getRemoteAddress());

		if(userName.startsWith(GROUP_PREFIX)) {
			ret.getGroups().add(userName.substring(GROUP_PREFIX.length()));
		} else {
			ret.getUsers().add(userName);
		}

		for (Permission.Action action : actions) {
			switch(action.code()) {
				case 'R':
					ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_READ);
				break;

				case 'W':
					ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_WRITE);
				break;

				case 'C':
					ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_CREATE);
				break;

				case 'A':
					ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_ADMIN);
					ret.setDelegateAdmin(Boolean.TRUE);
				break;

				default:
					LOG.warn("grant(): ignoring action '" + action.name() + "' for user '" + userName + "'");
			}
		}

		return ret;
	}

	private GrantRevokeRequest createRevokeData(AccessControlProtos.RevokeRequest request) throws Exception {
		AccessControlProtos.UserPermission up   = request.getUserPermission();
		AccessControlProtos.Permission     perm = up == null ? null : up.getPermission();

		UserPermission      userPerm  = up == null ? null : ProtobufUtil.toUserPermission(up);
		String              userName  = userPerm == null ? null : Bytes.toString(userPerm.getUser());
		String              nameSpace = null;
		String              tableName = null;
		String              colFamily = null;
		String              qualifier = null;

		if(perm == null) {
			throw new Exception("revoke(): invalid data - permission is null");
		}

		if(StringUtil.isEmpty(userName)) {
			throw new Exception("revoke(): invalid data - username empty");
		}

		switch(perm.getType()) {
			case Global :
				tableName = colFamily = qualifier = RangerHBaseResource.WILDCARD;
			break;

			case Table :
				tableName = Bytes.toString(userPerm.getTableName().getName());
				colFamily = Bytes.toString(userPerm.getFamily());
				qualifier = Bytes.toString(userPerm.getQualifier());
			break;

			case Namespace:
				nameSpace = userPerm.getNamespace();
			break;
		}

		if(StringUtil.isEmpty(nameSpace) && StringUtil.isEmpty(tableName) && StringUtil.isEmpty(colFamily) && StringUtil.isEmpty(qualifier)) {
			throw new Exception("revoke(): table/columnFamily/columnQualifier not specified");
		}

		tableName = StringUtil.isEmpty(tableName) ? RangerHBaseResource.WILDCARD : tableName;
		colFamily = StringUtil.isEmpty(colFamily) ? RangerHBaseResource.WILDCARD : colFamily;
		qualifier = StringUtil.isEmpty(qualifier) ? RangerHBaseResource.WILDCARD : qualifier;

		if(! StringUtil.isEmpty(nameSpace)) {
			tableName = nameSpace + RangerHBaseResource.NAMESPACE_SEPARATOR + tableName;
		}

		User   activeUser = getActiveUser();
		String grantor    = activeUser != null ? activeUser.getShortName() : null;
		String[] groups   = activeUser != null ? activeUser.getGroupNames() : null;

		Set<String> grantorGroups = null;

		if (groups != null && groups.length > 0) {
			grantorGroups = new HashSet<>(Arrays.asList(groups));
		}

		Map<String, String> mapResource = new HashMap<String, String>();
		mapResource.put(RangerHBaseResource.KEY_TABLE, tableName);
		mapResource.put(RangerHBaseResource.KEY_COLUMN_FAMILY, colFamily);
		mapResource.put(RangerHBaseResource.KEY_COLUMN, qualifier);

		GrantRevokeRequest ret = new GrantRevokeRequest();

		ret.setGrantor(grantor);
		ret.setGrantorGroups(grantorGroups);
		ret.setDelegateAdmin(Boolean.TRUE); // remove delegateAdmin privilege as well
		ret.setEnableAudit(Boolean.TRUE);
		ret.setReplaceExistingPermissions(Boolean.TRUE);
		ret.setResource(mapResource);
		ret.setClientIPAddress(getRemoteAddress());

		if(userName.startsWith(GROUP_PREFIX)) {
			ret.getGroups().add(userName.substring(GROUP_PREFIX.length()));
		} else {
			ret.getUsers().add(userName);
		}

		// revoke removes all permissions
		ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_READ);
		ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_WRITE);
		ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_CREATE);
		ret.getAccessTypes().add(HbaseAuthUtils.ACCESS_TYPE_ADMIN);

		return ret;
	}
}


class RangerHBasePlugin extends RangerBasePlugin {
	public RangerHBasePlugin(String appType) {
		super("hbase", appType);
	}

}


