/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.fhuz.kafka.streams.cep.nfa;

import com.github.fhuz.kafka.streams.cep.Event;
import com.github.fhuz.kafka.streams.cep.Sequence;
import com.github.fhuz.kafka.streams.cep.nfa.buffer.impl.KVSharedVersionedBuffer;
import com.github.fhuz.kafka.streams.cep.pattern.States;
import com.github.fhuz.kafka.streams.cep.pattern.ValueStore;
import com.github.fhuz.kafka.streams.cep.pattern.StateAggregator;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Non-determinism Finite Automaton.
 *
 * @param <K>
 * @param <V>
 */
public class NFA<K, V> implements Serializable {

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(NFA.class);

    private KVSharedVersionedBuffer<K, V> sharedVersionedBuffer;

    private Queue<ComputationStage<K, V>> computationStages;

    private transient ProcessorContext context;

    private AtomicLong runs = new AtomicLong(1);

    /**
     * Creates a new {@link NFA} instance.
     */
    public NFA(ProcessorContext context, KVSharedVersionedBuffer<K, V> buffer, Collection<Stage<K, V>> stages) {
        this(context, buffer, initComputationStates(stages));
    }

    /**
     * Creates a new {@link NFA} instance.
     */
    public NFA(ProcessorContext context, KVSharedVersionedBuffer<K, V> buffer, Queue<ComputationStage<K, V>> computationStages) {
        this.context = context;
        this.sharedVersionedBuffer = buffer;
        this.computationStages = computationStages;
    }

    private static <K, V> Queue<ComputationStage<K, V>> initComputationStates(Collection<Stage<K, V>> stages) {
        Queue<ComputationStage<K, V>> q = new LinkedBlockingQueue<>();
        stages.forEach(s -> {
            if (s.isBeginState())
                q.add(new ComputationStageBuilder().setStage(s).setVersion(new DeweyVersion(1)).setSequence(1L).build());
        });
        return q;
    }

    public Queue<ComputationStage<K, V>> getComputationStages() {
        return computationStages;
    }

    /**
     * Process the message with the given key and value.
     *
     * @param key the key for the message
     * @param value the value for the message
     * @param timestamp The timestamp of the current event.
     */
    public List<Sequence<K, V>> matchPattern(K key, V value, long timestamp) {

        int numberOfStateToProcess = computationStages.size();

        List<ComputationStage<K, V>> finalStates = new LinkedList<>();
        while(numberOfStateToProcess-- > 0) {
            ComputationStage<K, V> computationStage = computationStages.poll();
            Collection<ComputationStage<K, V>> states = matchPattern(new ComputationContext<>(this.context, key, value, timestamp, computationStage));
            if( states.isEmpty() )
                removePattern(computationStage);
            else
                finalStates.addAll(getAllFinalStates(states));
            computationStages.addAll(getAllNonFinalStates(states));
        }
        return matchConstruction(finalStates);
    }

    private List<Sequence<K, V>> matchConstruction(Collection<ComputationStage<K, V>> states) {
        return  states.stream()
                .map(c -> sharedVersionedBuffer.remove(c.getStage(), c.getEvent(), c.getVersion()))
                .collect(Collectors.toList());
    }

    private void removePattern(ComputationStage<K, V> computationStage) {
        sharedVersionedBuffer.remove(
                computationStage.getStage(),
                computationStage.getEvent(),
                computationStage.getVersion()
        );
    }

    private List<ComputationStage<K, V>> getAllNonFinalStates(Collection<ComputationStage<K, V>> states) {
        return states
                .stream()
                .filter(c -> !c.isForwardingToFinalState())
                .collect(Collectors.toList());
    }

    private List<ComputationStage<K, V>> getAllFinalStates(Collection<ComputationStage<K, V>> states) {
        return states
                .stream()
                .filter(ComputationStage::isForwardingToFinalState)
                .collect(Collectors.toList());
    }

