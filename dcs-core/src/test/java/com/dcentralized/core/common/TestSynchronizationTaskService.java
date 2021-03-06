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

import static java.util.stream.Collectors.toList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.dcentralized.core.common.test.ExampleService;
import com.dcentralized.core.common.test.ExampleService.ExampleODLService;
import com.dcentralized.core.common.test.ExampleService.ExampleServiceState;
import com.dcentralized.core.common.test.TestContext;
import com.dcentralized.core.common.test.TestRequestSender;
import com.dcentralized.core.common.test.VerificationHost;
import com.dcentralized.core.services.common.InMemoryLuceneDocumentIndexService;
import com.dcentralized.core.services.common.NodeGroupService;
import com.dcentralized.core.services.common.QueryTask;
import com.dcentralized.core.services.common.ServiceUriPaths;
import com.dcentralized.core.services.common.TestLuceneDocumentIndexService.InMemoryExampleService;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


public class TestSynchronizationTaskService extends BasicTestCase {
    public static class SynchRetryExampleService extends StatefulService {
        public static final String FACTORY_LINK = ServiceUriPaths.CORE + "/test-retry-examples";

        public static FactoryService createFactory() {
            return FactoryService.create(SynchRetryExampleService.class);
        }

        public SynchRetryExampleService() {
            super(ServiceDocument.class);
            toggleOption(ServiceOption.PERSISTENCE, true);
            toggleOption(ServiceOption.OWNER_SELECTION, true);
            toggleOption(ServiceOption.REPLICATION, true);
        }

        @Override
        public boolean queueRequest(Operation op) {
            return false;
        }

        @Override
        public void handleRequest(Operation op) {
            if (getSelfLink().endsWith("fail")) {
                if (op.hasPragmaDirective(Operation.PRAGMA_DIRECTIVE_SYNCH_OWNER)) {
                    op.fail(500);
                    return;
                }
            }

            super.handleRequest(op);
        }
    }

    public static final String STAT_NAME_PATCH_REQUEST_COUNT = "PATCHrequestCount";

    public int updateCount = 10;
    public int serviceCount = 10;
    public int nodeCount = 3;

    private BiPredicate<ExampleServiceState, ExampleServiceState> exampleStateConvergenceChecker = (
            initial, current) -> {
        if (current.name == null) {
            return false;
        }

        return current.name.equals(initial.name);
    };

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Override
    public void beforeHostStart(VerificationHost host) throws Throwable {
        host.setMaintenanceIntervalMicros(TimeUnit.MILLISECONDS.toMicros(
                VerificationHost.FAST_MAINT_INTERVAL_MILLIS));
        this.host.addPrivilegedService(InMemoryLuceneDocumentIndexService.class);
        this.host.startServiceAndWait(InMemoryLuceneDocumentIndexService.class,
                InMemoryLuceneDocumentIndexService.SELF_LINK);

        this.host.startFactory(InMemoryExampleService.class, InMemoryExampleService::createFactory);
        this.host.startFactory(ExampleODLService.class, ExampleODLService::createFactory);
    }

    @Before
    public void setUp() {
        CommandLineArgumentParser.parseFromProperties(this);
        URI exampleFactoryUri = UriUtils.buildUri(
                this.host.getUri(), ExampleService.FACTORY_LINK);
        URI exampleODLFactoryUri = UriUtils.buildUri(
                this.host.getUri(), ExampleODLService.FACTORY_LINK);
        this.host.waitForReplicatedFactoryServiceAvailable(
                exampleFactoryUri);
        this.host.waitForReplicatedFactoryServiceAvailable(
                exampleODLFactoryUri);
    }

    private void setUpMultiNode() throws Throwable {
        this.host.setUpPeerHosts(this.nodeCount);
        this.host.joinNodesAndVerifyConvergence(this.nodeCount);

        URI exampleFactoryUri = UriUtils.buildUri(
                this.host.getPeerServiceUri(ExampleService.FACTORY_LINK));
        this.host.waitForReplicatedFactoryServiceAvailable(exampleFactoryUri);

        // expire node that went away quickly to avoid alot of log spam from gossip failures
        NodeGroupService.NodeGroupConfig cfg = new NodeGroupService.NodeGroupConfig();
        cfg.nodeRemovalDelayMicros = TimeUnit.SECONDS.toMicros(1);
        this.host.setNodeGroupConfig(cfg);

        for (VerificationHost h : this.host.getInProcessHostMap().values()) {
            h.addPrivilegedService(InMemoryLuceneDocumentIndexService.class);
            h.startCoreServicesSynchronously(new InMemoryLuceneDocumentIndexService());
            h.startFactory(new InMemoryExampleService());
            h.startFactory(new ExampleODLService());
        }

        URI inMemoryExampleFactoryUri = UriUtils.buildUri(
                this.host.getPeerServiceUri(InMemoryExampleService.FACTORY_LINK));
        this.host.waitForReplicatedFactoryServiceAvailable(inMemoryExampleFactoryUri);
        URI ODLExampleFactoryUri = UriUtils.buildUri(
                this.host.getPeerServiceUri(ExampleODLService.FACTORY_LINK));
        this.host.waitForReplicatedFactoryServiceAvailable(ODLExampleFactoryUri);
    }

