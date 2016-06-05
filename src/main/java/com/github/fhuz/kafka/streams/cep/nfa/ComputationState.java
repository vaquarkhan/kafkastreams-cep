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
import com.github.fhuz.kafka.streams.cep.State;

/**
 * Implementation based on https://people.cs.umass.edu/~yanlei/publications/sase-sigmod08.pdf
 *
 * @param <K> the type of the key event.
 * @param <V> the type of the value event.
 */
public class ComputationState<K, V> {

    private State<K , V> state;

    /**
     * The pointer to the most recent event into the share buffer.
     */
    private Event<K, V> event;

    /**
     * Timestamp of the first event for this pattern.
     */
    private long timestamp;

    /**
     * The version number.
     */
    private DeweyVersion version;

    public ComputationState(State<K, V> state, DeweyVersion version) {
        this(state, version, null, -1);
    }

    public ComputationState(State<K, V> state, DeweyVersion version, Event<K, V> event, long timestamp) {
        this.state = state;
        this.event = event;
        this.timestamp = timestamp;
        this.version = version;
    }

    public boolean isOutOfWindow(long time) {
        return (time - timestamp) > state.getWindowMs();
    }

    public State<K, V> getState() {
        return state;
    }

    public Event<K, V> getEvent() {
        return event;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public DeweyVersion getVersion() {
        return version;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ComputationState{");
        sb.append("state=").append(state);
        sb.append(", event=").append(event);
        sb.append(", timestamp=").append(timestamp);
        sb.append(", version=").append(version);
        sb.append('}');
        return sb.toString();
    }
}