    private Collection<ComputationStage<K, V>> matchPattern(ComputationContext<K, V> ctx) {
        Collection<ComputationStage<K, V>> nextComputationStages = new ArrayList<>();

        // Checks the time window of the current state.
        if( !ctx.getComputationStage().isBeginState() && ctx.getComputationStage().isOutOfWindow(ctx.getTimestamp()) )
            return nextComputationStages;

        nextComputationStages = evaluate(ctx, ctx.getComputationStage().getStage(), null);

        // Begin state should always be re-add to allow multiple runs.
        if(ctx.getComputationStage().isBeginState() && !ctx.getComputationStage().isForwarding()) {
            DeweyVersion version = ctx.getComputationStage().getVersion();
            DeweyVersion newVersion = (nextComputationStages.isEmpty()) ? version : version.addRun();
            ComputationStageBuilder<K, V> builder = new ComputationStageBuilder<K, V>()
                    .setStage(ctx.getComputationStage().getStage())
                    .setVersion(newVersion)
                    .setSequence(runs.incrementAndGet());
            nextComputationStages.add(builder.build());
        }

        return nextComputationStages;
    }

    private Collection<ComputationStage<K, V>> evaluate(ComputationContext<K, V> ctx, Stage<K, V> currentStage, Stage<K, V> previousStage) {
        ComputationStage<K, V> computationStage = ctx.computationStage;
        final long sequenceID = computationStage.getSequence();
        final Event<K, V> previousEvent = computationStage.getEvent();
        final DeweyVersion version      = computationStage.getVersion();

        List<Stage.Edge<K, V>> matchedEdges = matchEdgesAndGet(ctx.key, ctx.value, ctx.timestamp, sequenceID, currentStage);

        Collection<ComputationStage<K, V>> nextComputationStages = new ArrayList<>();
        final boolean isBranching = isBranching(matchedEdges);
        Event<K, V> currentEvent = ctx.getEvent();

        long startTime = ctx.getFirstPatternTimestamp();
        boolean consumed = false;
        boolean ignored  = false;

        for(Stage.Edge<K, V> e : matchedEdges) {
            Stage<K, V> epsilonStage = Stage.newEpsilonState(currentStage, e.getTarget());
            LOG.debug("[{}]{} - {}", sequenceID, e.getOperation(), currentEvent);
            switch (e.getOperation()) {
                case PROCEED:
                    ComputationContext<K, V> nextContext = ctx;
                    // Checks whether epsilon operation is forwarding to a new stage and doesn't result from new branch.
                    if ( ! e.getTarget().equals(currentStage) && !ctx.computationStage.isBranching() ) {
                        ComputationStage<K, V> newStage = ctx.computationStage.setVersion(ctx.computationStage.getVersion().addStage());
                        nextContext = new ComputationContext<>(this.context, ctx.key, ctx.value, ctx.timestamp, newStage);
                    }
                    nextComputationStages.addAll(evaluate(nextContext, e.getTarget(), currentStage));
                    break;
                case TAKE :
                    // The event has matched the current event and not the next one.
                    if( ! isBranching ) {
                        // Re-add the current stage to NFA
                        ComputationStageBuilder<K, V> builder = new ComputationStageBuilder<K, V>()
                                .setStage(Stage.newEpsilonState(currentStage, currentStage))
                                .setVersion(version)
                                .setEvent(currentEvent)
                                .setTimestamp(startTime)
                                .setSequence(sequenceID);
                        nextComputationStages.add(builder.build());
                        // Add the current event to buffer using current version path.
                        putToSharedBuffer(currentStage, previousStage, previousEvent, currentEvent, version);
                    } else {
                        putToSharedBuffer(currentStage, previousStage, previousEvent, currentEvent, version.addRun());
                    }
                    // Else the event is handle by PROCEED edge.
                    consumed = true;
                    break;
                case BEGIN :
                    // Add the current event to buffer using current version path
                    putToSharedBuffer(currentStage, previousStage, previousEvent, currentEvent, version);
                    // Compute the next stage in NFA
                    ComputationStageBuilder<K, V> builder = new ComputationStageBuilder<K, V>()
                            .setStage(epsilonStage)
                            .setVersion(version)
                            .setEvent(currentEvent)
                            .setTimestamp(startTime)
                            .setSequence(sequenceID);
                    nextComputationStages.add(builder.build());
                    consumed = true;
                    break;
                case IGNORE:
                    // Re-add the current stage to NFA
                    if(!isBranching) nextComputationStages.add(computationStage);
                    ignored = true;
                    break;
            }
        }

        if(isBranching) {
            LOG.debug("new branch from event {}", currentEvent);
            long newSequence = runs.incrementAndGet();
            Event<K, V> latestMatchEvent = ignored ? previousEvent : currentEvent;
            ComputationStageBuilder<K, V> builder = new ComputationStageBuilder<K, V>()
                    .setStage(Stage.newEpsilonState(previousStage, currentStage))
                    .setVersion(version.addRun())
                    .setEvent(latestMatchEvent)
                    .setTimestamp(startTime)
                    .setSequence(newSequence)
                    .setBranching(true);
            nextComputationStages.add(builder.build());
            currentStage.getAggregates().forEach(agg -> newStageStateStore(agg.getName(), sequenceID).branch(newSequence));

            sharedVersionedBuffer.branch(previousStage, previousEvent, version);
        }

        if( consumed ) evaluateAggregates(currentStage.getAggregates(), sequenceID, ctx.key, ctx.value);
        return nextComputationStages;
    }