    @After
    public void tearDown() {
        this.host.tearDownInProcessPeers();
        this.host.tearDown();
    }

    @Test
    public void ownershipValidation() throws Throwable {
        // This test verifies that only the owner node
        // executes the synchronization task. If the task
        // is started on a non-owner node, the task should
        // self-cancel.
        setUpMultiNode();

        ownershipValidationDo(ExampleService.FACTORY_LINK);
        ownershipValidationDo(InMemoryExampleService.FACTORY_LINK);
        ownershipValidationDo(ExampleODLService.FACTORY_LINK);
    }

    private void ownershipValidationDo(String factoryLink) throws Throwable {
        boolean skipAvailabilityCheck = factoryLink.equals(ExampleODLService.FACTORY_LINK) ? true : false;
        this.host.createExampleServices(this.host.getPeerHost(), this.serviceCount, null, skipAvailabilityCheck, factoryLink);
        long membershipUpdateTimeMicros = getLatestMembershipUpdateTime(this.host.getPeerHostUri());

        SynchronizationTaskService.State task = createSynchronizationTaskState(membershipUpdateTimeMicros, factoryLink);
        List<Operation> ops = this.host.getInProcessHostMap().keySet().stream()
                .map(uri -> Operation
                    .createPost(UriUtils.buildUri(uri, SynchronizationTaskService.FACTORY_LINK))
                    .setBody(task)
                    .setReferer(this.host.getUri())
                ).collect(toList());

        TestRequestSender sender = new TestRequestSender(this.host);
        List<SynchronizationTaskService.State> results = sender
                .sendAndWait(ops, SynchronizationTaskService.State.class);

        int finishedCount = 0;
        for (SynchronizationTaskService.State r : results) {
            assertTrue(r.taskInfo.stage == TaskState.TaskStage.FINISHED ||
                    r.taskInfo.stage == TaskState.TaskStage.CANCELLED);
            if (r.taskInfo.stage == TaskState.TaskStage.FINISHED) {
                finishedCount++;
            }
        }
        assertTrue(finishedCount == 1);
    }

    @Test
    public void serviceResynchOnFailure() throws Throwable {
        TestRequestSender sender = new TestRequestSender(this.host);

        // Test with all failed to synch services, after all retries the task will be in failed state.
        this.host.startFactory(SynchRetryExampleService.class, SynchRetryExampleService::createFactory);
        URI factoryUri = UriUtils.buildUri(
                this.host, SynchRetryExampleService.FACTORY_LINK);
        this.host.waitForReplicatedFactoryServiceAvailable(factoryUri);

        createExampleServices(sender, this.host, this.serviceCount, "fail", SynchRetryExampleService.FACTORY_LINK);

        SynchronizationTaskService.State task = createSynchronizationTaskState(
                null, SynchRetryExampleService.FACTORY_LINK, ServiceDocument.class);
        task.membershipUpdateTimeMicros = Utils.getNowMicrosUtc();

        // Add pagination in query results.
        task.queryResultLimit = this.serviceCount / 2;

        // Speed up the retries.
        this.host.setMaintenanceIntervalMicros(TimeUnit.MILLISECONDS.toMicros(1));

        Operation op = Operation
                .createPost(UriUtils.buildUri(this.host, SynchronizationTaskService.FACTORY_LINK))
                .setBody(task);

        SynchronizationTaskService.State result = sender
                .sendAndWait(op, SynchronizationTaskService.State.class);

        assertEquals(TaskState.TaskStage.FAILED, result.taskInfo.stage);
        assertEquals(0, result.synchCompletionCount);

        // Verify that half of the child services were failed.
        waitForSynchRetries(result, SynchronizationTaskService.STAT_NAME_CHILD_SYNCH_FAILURE_COUNT,
                (synchRetryCount) -> synchRetryCount.latestValue == task.queryResultLimit);

        // Test after all retries the task will be in failed state with at-least half
        // successful synched services in each page.
        createExampleServices(sender, this.host, this.serviceCount * 3, "pass", SynchRetryExampleService.FACTORY_LINK);
        task.queryResultLimit = this.serviceCount * 4;
        op = Operation
                .createPost(UriUtils.buildUri(this.host, SynchronizationTaskService.FACTORY_LINK))
                .setBody(task);

        result = sender.sendAndWait(op, SynchronizationTaskService.State.class);

        assertEquals(TaskState.TaskStage.FAILED, result.taskInfo.stage);
        assertEquals(this.serviceCount * 3, result.synchCompletionCount);

        // Verify that this.serviceCount of the child services were failed.
        waitForSynchRetries(result, SynchronizationTaskService.STAT_NAME_CHILD_SYNCH_FAILURE_COUNT,
                (synchRetryCount) -> synchRetryCount.latestValue == this.serviceCount);

        waitForSynchRetries(result, SynchronizationTaskService.STAT_NAME_SYNCH_RETRY_COUNT,
                (synchRetryCount) -> synchRetryCount.latestValue > 0);
    }

