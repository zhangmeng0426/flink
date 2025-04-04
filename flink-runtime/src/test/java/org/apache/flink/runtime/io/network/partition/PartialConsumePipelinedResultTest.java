/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RpcOptions;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobGraphTestUtils;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.testutils.InternalMiniClusterExtension;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.testutils.TestingUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.ByteBuffer;

import static org.apache.flink.runtime.util.JobVertexConnectionUtils.connectNewDataSetAsInput;

/** Test for consuming a pipelined result only partially. */
class PartialConsumePipelinedResultTest {

    // Test configuration
    private static final int NUMBER_OF_TMS = 1;
    private static final int NUMBER_OF_SLOTS_PER_TM = 1;
    private static final int PARALLELISM = NUMBER_OF_TMS * NUMBER_OF_SLOTS_PER_TM;

    private static final int NUMBER_OF_NETWORK_BUFFERS = 128;

    @RegisterExtension
    private static final InternalMiniClusterExtension MINI_CLUSTER_RESOURCE =
            new InternalMiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setConfiguration(getFlinkConfiguration())
                            .setNumberTaskManagers(NUMBER_OF_TMS)
                            .setNumberSlotsPerTaskManager(NUMBER_OF_SLOTS_PER_TM)
                            .build());

    private static Configuration getFlinkConfiguration() {
        final Configuration config = new Configuration();
        config.set(RpcOptions.ASK_TIMEOUT_DURATION, TestingUtils.DEFAULT_ASK_TIMEOUT);

        return config;
    }

    /**
     * Tests a fix for FLINK-1930.
     *
     * <p>When consuming a pipelined result only partially, is is possible that local channels
     * release the buffer pool, which is associated with the result partition, too early. If the
     * producer is still producing data when this happens, it runs into an IllegalStateException,
     * because of the destroyed buffer pool.
     *
     * @see <a href="https://issues.apache.org/jira/browse/FLINK-1930">FLINK-1930</a>
     */
    @Test
    void testPartialConsumePipelinedResultReceiver() throws Exception {
        final JobVertex sender = new JobVertex("Sender");
        sender.setInvokableClass(SlowBufferSender.class);
        sender.setParallelism(PARALLELISM);

        final JobVertex receiver = new JobVertex("Receiver");
        receiver.setInvokableClass(SingleBufferReceiver.class);
        receiver.setParallelism(PARALLELISM);

        // The partition needs to be pipelined, otherwise the original issue does not occur, because
        // the sender and receiver are not online at the same time.
        connectNewDataSetAsInput(
                receiver, sender, DistributionPattern.POINTWISE, ResultPartitionType.PIPELINED);

        final JobGraph jobGraph = JobGraphTestUtils.streamingJobGraph(sender, receiver);

        final SlotSharingGroup slotSharingGroup = new SlotSharingGroup();

        sender.setSlotSharingGroup(slotSharingGroup);
        receiver.setSlotSharingGroup(slotSharingGroup);

        MINI_CLUSTER_RESOURCE.getMiniCluster().executeJobBlocking(jobGraph);
    }

    // ---------------------------------------------------------------------------------------------

    /** Sends a fixed number of buffers and sleeps in-between sends. */
    public static class SlowBufferSender extends AbstractInvokable {

        public SlowBufferSender(Environment environment) {
            super(environment);
        }

        @Override
        public void invoke() throws Exception {
            final ResultPartitionWriter writer = getEnvironment().getWriter(0);

            for (int i = 0; i < 8; i++) {
                writer.emitRecord(ByteBuffer.allocate(1024), 0);
                Thread.sleep(50);
            }
        }
    }

    /** Reads a single buffer and recycles it. */
    public static class SingleBufferReceiver extends AbstractInvokable {

        public SingleBufferReceiver(Environment environment) {
            super(environment);
        }

        @Override
        public void invoke() throws Exception {
            InputGate gate = getEnvironment().getInputGate(0);
            gate.finishReadRecoveredState();
            while (!gate.getStateConsumedFuture().isDone()) {
                gate.pollNext();
            }
            gate.setChannelStateWriter(ChannelStateWriter.NO_OP);
            gate.requestPartitions();
            Buffer buffer = gate.getNext().orElseThrow(IllegalStateException::new).getBuffer();
            if (buffer != null) {
                buffer.recycleBuffer();
            }
        }
    }
}