    private void putToSharedBuffer(Stage<K, V> currentStage, Stage<K, V> previousStage, Event<K, V> previousEvent, Event<K, V> currentEvent, DeweyVersion nextVersion) {
        if( previousStage != null)
            sharedVersionedBuffer.put(currentStage, currentEvent, previousStage, previousEvent, nextVersion);
        else
            sharedVersionedBuffer.put(currentStage, currentEvent, nextVersion);
    }

    @SuppressWarnings("unchecked")
    private void evaluateAggregates(List<StateAggregator<K, V, Object>> aggregates, long sequence, K key, V value) {
        aggregates.forEach(agg -> {
            ValueStore store = newStageStateStore(agg.getName(), sequence);
            store.set(agg.getAggregate().aggregate(key, value, store.get()));
        });
    }

    private List<Stage.Edge<K, V>> matchEdgesAndGet(K key, V value, long timestamp, long sequence, Stage<K, V> currentStage) {
        States store = new States(context, sequence);
        return currentStage.getEdges()
                .stream()
                .filter(e -> e.matches(key, value, timestamp, store))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private ValueStore newStageStateStore(String state, long seqId) {
        return new ValueStore(context.topic(), context.partition(), seqId, (KeyValueStore)context.getStateStore(state));
    }

    private boolean isBranching(Collection<Stage.Edge<K, V>> edges) {
        List<EdgeOperation> matchedOperations = edges
                .stream()
                .map(Stage.Edge::getOperation)
                .collect(Collectors.toList());
        return matchedOperations.containsAll(Arrays.asList(EdgeOperation.PROCEED, EdgeOperation.TAKE) )  // allowed with multiple match
                || matchedOperations.containsAll(Arrays.asList(EdgeOperation.IGNORE, EdgeOperation.TAKE) ) // allowed by skip-till-any-match
                || matchedOperations.containsAll(Arrays.asList(EdgeOperation.IGNORE, EdgeOperation.BEGIN) ) // allowed by skip-till-any-match
                || matchedOperations.containsAll(Arrays.asList(EdgeOperation.IGNORE, EdgeOperation.PROCEED) ); //allowed by skip-till-next-match or skip-till-any-match
    }

    /**
     * Class to wrap all data required to evaluate a state against an event.
     */
    private static class ComputationContext<K, V> {
        /**
         * The current processor context.
         */
        private final ProcessorContext context;
        /**
         * The current event key.
         */
        private final K key;
        /**
         * The current event value.
         */
        private final V value;
        /**
         * The current event timestamp.
         */
        private final long timestamp;
        /**
         * The current state to compute.
         */
        private final ComputationStage<K, V> computationStage;

        /**
         * Creates a new {@link ComputationContext} instance.
         */
        private ComputationContext(ProcessorContext context, K key, V value, long timestamp, ComputationStage<K, V> computationStage) {
            this.context = context;
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
            this.computationStage = computationStage;
        }

        public ProcessorContext getContext() {
            return context;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public ComputationStage<K, V> getComputationStage() {
            return computationStage;
        }

        public long getFirstPatternTimestamp() {
            return computationStage.isBeginState() ? timestamp : computationStage.getTimestamp();
        }

        public Event<K, V> getEvent( ) {
            return new Event<>(key, value, context.timestamp(), context.topic(), context.partition(), context.offset());
        }
    }
}