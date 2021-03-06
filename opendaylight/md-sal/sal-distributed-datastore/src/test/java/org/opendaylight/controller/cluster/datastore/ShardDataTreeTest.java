/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.cluster.datastore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.opendaylight.controller.cluster.datastore.ShardDataTreeMocking.immediateCanCommit;
import static org.opendaylight.controller.cluster.datastore.ShardDataTreeMocking.immediateCommit;
import static org.opendaylight.controller.cluster.datastore.ShardDataTreeMocking.immediatePreCommit;

import com.google.common.base.Optional;
import com.google.common.base.Ticker;
import com.google.common.collect.Maps;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opendaylight.controller.cluster.datastore.jmx.mbeans.shard.ShardStats;
import org.opendaylight.controller.md.cluster.datastore.model.CarsModel;
import org.opendaylight.controller.md.cluster.datastore.model.PeopleModel;
import org.opendaylight.controller.md.cluster.datastore.model.SchemaContextHelper;
import org.opendaylight.controller.md.sal.dom.api.DOMDataTreeChangeListener;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeCandidate;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeCandidateTip;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeCandidates;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeModification;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeSnapshot;
import org.opendaylight.yangtools.yang.data.api.schema.tree.ModificationType;
import org.opendaylight.yangtools.yang.data.api.schema.tree.TreeType;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;

public class ShardDataTreeTest extends AbstractTest {

    private final Shard mockShard = Mockito.mock(Shard.class);


    private SchemaContext fullSchema;

    @Before
    public void setUp() {
        doReturn(true).when(mockShard).canSkipPayload();
        doReturn(Ticker.systemTicker()).when(mockShard).ticker();
        doReturn(Mockito.mock(ShardStats.class)).when(mockShard).getShardMBean();

        fullSchema = SchemaContextHelper.full();
    }

    @Test
    public void testWrite() throws ExecutionException, InterruptedException {
        modify(new ShardDataTree(mockShard, fullSchema, TreeType.OPERATIONAL), false, true, true);
    }

    @Test
    public void testMerge() throws ExecutionException, InterruptedException {
        modify(new ShardDataTree(mockShard, fullSchema, TreeType.OPERATIONAL), true, true, true);
    }


    private void modify(final ShardDataTree shardDataTree, final boolean merge, final boolean expectedCarsPresent,
            final boolean expectedPeoplePresent) throws ExecutionException, InterruptedException {

        assertEquals(fullSchema, shardDataTree.getSchemaContext());

        final ReadWriteShardDataTreeTransaction transaction =
                shardDataTree.newReadWriteTransaction(nextTransactionId());

        final DataTreeModification snapshot = transaction.getSnapshot();

        assertNotNull(snapshot);

        if (merge) {
            snapshot.merge(CarsModel.BASE_PATH, CarsModel.create());
            snapshot.merge(PeopleModel.BASE_PATH, PeopleModel.create());
        } else {
            snapshot.write(CarsModel.BASE_PATH, CarsModel.create());
            snapshot.write(PeopleModel.BASE_PATH, PeopleModel.create());
        }

        final ShardDataTreeCohort cohort = shardDataTree.finishTransaction(transaction);

        immediateCanCommit(cohort);
        immediatePreCommit(cohort);
        immediateCommit(cohort);

        final ReadOnlyShardDataTreeTransaction readOnlyShardDataTreeTransaction =
                shardDataTree.newReadOnlyTransaction(nextTransactionId());

        final DataTreeSnapshot snapshot1 = readOnlyShardDataTreeTransaction.getSnapshot();

        final Optional<NormalizedNode<?, ?>> optional = snapshot1.readNode(CarsModel.BASE_PATH);

        assertEquals(expectedCarsPresent, optional.isPresent());

        final Optional<NormalizedNode<?, ?>> optional1 = snapshot1.readNode(PeopleModel.BASE_PATH);

        assertEquals(expectedPeoplePresent, optional1.isPresent());

    }

    @Test
    public void bug4359AddRemoveCarOnce() throws ExecutionException, InterruptedException {
        final ShardDataTree shardDataTree = new ShardDataTree(mockShard, fullSchema, TreeType.OPERATIONAL);

        final List<DataTreeCandidateTip> candidates = new ArrayList<>();
        candidates.add(addCar(shardDataTree));
        candidates.add(removeCar(shardDataTree));

        final NormalizedNode<?, ?> expected = getCars(shardDataTree);

        applyCandidates(shardDataTree, candidates);

        final NormalizedNode<?, ?> actual = getCars(shardDataTree);

        assertEquals(expected, actual);
    }

    @Test
    public void bug4359AddRemoveCarTwice() throws ExecutionException, InterruptedException {
        final ShardDataTree shardDataTree = new ShardDataTree(mockShard, fullSchema, TreeType.OPERATIONAL);

        final List<DataTreeCandidateTip> candidates = new ArrayList<>();
        candidates.add(addCar(shardDataTree));
        candidates.add(removeCar(shardDataTree));
        candidates.add(addCar(shardDataTree));
        candidates.add(removeCar(shardDataTree));

        final NormalizedNode<?, ?> expected = getCars(shardDataTree);

        applyCandidates(shardDataTree, candidates);

        final NormalizedNode<?, ?> actual = getCars(shardDataTree);

        assertEquals(expected, actual);
    }

