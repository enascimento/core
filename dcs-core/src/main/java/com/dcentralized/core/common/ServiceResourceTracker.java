/*
 * Copyright (c) 2014-2016 dCentralizedSystems, LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.dcentralized.core.common;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import com.dcentralized.core.common.Service.ServiceOption;
import com.dcentralized.core.common.ServiceClient.ConnectionPoolMetrics;
import com.dcentralized.core.common.ServiceHost.AttachedServiceInfo;
import com.dcentralized.core.common.ServiceHost.ServiceHostState;
import com.dcentralized.core.common.ServiceStats.ServiceStat;
import com.dcentralized.core.common.ServiceStats.TimeSeriesStats.AggregationType;
import com.dcentralized.core.services.common.ServiceHostManagementService;
import com.dcentralized.core.services.common.ServiceUriPaths;

/**
 * Monitors service resources, and takes action, during periodic maintenance
 */
public class ServiceResourceTracker {
    /**
     * For performance reasons, this map is owned and directly operated by the host
     */
    private final ConcurrentMap<String, AttachedServiceInfo> attachedServices;

    private final ServiceHost host;

    private boolean isServiceStateCaching = true;

    private long startTimeMicros;

    private ThreadMXBean threadBean;

    private Service mgmtService;

    public static ServiceResourceTracker create(ServiceHost host,
            ConcurrentMap<String, AttachedServiceInfo> services) {
        ServiceResourceTracker srt = new ServiceResourceTracker(host, services);
        return srt;
    }

    public ServiceResourceTracker(ServiceHost host,
            ConcurrentMap<String, AttachedServiceInfo> services) {
        this.attachedServices = services;
        this.host = host;
    }