    private void waitForSynchRetries(SynchronizationTaskService.State state, String statName,
                                     Function<ServiceStats.ServiceStat, Boolean> check) {
        this.host.waitFor("Expected retries not completed", () -> {
            URI statsURI = UriUtils.buildStatsUri(this.host, state.documentSelfLink);
            ServiceStats stats = this.host.getServiceState(null, ServiceStats.class, statsURI);
            ServiceStats.ServiceStat synchRetryCount = stats.entries
                    .get(statName);

            return synchRetryCount != null && check.apply(synchRetryCount);
        });
    }

    private void createExampleServices(
            TestRequestSender sender, ServiceHost h, long serviceCount, String selfLinkPostfix, String factoryLink) {

        // create example services
        List<Operation> ops = new ArrayList<>();
        for (int i = 0; i < serviceCount; i++) {
            ServiceDocument initState = new ServiceDocument();
            initState.documentSelfLink = i + selfLinkPostfix;

            Operation post = Operation.createPost(
                    UriUtils.buildUri(h, factoryLink)).setBody(initState);
            ops.add(post);
        }

        sender.sendAndWait(ops, ServiceDocument.class);
    }

    @Test
    public void synchCounts() throws Throwable {
        synchCountsDo(ExampleService.FACTORY_LINK);
        synchCountsDo(InMemoryExampleService.FACTORY_LINK);
        synchCountsDo(ExampleODLService.FACTORY_LINK);
    }

    public void synchCountsDo(String factoryLink) throws Throwable {
        boolean skipAvailabilityCheck = factoryLink.equals(ExampleODLService.FACTORY_LINK) ? true : false;
        this.host.createExampleServices(this.host, this.serviceCount, null, skipAvailabilityCheck, factoryLink);
        SynchronizationTaskService.State task = createSynchronizationTaskState(Long.MAX_VALUE, factoryLink);

        // Add pagination in query results.
        task.queryResultLimit = this.serviceCount / 2;

        Operation op = Operation
                .createPost(UriUtils.buildUri(this.host, SynchronizationTaskService.FACTORY_LINK))
                .setBody(task);

        TestRequestSender sender = new TestRequestSender(this.host);
        SynchronizationTaskService.State result = sender
                .sendAndWait(op, SynchronizationTaskService.State.class);
        this.host.waitForTask(SynchronizationTaskService.State.class, result.documentSelfLink, TaskState.TaskStage.FINISHED);
        op = Operation
                .createGet(UriUtils.buildUri(this.host, result.documentSelfLink));
        result = sender.sendAndWait(op, SynchronizationTaskService.State.class);
        assertTrue(result.synchCompletionCount == this.serviceCount);

        // Restart the task to verify counter was reset.
        task = createSynchronizationTaskState(Long.MAX_VALUE, factoryLink);
        task.queryResultLimit = this.serviceCount / 2;

        op = Operation
                .createPost(UriUtils.buildUri(this.host, SynchronizationTaskService.FACTORY_LINK))
                .setBody(task);
        result = sender.sendAndWait(op, SynchronizationTaskService.State.class);

        this.host.waitForTask(SynchronizationTaskService.State.class, result.documentSelfLink, TaskState.TaskStage.FINISHED);
        op = Operation
                .createGet(UriUtils.buildUri(this.host, result.documentSelfLink));
        result = sender.sendAndWait(op, SynchronizationTaskService.State.class);
        assertTrue(result.synchCompletionCount == this.serviceCount);
    }

