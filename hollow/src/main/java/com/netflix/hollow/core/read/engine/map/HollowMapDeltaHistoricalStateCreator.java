/*
 *  Copyright 2016-2019 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.core.read.engine.map;

import static com.netflix.hollow.core.HollowConstants.ORDINAL_NONE;

import com.netflix.hollow.core.memory.encoding.FixedLengthElementArray;
import com.netflix.hollow.core.memory.encoding.HashCodes;
import com.netflix.hollow.core.memory.pool.WastefulRecycler;
import com.netflix.hollow.core.read.engine.PopulatedOrdinalListener;
import com.netflix.hollow.core.util.IntMap;
import com.netflix.hollow.core.util.RemovedOrdinalIterator;

/**
 * This class contains the logic for extracting the removed records from a MAP type state
 * to produce a historical type state.
 * 
 * Not intended for external consumption.
 */
public class HollowMapDeltaHistoricalStateCreator {

    private final HollowMapTypeDataElements historicalDataElements;

    private final int shardNumberMask;
    private final int shardOrdinalShift;

    private HollowMapTypeReadState typeState;
    private HollowMapTypeDataElements stateEngineDataElements[];
    private RemovedOrdinalIterator iter;
    private IntMap ordinalMapping;
    private int nextOrdinal;
    private long nextStartBucket;

    public HollowMapDeltaHistoricalStateCreator(HollowMapTypeReadState typeState, boolean reverse) {
        this.typeState = typeState;
        this.stateEngineDataElements = typeState.currentDataElements();
        this.historicalDataElements = new HollowMapTypeDataElements(WastefulRecycler.DEFAULT_INSTANCE);
        this.iter = new RemovedOrdinalIterator(typeState.getListener(PopulatedOrdinalListener.class), reverse);
        this.shardNumberMask = stateEngineDataElements.length - 1;
        this.shardOrdinalShift = 31 - Integer.numberOfLeadingZeros(stateEngineDataElements.length);
    }

    public void populateHistory() {
        populateStats();

        historicalDataElements.mapPointerAndSizeData = new FixedLengthElementArray(historicalDataElements.memoryRecycler, ((long)historicalDataElements.maxOrdinal + 1) * historicalDataElements.bitsPerFixedLengthMapPortion);
        historicalDataElements.entryData = new FixedLengthElementArray(historicalDataElements.memoryRecycler, historicalDataElements.totalNumberOfBuckets * historicalDataElements.bitsPerMapEntry);

        iter.reset();

        int ordinal = iter.next();
        while(ordinal != ORDINAL_NONE) {
            ordinalMapping.put(ordinal, nextOrdinal);
            copyRecord(ordinal);

            ordinal = iter.next();
        }
    }

    public void dereferenceTypeState() {
        this.typeState = null;
        this.stateEngineDataElements = null;
        this.iter = null;
    }

    public IntMap getOrdinalMapping() {
        return ordinalMapping;
    }

    public HollowMapTypeReadState createHistoricalTypeReadState() {
        HollowMapTypeReadState historicalTypeState = new HollowMapTypeReadState(typeState.getSchema(), historicalDataElements);
        return historicalTypeState;
    }

    private void populateStats() {
        iter.reset();
        int removedEntryCount = 0;
        int maxSize = 0;
        long totalBucketCount = 0;
        int ordinal = iter.next();

        while(ordinal != ORDINAL_NONE) {
            removedEntryCount++;
            int size = typeState.size(ordinal);
            if(size > maxSize)
                maxSize = size;
            totalBucketCount += HashCodes.hashTableSize(size);
            ordinal = iter.next();
        }

        historicalDataElements.maxOrdinal = removedEntryCount - 1;
        historicalDataElements.bitsPerMapPointer = 64 - Long.numberOfLeadingZeros(totalBucketCount);
        historicalDataElements.bitsPerMapSizeValue = 64 - Long.numberOfLeadingZeros(maxSize);
        historicalDataElements.bitsPerFixedLengthMapPortion = historicalDataElements.bitsPerMapPointer + historicalDataElements.bitsPerMapSizeValue;
        for (int i=0;i<stateEngineDataElements.length;i++) {
            if (stateEngineDataElements[i].bitsPerKeyElement > historicalDataElements.bitsPerKeyElement) {
                historicalDataElements.bitsPerKeyElement = stateEngineDataElements[i].bitsPerKeyElement;
                historicalDataElements.emptyBucketKeyValue = stateEngineDataElements[i].emptyBucketKeyValue;
            }
            if (stateEngineDataElements[i].bitsPerValueElement > historicalDataElements.bitsPerValueElement) {
                historicalDataElements.bitsPerValueElement = stateEngineDataElements[i].bitsPerValueElement;
            }
            if (stateEngineDataElements[i].bitsPerMapEntry > historicalDataElements.bitsPerMapEntry) {
                historicalDataElements.bitsPerMapEntry = stateEngineDataElements[i].bitsPerMapEntry;
            }
        }
        historicalDataElements.totalNumberOfBuckets = totalBucketCount;

        ordinalMapping = new IntMap(removedEntryCount);
    }

    private void copyRecord(int ordinal) {
        int shard = ordinal & shardNumberMask;
        int shardOrdinal = ordinal >> shardOrdinalShift; 

        long bitsPerBucket = historicalDataElements.bitsPerMapEntry;
        long size = typeState.size(ordinal);

        long fromStartBucket = stateEngineDataElements[shard].getStartBucket(shardOrdinal);
        long fromEndBucket = stateEngineDataElements[shard].getEndBucket(shardOrdinal);
        long numBuckets = fromEndBucket - fromStartBucket;

        historicalDataElements.mapPointerAndSizeData.setElementValue((long)nextOrdinal * historicalDataElements.bitsPerFixedLengthMapPortion, historicalDataElements.bitsPerMapPointer, nextStartBucket + numBuckets);
        historicalDataElements.mapPointerAndSizeData.setElementValue(((long)nextOrdinal * historicalDataElements.bitsPerFixedLengthMapPortion) + historicalDataElements.bitsPerMapPointer, historicalDataElements.bitsPerMapSizeValue, size);

        historicalDataElements.entryData.copyBits(stateEngineDataElements[shard].entryData, fromStartBucket * bitsPerBucket, nextStartBucket * bitsPerBucket, numBuckets * bitsPerBucket);

        nextOrdinal++;
        nextStartBucket += numBuckets;
    }

}