    private void checkAndInitializeStats() {
        if (this.startTimeMicros > 0) {
            return;
        }
        this.startTimeMicros = Utils.getNowMicrosUtc();
        if (this.host.getManagementService() == null) {
            this.host.log(Level.WARNING,
                    "Management service not found, stats will not be available");
            return;
        }

        long inUseMem = this.host.getState().systemInfo.totalMemoryByteCount
                - this.host.getState().systemInfo.freeMemoryByteCount;
        long freeMem = this.host.getState().systemInfo.maxMemoryByteCount - inUseMem;
        long freeDisk = this.host.getState().systemInfo.freeDiskByteCount;

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_MEMORY_BYTES_PREFIX,
                freeMem);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_DISK_BYTES_PREFIX,
                freeDisk);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_CPU_USAGE_PCT_PREFIX,
                0);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_HTTP11_CONNECTION_COUNT_PREFIX,
                0);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_HTTP2_CONNECTION_COUNT_PREFIX,
                0);

        getManagementService().setStat(ServiceHostManagementService.STAT_NAME_THREAD_COUNT,
                Utils.DEFAULT_THREAD_COUNT);

    }

    void createTimeSeriesStat(String name, double v) {
        Service service = getManagementService();
        EnumSet<AggregationType> types = EnumSet.of(AggregationType.AVG);
        ServiceStat dayStat = ServiceStatUtils.getOrCreateDailyTimeSeriesStat(service, name, types);
        ServiceStat hourStat = ServiceStatUtils.getOrCreateHourlyTimeSeriesHistogramStat(service,
                name, types);
        service.setStat(dayStat, v);
        service.setStat(hourStat, v);
    }

    private void updateStats(long now) {
        this.host.updateMemoryAndDiskInfo();
        ServiceHostState hostState = this.host.getStateNoCloning();
        SystemHostInfo shi = hostState.systemInfo;

        Service mgmtService = getManagementService();
        if (mgmtService == null) {
            return;
        }

        checkAndInitializeStats();
        mgmtService.setStat(ServiceHostManagementService.STAT_NAME_SERVICE_COUNT,
                hostState.serviceCount);

        // The JVM reports free memory in a indirect way, relative to the current "total". But the
        // true free memory is the estimated used memory subtracted from the JVM heap max limit
        long freeMemory = shi.maxMemoryByteCount
                - (shi.totalMemoryByteCount - shi.freeMemoryByteCount);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_MEMORY_BYTES_PER_HOUR,
                freeMemory);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_MEMORY_BYTES_PER_DAY,
                freeMemory);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_DISK_BYTES_PER_HOUR,
                shi.freeDiskByteCount);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_DISK_BYTES_PER_DAY,
                shi.freeDiskByteCount);

        if (this.threadBean == null) {
            this.threadBean = ManagementFactory.getThreadMXBean();
        }
        if (!this.threadBean.isCurrentThreadCpuTimeSupported()) {
            return;
        }

        long totalTime = 0;
        // we assume a low number of threads since the runtime uses just a thread per core, plus
        // a small multiple of that dedicated to I/O threads. So the thread CPU usage calculation
        // should have a small overhead
        long[] threadIds = this.threadBean.getAllThreadIds();
        for (long threadId : threadIds) {
            totalTime += this.threadBean.getThreadCpuTime(threadId);
        }

        double runningTime = now - this.startTimeMicros;
        if (runningTime <= 0) {
            return;
        }

        createTimeSeriesStat(ServiceHostManagementService.STAT_NAME_JVM_THREAD_COUNT_PREFIX,
                threadIds.length);

        totalTime = TimeUnit.NANOSECONDS.toMicros(totalTime);
        double pctUse = totalTime / runningTime;
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_CPU_USAGE_PCT_PER_HOUR,
                pctUse);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_CPU_USAGE_PCT_PER_DAY,
                pctUse);

        ConnectionPoolMetrics http11TagInfo = this.host.getClient()
                .getConnectionPoolMetrics(false);
        if (http11TagInfo != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP11_PENDING_OP_COUNT_PREFIX,
                    http11TagInfo.pendingRequestCount);
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP11_CONNECTION_COUNT_PREFIX,
                    http11TagInfo.inUseConnectionCount);
        }

        ConnectionPoolMetrics http2TagInfo = this.host.getClient()
                .getConnectionPoolMetrics(true);
        if (http2TagInfo != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP2_PENDING_OP_COUNT_PREFIX,
                    http2TagInfo.pendingRequestCount);
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP2_CONNECTION_COUNT_PREFIX,
                    http2TagInfo.inUseConnectionCount);
        }

        ForkJoinPool executor = (ForkJoinPool) this.host.getExecutor();
        if (executor != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_EXECUTOR_QUEUE_DEPTH,
                    executor.getQueuedSubmissionCount());
        }

        ScheduledThreadPoolExecutor scheduledExecutor = (ScheduledThreadPoolExecutor) this.host
                .getScheduledExecutor();
        if (scheduledExecutor != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_SCHEDULED_EXECUTOR_QUEUE_DEPTH,
                    scheduledExecutor.getQueue().size());
        }
    }

    private Service getManagementService() {
        if (this.mgmtService == null) {
            this.mgmtService = this.host.getManagementService();
        }
        return this.mgmtService;
    }

    public void setServiceStateCaching(boolean enable) {
        this.isServiceStateCaching = enable;
    }

    public void resetCachedServiceState(Service s, ServiceDocument st, Operation op) {
        updateCachedServiceState(s, st, op, false);
    }

    public void updateCachedServiceState(Service s, ServiceDocument st, Operation op) {
        updateCachedServiceState(s, st, op, true);
    }

    private void updateCachedServiceState(Service s, ServiceDocument st, Operation op,
            boolean checkVersion) {
        AttachedServiceInfo serviceInfo = this.attachedServices.get(s.getSelfLink());
        if (serviceInfo == null) {
            // the service has just been detached (unlikely, but possible) - we'll forego caching
            return;
        }

        // we cache the state only if:
        // (1) the service is non-persistent, or:
        // (2) the service is persistent, the operation is not replicated and state caching is enabled
        // (3) the service has better state from peer after synchronization and local node is owner
        boolean cacheState = !ServiceHost.isServiceIndexed(s);
        cacheState |= (!op.isFromReplication() || op.isSynchronizeOwner())
                && this.isServiceStateCaching;

        if (!cacheState) {
            if (ServiceHost.isServiceIndexed(s)) {
                synchronized (serviceInfo) {
                    serviceInfo.lastAccessTime = Utils.getNowMicrosUtc();
                }
            }
            return;
        }

        synchronized (serviceInfo) {
            ServiceDocument cachedState = serviceInfo.cachedState;
            if (checkVersion && cachedState != null
                    && cachedState.documentVersion > st.documentVersion) {
                // discarding update, if the existing version is higher
                return;
            }
            serviceInfo.cachedState = st;
            if (ServiceHost.isServiceIndexed(s)) {
                serviceInfo.lastAccessTime = Utils.getNowMicrosUtc();
            }
        }
    }

    /**
     * called only for stateful services
     */
    public ServiceDocument getCachedServiceState(Service s, Operation op) {
        String servicePath = s.getSelfLink();
        AttachedServiceInfo serviceInfo = null;
        ServiceDocument state = null;
        serviceInfo = this.attachedServices.get(servicePath);
        if (serviceInfo != null) {
            synchronized (serviceInfo) {
                state = serviceInfo.cachedState;
            }
        }

        if (state == null) {
            updateCacheMissStats();
            return null;
        }

        if (state.documentExpirationTimeMicros > 0 &&
                state.documentExpirationTimeMicros < Utils.getNowMicrosUtc()) {
            // Service has expired - stop it and clear from cache.
            // An expired persistent service is also detected and deleted by the
            // index service, but concurrent stop delete is safe and in any
            // case expired documents cannot be returned from the cache.
            stopServiceAndClearFromCache(s, state);
            return null;
        }

        if (ServiceHost.isServiceIndexed(s)) {
            synchronized (serviceInfo) {
                serviceInfo.lastAccessTime = Utils.getNowMicrosUtc();
            }
        }

        updateCacheHitStats();
        return state;
    }

    private void stopServiceAndClearFromCache(Service s, ServiceDocument state) {
        // Issue DELETE to stop the service and clear it from cache
        Operation deleteExp = Operation.createDelete(this.host, s.getSelfLink())
                .setBodyNoCloning(state)
                .disableFailureLogging(true)
                .toggleOption(Operation.OperationOption.INDEXING_DISABLED, true)
                .toggleOption(Operation.OperationOption.FORWARDING_DISABLED, true)
                .setReferer(this.host.getUri());

        this.host.sendRequest(deleteExp);
    }

    public void clearCachedServiceState(String servicePath, Operation op) {
        AttachedServiceInfo serviceInfo = this.attachedServices.get(servicePath);
        if (serviceInfo != null) {
            synchronized (serviceInfo) {
                serviceInfo.cachedState = null;
                serviceInfo.lastAccessTime = 0;
            }
        }

        updateCacheClearStats();
        return;
    }

    public void updateCacheMissStats() {
        this.host.getManagementService().adjustStat(
                ServiceHostManagementService.STAT_NAME_SERVICE_CACHE_MISS_COUNT, 1);
    }

    private void updateCacheClearStats() {
        this.host.getManagementService().adjustStat(
                ServiceHostManagementService.STAT_NAME_SERVICE_CACHE_CLEAR_COUNT, 1);
    }

    private void updateCacheHitStats() {
        this.host.getManagementService().adjustStat(
                ServiceHostManagementService.STAT_NAME_SERVICE_CACHE_HIT_COUNT, 1);
    }

    /**
     * Estimates how much memory is used by host caches, queues and based on the memory limits
     * takes appropriate action: clears cached service state, temporarily stops services
     */
    public void performMaintenance(long now, long deadlineMicros) {
        updateStats(now);
        ServiceHostState hostState = this.host.getStateNoCloning();
        int stopServiceCount = 0;

        for (AttachedServiceInfo serviceInfo : this.attachedServices.values()) {
            Service service = serviceInfo.service;
            ServiceDocument state;
            synchronized (serviceInfo) {
                state = serviceInfo.cachedState;
            }

            if (!ServiceHost.isServiceIndexed(service)) {
                // service is not persistent - just check if it's expired, and
                // if so - stop and clear from cache
                if (state != null &&
                        state.documentExpirationTimeMicros > 0 &&
                        state.documentExpirationTimeMicros < now) {
                    stopServiceAndClearFromCache(service, state);
                }
                continue;
            }

            // service is persistent - check whether it's exempt from cache
            // eviction (e.g. it has soft state)
            if (serviceExemptFromCacheEviction(service)) {
                continue;
            }

            if (serviceActive(serviceInfo.lastAccessTime, service, now)) {
                // Skip stop for services that have been recently active
                continue;
            }

            // stop service and update count
            stopServiceAndClearFromCache(service, state);
            stopServiceCount++;

            if (deadlineMicros < Utils.getSystemNowMicrosUtc()) {
                break;
            }
        }

        if (hostState.serviceCount < 0) {
            // Make sure our service count matches the list contents, they could drift. Using size()
            // on a concurrent data structure is costly so we do this only when pausing services or
            // the count is negative
            synchronized (hostState) {
                hostState.serviceCount = this.attachedServices.size();
            }
        }

        if (stopServiceCount == 0) {
            return;
        }

        this.host.log(Level.FINE,
                "Attempt stop on %d services, attached: %d",
                stopServiceCount, hostState.serviceCount);
    }

    private boolean serviceExemptFromCacheEviction(Service service) {
        if (service.hasOption(ServiceOption.FACTORY)) {
            // skip factory services, they do not have state
            return true;
        }

        if (service.hasOption(ServiceOption.PERIODIC_MAINTENANCE)) {
            // Services with periodic maintenance stay resident, for now. We might stop them in the future
            // if they have long periods
            return true;
        }

        if (this.host.isServiceStarting(service, service.getSelfLink())) {
            // service is just starting - doesn't make sense to evict it from cache
            return true;
        }

        if (this.host.hasPendingServiceAvailableCompletions(service.getSelfLink())) {
            // service has pending available completions - keep in memory
            return true;
        }

        if (service.getSelfLink().startsWith(ServiceUriPaths.CORE_AUTHZ)) {
            // we keep authz resources (users, groups, roles, etc.) resident in memory
            // for perf reasons
            return true;
        }

        if (hasServiceSoftState(service)) {
            // service has soft state like subscriptions or stats - keep in memory
            return true;
        }

        // service is not exempt from cache eviction
        return false;
    }

    private boolean serviceActive(long lastAccessTime, Service s, long now) {
        long cacheClearDelayMicros = s.getCacheClearDelayMicros();
        if (ServiceHost.isServiceImmutable(s)) {
            cacheClearDelayMicros = 0;
        }

        return (cacheClearDelayMicros + lastAccessTime) > now;
    }

    void retryOnDemandLoadConflict(Operation op, Service s) {
        if (!ServiceHost.isServiceIndexed(s)) {
            // service is stopped but it's not persistent, so it doesn't start/stop on-demand.
            // no point in retrying the operation.
            op.fail(new CancellationException("Service has stopped"));
            return;
        }

        op.removePragmaDirective(Operation.PRAGMA_DIRECTIVE_INDEX_CHECK);

        this.host.log(Level.WARNING,
                "ODL conflict: retrying %s (%d %s) on %s",
                op.getAction(), op.getId(), op.getContextId(),
                op.getUri().getPath());

        long interval = Math.max(TimeUnit.SECONDS.toMicros(1),
                this.host.getMaintenanceIntervalMicros());
        this.host.scheduleCore(() -> {
            this.host.handleRequest(null, op);
        }, interval, TimeUnit.MICROSECONDS);
    }

    public void close() {
        for (AttachedServiceInfo e : this.attachedServices.values()) {
            e.cachedState = null;
            e.lastAccessTime = 0;
        }
    }

    private boolean hasServiceSoftState(Service service) {
        UtilityService subUtilityService = (UtilityService) service
                .getUtilityService(ServiceHost.SERVICE_URI_SUFFIX_SUBSCRIPTIONS, false);
        UtilityService statsUtilityService = (UtilityService) service
                .getUtilityService(ServiceHost.SERVICE_URI_SUFFIX_STATS, false);
        boolean hasSoftState = false;
        if (subUtilityService != null && subUtilityService.hasSubscribers()) {
            hasSoftState = true;
        }
        if (statsUtilityService != null && statsUtilityService.hasStats()) {
            hasSoftState = true;
        }
        return hasSoftState;
    }
}