    @Test
    public void synchTaskStopsSelfPatchingOnFactoryDelete() throws Throwable {
        String factoryLink = ExampleService.FACTORY_LINK;
        this.host.createExampleServices(this.host, this.serviceCount, null, false, factoryLink);
        SynchronizationTaskService.State task = createSynchronizationTaskState(Long.MAX_VALUE, factoryLink);

        Operation op = Operation
                .createPost(UriUtils.buildUri(this.host, SynchronizationTaskService.FACTORY_LINK))
                .setBody(task);

        TestRequestSender sender = new TestRequestSender(this.host);
        sender.sendRequest(Operation.createDelete(UriUtils.buildUri(this.host, factoryLink)));
        SynchronizationTaskService.State result = sender.sendAndWait(op, SynchronizationTaskService.State.class);

        // Verify that patch count stops incrementing after a while
        AtomicInteger previousValue = new AtomicInteger();
        this.host.waitFor("Expected synch task to stop patching itself", () -> {
            // Get the latest patch count
            URI statsURI = UriUtils.buildStatsUri(this.host, result.documentSelfLink);
            ServiceStats stats = this.host.getServiceState(null, ServiceStats.class, statsURI);
            ServiceStats.ServiceStat synchRetryCount = stats.entries
                    .get(STAT_NAME_PATCH_REQUEST_COUNT);

            return previousValue.getAndSet((int)synchRetryCount.latestValue) == synchRetryCount.latestValue;
        });
    }

    @Test
    public void stateValidation() throws Throwable {
        // This test verifies state validation when
        // a synchronization task is created.

        // handleStart validation.
        URI taskFactoryUri = UriUtils.buildUri(
                this.host.getUri(), SynchronizationTaskService.FACTORY_LINK);

        validateInvalidStartState(taskFactoryUri, true, s -> s.factorySelfLink = null);
        validateInvalidStartState(taskFactoryUri, true, s -> s.factoryStateKind = null);
        validateInvalidStartState(taskFactoryUri, true, s -> s.nodeSelectorLink = null);
        validateInvalidStartState(taskFactoryUri, true, s -> s.queryResultLimit = -1);
        validateInvalidStartState(taskFactoryUri, true, s -> s.membershipUpdateTimeMicros = 10L);
        validateInvalidStartState(taskFactoryUri, true, s -> s.queryPageReference = taskFactoryUri);
        validateInvalidStartState(taskFactoryUri, true,
                s -> s.subStage = SynchronizationTaskService.SubStage.SYNCHRONIZE);
        validateInvalidStartState(taskFactoryUri, true,
                s -> s.childOptions = EnumSet.of(Service.ServiceOption.PERSISTENCE));
        validateInvalidStartState(taskFactoryUri, true,
                s -> {
                    s.taskInfo = new TaskState();
                    s.taskInfo.stage = TaskState.TaskStage.STARTED;
                });

        // handlePut validation
        validateInvalidPutRequest(taskFactoryUri, true, s -> s.queryResultLimit = -1);
        validateInvalidPutRequest(taskFactoryUri, true, s -> s.membershipUpdateTimeMicros = null);

        // Let's also test successful requests, to make sure our
        // test methods are doing the right thing.
        validateInvalidStartState(taskFactoryUri, false, null);
        validateInvalidPutRequest(taskFactoryUri, false, null);
    }

