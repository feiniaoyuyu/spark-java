package com.sdu.spark.storage.memory;

import com.google.common.collect.Maps;
import com.sdu.spark.memory.MemoryManager;
import com.sdu.spark.memory.MemoryMode;
import com.sdu.spark.rpc.SparkConf;
import com.sdu.spark.serializer.SerializerManager;
import com.sdu.spark.storage.BlockId;
import com.sdu.spark.storage.BlockInfoManager;
import com.sdu.spark.utils.ChunkedByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.sdu.spark.utils.Utils.bytesToString;

/**
 * Block数据落地JVM内存
 *
 * todo: putIteratorAsValues、 putIteratorAsBytes、evictBlocksToFreeSpace方法尚未实现
 *
 * @author hanhan.zhang
 * */
public class MemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryStore.class);

    public SparkConf conf;
    public BlockInfoManager blockInfoManager;
    public SerializerManager serializerManager;
    public MemoryManager memoryManager;
    public BlockEvictionHandler blockEvictionHandler;

    // key = BlockId, value = 存储空间(jvm内存或直接内存)
    private Map<BlockId, MemoryEntry> entries;
    // key = taskId, value = 存储的jvm空间大小
    private Map<Long, Long> onHeapUnrollMemoryMap;
    // key = taskId, value = 存储的堆外空间大小
    private Map<Long, Long> offHeapUnrollMemoryMap;

    private long unrollMemoryThreshold;

    public MemoryStore(SparkConf conf, BlockInfoManager blockInfoManager,
                       SerializerManager serializerManager, MemoryManager memoryManager,
                       BlockEvictionHandler blockEvictionHandler) {
        this.conf = conf;
        this.blockInfoManager = blockInfoManager;
        this.serializerManager = serializerManager;
        this.memoryManager = memoryManager;
        this.blockEvictionHandler = blockEvictionHandler;

        this.entries = new LinkedHashMap<>(32, 0.75f, true);
        this.onHeapUnrollMemoryMap = Maps.newHashMap();
        this.offHeapUnrollMemoryMap = Maps.newHashMap();

        this.unrollMemoryThreshold = conf.getLong("spark.storage.unrollMemoryThreshold", 1024 * 1024);

        if (maxMemory() < unrollMemoryThreshold) {
            LOGGER.warn("Max memory {} is less than the initial memory threshold {} needed to store a block in memory. " +
                        "Please configure Spark with more memory.", bytesToString(maxMemory()), bytesToString(unrollMemoryThreshold));
        }

        LOGGER.info("MemoryStore started with capacity {}", bytesToString(maxMemory()));
    }

    private long maxMemory() {
        return memoryManager.maxOnHeapStorageMemory() + memoryManager.maxOffHeapStorageMemory();
    }

    public long memoryUsed() {
        return memoryManager.storageMemoryUsed();
    }

    public long blocksMemoryUsed() {
        synchronized (memoryManager) {
            return memoryUsed() - currentUnrollMemory();
        }
    }

    public long getSize(BlockId blockId) {
        synchronized (entries) {
            MemoryEntry entry = entries.get(blockId);
            if (entry != null) {
                return entry.size();
            }
            throw new IllegalArgumentException(String.format("%s not exist", blockId));
        }
    }

    /**
     * Return the amount of memory currently occupied for unrolling blocks across all tasks.
     */
    public long currentUnrollMemory() {
        synchronized (memoryManager) {
            long onHeapUnrollMemorySum = 0L;
            for (Map.Entry<Long, Long> entry : onHeapUnrollMemoryMap.entrySet()) {
                onHeapUnrollMemorySum += entry.getValue();
            }

            long offHeapUnrollMemorySum = 0L;
            for (Map.Entry<Long, Long> entry : offHeapUnrollMemoryMap.entrySet()) {
                offHeapUnrollMemorySum += entry.getValue();
            }

            return onHeapUnrollMemorySum + offHeapUnrollMemorySum;
        }
    }

    private boolean contains(BlockId blockId) {
        synchronized (entries) {
            return entries.containsKey(blockId);
        }
    }

    public boolean putBytes(BlockId blockId, long size, MemoryMode memoryMode, BlockToBytes blockToBytes) {
        checkArgument(contains(blockId), String.format("Block %s is already present in the MemoryStore", blockId));

        if (memoryManager.acquireStorageMemory(blockId, size, memoryMode)) {
            // 存储空间申请成功
            ChunkedByteBuffer chunkedByteBuffer = blockToBytes.toBytes();
            assert chunkedByteBuffer.size() == size;
            SerializedMemoryEntry memoryEntry = new SerializedMemoryEntry(chunkedByteBuffer, memoryMode);
            synchronized (entries) {
                entries.put(blockId, memoryEntry);
            }
            LOGGER.info("Block {} stored as bytes in memory (estimated size {}, free {})",
                    blockId, bytesToString(size), bytesToString(maxMemory() - blocksMemoryUsed()));
            return true;
        }

        return false;
    }

    public ChunkedByteBuffer getBytes(BlockId blockId) {
        MemoryEntry entry;
        synchronized (entries) {
            entry = entries.get(blockId);
        }
        if (entry == null) {
            return null;
        }
        if (entry instanceof DeserializedMemoryEntry) {
            throw new IllegalArgumentException("should only call getBytes on serialized blocks");
        }
        return ((SerializedMemoryEntry) entry).buffer;
    }

    public Iterator<?> getValues(BlockId blockId) {
        MemoryEntry entry;
        synchronized (entries) {
            entry = entries.get(blockId);
        }
        if (entry == null) {
            throw new IllegalArgumentException("should only call getValues on deserialized blocks");
        }
        DeserializedMemoryEntry<?> memoryEntry = (DeserializedMemoryEntry<?>) entry;

        return memoryEntry.array.iterator();
    }

    public boolean remove(BlockId blockId) {
        synchronized (memoryManager) {
            MemoryEntry entry;
            synchronized (entries) {
                entry = entries.remove(blockId);
            }
            if (entry != null) {
                if (entry instanceof SerializedMemoryEntry) {
                    ((SerializedMemoryEntry) entry).buffer.dispose();
                }

                memoryManager.releaseStorageMemory(entry.size(), entry.memoryMode());
                return true;
            }

            return false;
        }
    }

    public void clear() {
        synchronized (memoryManager) {
            synchronized (entries) {
                entries.clear();
            }
            onHeapUnrollMemoryMap.clear();
            offHeapUnrollMemoryMap.clear();
            memoryManager.releaseAllStorageMemory();
        }
    }


    public long evictBlocksToFreeSpace(BlockId blockId, long space, MemoryMode memoryMode) {
        throw new UnsupportedOperationException("");
    }

    public interface BlockToBytes {
        ChunkedByteBuffer toBytes();
    }
}