    @Test
    public void testListenerNotifiedOnApplySnapshot() throws Exception {
        final ShardDataTree shardDataTree = new ShardDataTree(mockShard, fullSchema, TreeType.OPERATIONAL);

        DOMDataTreeChangeListener listener = mock(DOMDataTreeChangeListener.class);
        shardDataTree.registerTreeChangeListener(CarsModel.CAR_LIST_PATH.node(CarsModel.CAR_QNAME), listener);

        addCar(shardDataTree, "optima");

        verifyOnDataTreeChanged(listener, dtc -> {
            assertEquals("getModificationType", ModificationType.WRITE, dtc.getRootNode().getModificationType());
            assertEquals("getRootPath", CarsModel.newCarPath("optima"), dtc.getRootPath());
        });

        addCar(shardDataTree, "sportage");

        verifyOnDataTreeChanged(listener, dtc -> {
            assertEquals("getModificationType", ModificationType.WRITE, dtc.getRootNode().getModificationType());
            assertEquals("getRootPath", CarsModel.newCarPath("sportage"), dtc.getRootPath());
        });

        ShardDataTree newDataTree = new ShardDataTree(mockShard, fullSchema, TreeType.OPERATIONAL);
        addCar(newDataTree, "optima");
        addCar(newDataTree, "murano");

        shardDataTree.applySnapshot(newDataTree.takeStateSnapshot());

        Map<YangInstanceIdentifier, ModificationType> expChanges = Maps.newHashMap();
        expChanges.put(CarsModel.newCarPath("optima"), ModificationType.WRITE);
        expChanges.put(CarsModel.newCarPath("murano"), ModificationType.WRITE);
        expChanges.put(CarsModel.newCarPath("sportage"), ModificationType.DELETE);
        verifyOnDataTreeChanged(listener, dtc -> {
            ModificationType expType = expChanges.remove(dtc.getRootPath());
            assertNotNull("Got unexpected change for " + dtc.getRootPath(), expType);
            assertEquals("getModificationType", expType, dtc.getRootNode().getModificationType());
        });

        if (!expChanges.isEmpty()) {
            fail("Missing change notifications: " + expChanges);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void verifyOnDataTreeChanged(DOMDataTreeChangeListener listener,
            Consumer<DataTreeCandidate> callback) {
        ArgumentCaptor<Collection> changes = ArgumentCaptor.forClass(Collection.class);
        verify(listener, atLeastOnce()).onDataTreeChanged(changes.capture());
        for (Collection list : changes.getAllValues()) {
            for (Object dtc : list) {
                callback.accept((DataTreeCandidate)dtc);
            }
        }

        reset(listener);
    }

    private static NormalizedNode<?, ?> getCars(final ShardDataTree shardDataTree) {
        final ReadOnlyShardDataTreeTransaction readOnlyShardDataTreeTransaction =
                shardDataTree.newReadOnlyTransaction(nextTransactionId());
        final DataTreeSnapshot snapshot1 = readOnlyShardDataTreeTransaction.getSnapshot();

        final Optional<NormalizedNode<?, ?>> optional = snapshot1.readNode(CarsModel.BASE_PATH);

        assertEquals(true, optional.isPresent());

        return optional.get();
    }

    private static DataTreeCandidateTip addCar(final ShardDataTree shardDataTree)
            throws ExecutionException, InterruptedException {
        return addCar(shardDataTree, "altima");
    }

    private static DataTreeCandidateTip addCar(final ShardDataTree shardDataTree, String name)
            throws ExecutionException, InterruptedException {
        return doTransaction(shardDataTree, snapshot -> {
            snapshot.merge(CarsModel.BASE_PATH, CarsModel.emptyContainer());
            snapshot.merge(CarsModel.CAR_LIST_PATH, CarsModel.newCarMapNode());
            snapshot.write(CarsModel.newCarPath(name), CarsModel.newCarEntry(name, new BigInteger("100")));
        });
    }

    private static DataTreeCandidateTip removeCar(final ShardDataTree shardDataTree)
            throws ExecutionException, InterruptedException {
        return doTransaction(shardDataTree, snapshot -> snapshot.delete(CarsModel.newCarPath("altima")));
    }

    @FunctionalInterface
    private interface DataTreeOperation {
        void execute(DataTreeModification snapshot);
    }

    private static DataTreeCandidateTip doTransaction(final ShardDataTree shardDataTree,
            final DataTreeOperation operation) throws ExecutionException, InterruptedException {
        final ReadWriteShardDataTreeTransaction transaction =
                shardDataTree.newReadWriteTransaction(nextTransactionId());
        final DataTreeModification snapshot = transaction.getSnapshot();
        operation.execute(snapshot);
        final ShardDataTreeCohort cohort = shardDataTree.finishTransaction(transaction);

        immediateCanCommit(cohort);
        immediatePreCommit(cohort);
        final DataTreeCandidateTip candidate = cohort.getCandidate();
        immediateCommit(cohort);

        return candidate;
    }

    private static DataTreeCandidateTip applyCandidates(final ShardDataTree shardDataTree,
            final List<DataTreeCandidateTip> candidates) throws ExecutionException, InterruptedException {
        final ReadWriteShardDataTreeTransaction transaction =
                shardDataTree.newReadWriteTransaction(nextTransactionId());
        final DataTreeModification snapshot = transaction.getSnapshot();
        for (final DataTreeCandidateTip candidateTip : candidates) {
            DataTreeCandidates.applyToModification(snapshot, candidateTip);
        }
        final ShardDataTreeCohort cohort = shardDataTree.finishTransaction(transaction);

        immediateCanCommit(cohort);
        immediatePreCommit(cohort);
        final DataTreeCandidateTip candidate = cohort.getCandidate();
        immediateCommit(cohort);

        return candidate;
    }
}