    @Test
    public void cacheUpdateAfterSynchronization() throws Throwable {
        setUpMultiNode();
        for (VerificationHost host : this.host.getInProcessHostMap().values()) {
            // avoid cache clear
            host.setServiceCacheClearDelayMicros(TimeUnit.MINUTES.toMicros(3));
        }
        this.host.setNodeGroupQuorum(this.nodeCount - 1);
        this.host.waitForNodeGroupConvergence();

        List<ExampleServiceState> exampleStates = this.host.createExampleServices(
                this.host.getPeerHost(), this.serviceCount, null, ExampleService.FACTORY_LINK);
        Map<String, ExampleServiceState> exampleStatesMap =
                exampleStates.stream().collect(Collectors.toMap(s -> s.documentSelfLink, s -> s));

        VerificationHost hostToStop = this.host.getPeerHost();
        this.host.stopHost(hostToStop);
        this.host.waitForNodeUnavailable(ServiceUriPaths.DEFAULT_NODE_GROUP, this.host.getInProcessHostMap().values(), hostToStop);
        this.host.waitForNodeGroupConvergence(this.host.getPeerCount());

        VerificationHost peer = this.host.getPeerHost();
        this.host.waitForReplicatedFactoryChildServiceConvergence(
                this.host.getNodeGroupToFactoryMap(ExampleService.FACTORY_LINK),
                exampleStatesMap,
                this.exampleStateConvergenceChecker,
                exampleStatesMap.size(),
                0, this.nodeCount - 1);
        // get cached state
        List<Operation> ops = new ArrayList<>();
        for (ExampleServiceState s : exampleStates) {
            Operation op = Operation.createGet(peer, s.documentSelfLink);
            ops.add(op);
        }

        List<ExampleServiceState> newExampleStates = this.host.getTestRequestSender().sendAndWait(ops, ExampleServiceState.class);
        Map<String, ExampleServiceState> newExampleStatesMap =
                newExampleStates.stream().collect(Collectors.toMap(s -> s.documentSelfLink, s -> s));
        for (ExampleServiceState s : exampleStates) {
            ExampleServiceState ns = newExampleStatesMap.get(s.documentSelfLink);
            if (ns.documentEpoch > s.documentEpoch) {
                // owner change
                ServiceHost newOwner =
                        this.host.getInProcessHostMap().values().stream().filter(h -> h.getId().equals(ns.documentOwner)).iterator().next();

                QueryTask.Query query = QueryTask.Query.Builder.create()
                        .addKindFieldClause(ExampleServiceState.class)
                        .addFieldClause(ServiceDocument.FIELD_NAME_SELF_LINK, s.documentSelfLink)
                        .build();
                QueryTask task = QueryTask.Builder.createDirectTask()
                        .setQuery(query)
                        .addOption(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT)
                        .build();
                // query latest version in index after synchronization
                Operation op = Operation.createPost(newOwner, ServiceUriPaths.CORE_LOCAL_QUERY_TASKS)
                        .setBody(task);
                // check cache has latest state
                task = this.host.getTestRequestSender().sendAndWait(op, QueryTask.class);
                ServiceDocumentQueryResult result = task.results;
                assertEquals(1, result.documentCount.longValue());
                ExampleServiceState nsFromIndex
                        = Utils.fromJson(result.documents.values().iterator().next(), ExampleServiceState.class);
                // service owner should cache latest version
                // version in cache should be no less than index at owner node
                assertFalse(ns.documentVersion < nsFromIndex.documentVersion);
            }
        }
    }

    private long getLatestMembershipUpdateTime(URI nodeUri) throws Throwable {
        NodeGroupService.NodeGroupState ngs = this.host.getServiceState(null,
                NodeGroupService.NodeGroupState.class,
                UriUtils.buildUri(nodeUri, ServiceUriPaths.DEFAULT_NODE_GROUP));
        return ngs.membershipUpdateTimeMicros;
    }

    private SynchronizationTaskService.State createSynchronizationTaskState(
            Long membershipUpdateTimeMicros) {
        return createSynchronizationTaskState(
                membershipUpdateTimeMicros, ExampleService.FACTORY_LINK, ExampleService.ExampleServiceState.class);
    }

    private SynchronizationTaskService.State createSynchronizationTaskState(
            Long membershipUpdateTimeMicros, String factoryLink) {
        return createSynchronizationTaskState(
                membershipUpdateTimeMicros, factoryLink, ExampleService.ExampleServiceState.class);
    }

    private SynchronizationTaskService.State createSynchronizationTaskState(
            Long membershipUpdateTimeMicros, String factoryLink, Class<? extends ServiceDocument> type) {
        SynchronizationTaskService.State task = new SynchronizationTaskService.State();
        task.documentSelfLink = UriUtils.convertPathCharsFromLink(factoryLink);
        task.factorySelfLink = factoryLink;
        task.factoryStateKind = Utils.buildKind(type);
        task.membershipUpdateTimeMicros = membershipUpdateTimeMicros;
        task.nodeSelectorLink = ServiceUriPaths.DEFAULT_NODE_SELECTOR;
        task.queryResultLimit = 1000;
        task.taskInfo = TaskState.create();
        task.taskInfo.isDirect = true;

        return task;
    }

