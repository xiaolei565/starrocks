// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.replication;

import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Tablet;
import com.starrocks.common.io.DeepCopy;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.leader.LeaderImpl;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.sql.analyzer.AnalyzeTestUtil;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.system.Backend;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTask;
import com.starrocks.task.AgentTaskExecutor;
import com.starrocks.task.RemoteSnapshotTask;
import com.starrocks.task.ReplicateSnapshotTask;
import com.starrocks.thrift.TBackend;
import com.starrocks.thrift.TFinishTaskRequest;
import com.starrocks.thrift.TIndexReplicationInfo;
import com.starrocks.thrift.TPartitionReplicationInfo;
import com.starrocks.thrift.TReplicaReplicationInfo;
import com.starrocks.thrift.TSnapshotInfo;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TTableReplicationRequest;
import com.starrocks.thrift.TTableType;
import com.starrocks.thrift.TTabletReplicationInfo;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReplicationJobTest {
    private static StarRocksAssert starRocksAssert;

    private static Database db;
    private static OlapTable table;
    private static OlapTable srcTable;
    private static Partition partition;
    private static Partition srcPartition;
    private ReplicationJob job;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster(RunMode.SHARED_NOTHING);
        AnalyzeTestUtil.init();
        starRocksAssert = new StarRocksAssert(AnalyzeTestUtil.getConnectContext());
        starRocksAssert.withDatabase("test").useDatabase("test");

        db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");

        String sql = "create table single_partition_duplicate_key (key1 int, key2 varchar(10))\n" +
                "distributed by hash(key1) buckets 1\n" +
                "properties('replication_num' = '1'); ";
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.parseStmtWithNewParser(sql,
                AnalyzeTestUtil.getConnectContext());
        StarRocksAssert.utCreateTableWithRetry(createTableStmt);
        table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable(db.getFullName(), "single_partition_duplicate_key");
        srcTable = DeepCopy.copyWithGson(table, OlapTable.class);

        partition = table.getPartitions().iterator().next();
        srcPartition = srcTable.getPartitions().iterator().next();

        new MockUp<AgentTaskExecutor>() {
            @Mock
            public void submit(AgentBatchTask task) {

            }
        };
    }

    @BeforeEach
    public void setUp() throws Exception {
        partition.getDefaultPhysicalPartition().updateVersionForRestore(10);
        srcPartition.getDefaultPhysicalPartition().updateVersionForRestore(100);
        partition.getDefaultPhysicalPartition().setDataVersion(8);
        partition.getDefaultPhysicalPartition().setNextDataVersion(9);
        srcPartition.getDefaultPhysicalPartition().setDataVersion(98);
        srcPartition.getDefaultPhysicalPartition().setNextDataVersion(99);

        job = new ReplicationJob(null, "test_token", db.getId(), table, srcTable,
                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo());
    }

    @Test
    public void testJobId() {
        ReplicationJob jobWithoutId = new ReplicationJob(null, "test_token", db.getId(), table, srcTable,
                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo());
        Assertions.assertFalse(jobWithoutId.getJobId().isEmpty());
        ReplicationJob jobWithId = new ReplicationJob("fake_id", "test_token", db.getId(), table, srcTable,
                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo());
        Assertions.assertEquals("fake_id", jobWithId.getJobId());
    }

    @Test
    public void testNormal() throws Exception {
        Assertions.assertFalse(ReplicationJobState.INITIALIZING.equals(job));
        Assertions.assertEquals(ReplicationJobState.INITIALIZING, job.getState());
        Assertions.assertEquals(ReplicationJobState.INITIALIZING.name(), job.getState().name());
        Assertions.assertEquals(ReplicationJobState.INITIALIZING.toString(), job.getState().toString());
        Assertions.assertEquals(ReplicationJobState.INITIALIZING.hashCode(), job.getState().hashCode());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        Map<AgentTask, AgentTask> runningTasks = Deencapsulation.getField(job, "runningTasks");
        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            request.setTask_status(new TStatus(TStatusCode.OK));
            request.setSnapshot_info(newTSnapshotInfo(new TBackend("test_host", 0, 0), "test_snapshot_path", true));
            job.finishRemoteSnapshotTask((RemoteSnapshotTask) task, request);

            Deencapsulation.invoke(new LeaderImpl(), "finishRemoteSnapshotTask",
                    (RemoteSnapshotTask) task, request);
            ((RemoteSnapshotTask) task).toThrift();
            task.toString();
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.REPLICATING, job.getState());

        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            request.setTask_status(new TStatus(TStatusCode.OK));
            job.finishReplicateSnapshotTask((ReplicateSnapshotTask) task, request);

            Deencapsulation.invoke(new LeaderImpl(), "finishReplicateSnapshotTask",
                    (ReplicateSnapshotTask) task, request);
            ((ReplicateSnapshotTask) task).toThrift();
            task.toString();
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.COMMITTED, job.getState());

        Assertions.assertEquals(partition.getDefaultPhysicalPartition().getCommittedVersion(),
                srcPartition.getDefaultPhysicalPartition().getVisibleVersion());
        // data version == visible version in shared-nothing mode
        Assertions.assertEquals(partition.getDefaultPhysicalPartition().getCommittedDataVersion(),
                srcPartition.getDefaultPhysicalPartition().getVisibleVersion());
    }

    @Test
    public void testInitializingCancel() {
        Assertions.assertEquals(ReplicationJobState.INITIALIZING, job.getState());

        job.cancel();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());
    }

    @Test
    public void testSnapshotingCancel() {
        Assertions.assertEquals(ReplicationJobState.INITIALIZING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        job.cancel();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());
    }

    @Test
    public void testReplicatingCancel() {
        Assertions.assertEquals(ReplicationJobState.INITIALIZING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        Map<AgentTask, AgentTask> runningTasks = Deencapsulation.getField(job, "runningTasks");
        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            request.setTask_status(new TStatus(TStatusCode.OK));
            request.setSnapshot_info(newTSnapshotInfo(new TBackend("test_host", 0, 0), "test_snapshot_path", true));
            job.finishRemoteSnapshotTask((RemoteSnapshotTask) task, request);
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.REPLICATING, job.getState());

        job.cancel();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());
    }

    @Test
    public void testCommittedCancel() {
        Assertions.assertEquals(ReplicationJobState.INITIALIZING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        Map<AgentTask, AgentTask> runningTasks = Deencapsulation.getField(job, "runningTasks");
        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            request.setTask_status(new TStatus(TStatusCode.OK));
            request.setSnapshot_info(newTSnapshotInfo(new TBackend("test_host", 0, 0), "test_snapshot_path", true));
            job.finishRemoteSnapshotTask((RemoteSnapshotTask) task, request);
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.REPLICATING, job.getState());

        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            request.setTask_status(new TStatus(TStatusCode.OK));
            job.finishReplicateSnapshotTask((ReplicateSnapshotTask) task, request);
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.COMMITTED, job.getState());

        job.cancel();
        Assertions.assertEquals(ReplicationJobState.COMMITTED, job.getState());
    }

    @Test
    public void testSnapshotingFailed() throws Exception {
        Assertions.assertEquals(ReplicationJobState.INITIALIZING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        Map<AgentTask, AgentTask> runningTasks = Deencapsulation.getField(job, "runningTasks");
        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            request.setTask_status(new TStatus(TStatusCode.OK));
            job.finishRemoteSnapshotTask((RemoteSnapshotTask) task, request);
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());
    }

    @Test
    public void testReplicatingFailed() throws Exception {
        Assertions.assertEquals(ReplicationJobState.INITIALIZING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        job.run();
        Assertions.assertEquals(ReplicationJobState.SNAPSHOTING, job.getState());

        Map<AgentTask, AgentTask> runningTasks = Deencapsulation.getField(job, "runningTasks");
        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            request.setTask_status(new TStatus(TStatusCode.OK));
            request.setSnapshot_info(newTSnapshotInfo(new TBackend("test_host", 0, 0), "test_snapshot_path", true));
            job.finishRemoteSnapshotTask((RemoteSnapshotTask) task, request);
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.REPLICATING, job.getState());

        for (AgentTask task : runningTasks.values()) {
            TFinishTaskRequest request = new TFinishTaskRequest();
            TStatus status = new TStatus(TStatusCode.INTERNAL_ERROR);
            status.addToError_msgs("failed");
            request.setTask_status(status);
            job.finishReplicateSnapshotTask((ReplicateSnapshotTask) task, request);
        }

        job.run();
        Assertions.assertEquals(ReplicationJobState.ABORTED, job.getState());
    }

    @Test
    public void testInitializedByThrift() {
        TTableReplicationRequest request = new TTableReplicationRequest();
        request.username = "test_usename";
        request.password = "test_password";
        request.database_id = db.getId();
        request.table_id = table.getId();
        request.src_token = "test_token";
        request.src_table_type = TTableType.OLAP_TABLE;
        request.src_table_data_size = 100;

        request.partition_replication_infos = new HashMap<Long, TPartitionReplicationInfo>();
        TPartitionReplicationInfo partitionInfo = new TPartitionReplicationInfo();
        Partition partition = table.getPartitions().iterator().next();
        Partition srcPartition = srcTable.getPartitions().iterator().next();
        partitionInfo.partition_id = partition.getId();
        partitionInfo.src_version = srcPartition.getDefaultPhysicalPartition().getVisibleVersion();
        partitionInfo.src_version_epoch = srcPartition.getDefaultPhysicalPartition().getVersionEpoch();
        request.partition_replication_infos.put(partitionInfo.partition_id, partitionInfo);

        partitionInfo.index_replication_infos = new HashMap<Long, TIndexReplicationInfo>();
        TIndexReplicationInfo indexInfo = new TIndexReplicationInfo();
        MaterializedIndex index = partition.getDefaultPhysicalPartition().getBaseIndex();
        MaterializedIndex srcIndex = srcPartition.getDefaultPhysicalPartition().getBaseIndex();
        indexInfo.index_id = index.getId();
        indexInfo.src_schema_hash = srcTable.getSchemaHashByIndexId(srcIndex.getId());
        partitionInfo.index_replication_infos.put(indexInfo.index_id, indexInfo);

        indexInfo.tablet_replication_infos = new HashMap<Long, TTabletReplicationInfo>();
        List<Tablet> tablets = index.getTablets();
        List<Tablet> srcTablets = srcIndex.getTablets();
        for (int i = 0; i < tablets.size(); ++i) {
            Tablet tablet = tablets.get(i);
            Tablet srcTablet = srcTablets.get(i);
            TTabletReplicationInfo tabletInfo = new TTabletReplicationInfo();
            tabletInfo.tablet_id = tablet.getId();
            tabletInfo.src_tablet_id = srcTablet.getId();
            indexInfo.tablet_replication_infos.put(tabletInfo.tablet_id, tabletInfo);

            tabletInfo.replica_replication_infos = new ArrayList<TReplicaReplicationInfo>();
            TReplicaReplicationInfo replicaInfo = new TReplicaReplicationInfo();
            Backend backend = GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackends().iterator()
                    .next();
            replicaInfo.src_backend = new TBackend(backend.getHost(), backend.getBePort(), backend.getHttpPort());
            tabletInfo.replica_replication_infos.add(replicaInfo);
        }

        try {
            new LeaderImpl().startTableReplication(request);
        } catch (Exception e) {
            Assertions.assertNull(e);
        }
    }

    private static TSnapshotInfo newTSnapshotInfo(TBackend backend, String snapshotPath, boolean incrementalSnapshot) {
        TSnapshotInfo tSnapshotInfo = new TSnapshotInfo();
        tSnapshotInfo.setBackend(backend);
        tSnapshotInfo.setSnapshot_path(snapshotPath);
        tSnapshotInfo.setIncremental_snapshot(incrementalSnapshot);
        return tSnapshotInfo;
    }
}