    private void validateInvalidStartState(URI taskFactoryUri,
            boolean expectFailure, Consumer<SynchronizationTaskService.State> stateSetter)
    throws Throwable {
        String factorySelfLink = UUID.randomUUID().toString();
        URI taskUri = UriUtils.extendUri(
                taskFactoryUri, UriUtils.convertPathCharsFromLink(factorySelfLink));

        SynchronizationTaskService.State task = createSynchronizationTaskState(null);
        task.factorySelfLink = factorySelfLink;
        task.documentSelfLink = factorySelfLink;

        if (stateSetter != null) {
            stateSetter.accept(task);
        }

        TestContext ctx = testCreate(1);
        Operation post = Operation
                .createPost(taskUri)
                .setBody(task)
                .setCompletion((o, e) -> {
                    if (expectFailure) {
                        if (o.getStatusCode() == Operation.STATUS_CODE_BAD_REQUEST) {
                            ctx.completeIteration();
                            return;
                        }
                        ctx.failIteration(new IllegalStateException(
                                "request was expected to fail"));
                    } else {
                        if (o.getStatusCode() == Operation.STATUS_CODE_ACCEPTED) {
                            ctx.completeIteration();
                            return;
                        }
                        ctx.failIteration(new IllegalStateException(
                                "request was expected to succeed"));
                    }
                });

        SynchronizationTaskService service = SynchronizationTaskService
                .create(() -> new ExampleService());
        this.host.startService(post, service);
        testWait(ctx);
    }

    private void validateInvalidPutRequest(URI taskFactoryUri,
            boolean expectFailure, Consumer<SynchronizationTaskService.State> stateSetter)
            throws Throwable {
        SynchronizationTaskService.State state =
                createSynchronizationTaskState(Long.MAX_VALUE);

        if (stateSetter != null) {
            stateSetter.accept(state);
        }

        TestContext ctx = testCreate(1);
        Operation op = Operation
                .createPost(taskFactoryUri)
                .setBody(state)
                .setReferer(this.host.getUri())
                .setCompletion((o, e) -> {
                    if (expectFailure) {
                        if (o.getStatusCode() == Operation.STATUS_CODE_BAD_REQUEST) {
                            ctx.completeIteration();
                            return;
                        }
                        ctx.failIteration(new IllegalStateException(
                                "request was expected to fail"));
                    } else {
                        if (o.getStatusCode() == Operation.STATUS_CODE_OK) {
                            ctx.completeIteration();
                            return;
                        }
                        ctx.failIteration(new IllegalStateException(
                                "request was expected to succeed"));
                    }
                });
        this.host.sendRequest(op);
        testWait(ctx);
    }

    @Test
    public void queryPageCleanUp() throws Throwable {
        setUpMultiNode();
        VerificationHost node = this.host.getPeerHost();
        TestRequestSender sender = this.host.getTestRequestSender();

        node.createExampleServices(node, this.serviceCount, null);

        // find factory owner
        VerificationHost factoryOwner = this.host.getOwnerPeer(ExampleService.FACTORY_LINK);

        // make sure currently there is no query service
        int queryPageCountBefore = factoryOwner
                .getServicePathsByPrefix(ServiceUriPaths.CORE_QUERY_PAGE).size();
        int broadCastPageCountBefore = factoryOwner
                .getServicePathsByPrefix(ServiceUriPaths.CORE_QUERY_BROADCAST_PAGE).size();
        assertEquals("Expect no query pages", 0, queryPageCountBefore);
        assertEquals("Expect no broad cast pages", 0, broadCastPageCountBefore);

        SynchronizationTaskService.State task = createSynchronizationTaskState(Long.MAX_VALUE,
                ExampleService.FACTORY_LINK);
        task.queryResultLimit = this.serviceCount / 5;

        Operation op = Operation
                .createPost(
                        UriUtils.buildUri(factoryOwner, SynchronizationTaskService.FACTORY_LINK))
                .setBody(task);

        SynchronizationTaskService.State result = sender.sendAndWait(op,
                SynchronizationTaskService.State.class);
        factoryOwner.waitForTask(SynchronizationTaskService.State.class, result.documentSelfLink,
                TaskState.TaskStage.FINISHED);

        int queryPageCountAfter = factoryOwner
                .getServicePathsByPrefix(ServiceUriPaths.CORE_QUERY_PAGE).size();
        int broadcastPageCountAfter = factoryOwner
                .getServicePathsByPrefix(ServiceUriPaths.CORE_QUERY_BROADCAST_PAGE).size();
        assertEquals("Expect no query pages", 0, queryPageCountAfter);
        assertEquals("Expect no broad cast pages", 0, broadcastPageCountAfter);
    }

}
