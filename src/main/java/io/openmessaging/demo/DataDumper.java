package io.openmessaging.demo;

import javax.xml.crypto.Data;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by will on 31/5/2017.
 */
public class DataDumper {

    static String rootPath;
    static long DATA_FILE_SZIE = 8 * 1024 * 1024 * 1024; // 6GB
    static int MINI_CHUNK_SIZE = 4 * 1024 * 1024; // 4MB
    static int MINI_CHUNK_TAIL_SIZE = 256 * 1025; // 256KB

    static RandomAccessFile dataFile;
    static FileChannel dataFileChannel;
    static DataFileIndexer dataFileIndexer = new DataFileIndexer();

    static ByteBuffer integerToByteArray = ByteBuffer.allocate(4);
    static MappedByteBuffer[] topicMappedBuff = new MappedByteBuffer[DataFileIndexer.INIT_TOPIC_NUMBER];

    static ReentrantLock[] assignMiniChunkLocks = new ReentrantLock[DataFileIndexer.INIT_TOPIC_NUMBER];
    static ReentrantLock[] topicWriteLocks = new ReentrantLock[DataFileIndexer.INIT_TOPIC_NUMBER];
    static AtomicInteger numberOfProducer = new AtomicInteger(0);
    static AtomicInteger numberOfFinished = new AtomicInteger(0);

    public DataDumper(String fileRootPath) throws IOException {
        rootPath = fileRootPath;
        dataFile = new RandomAccessFile(fileRootPath + File.separator + "data.bin", "rw");
        dataFile.setLength(DATA_FILE_SZIE);
        dataFileChannel = dataFile.getChannel();
        for (int i = 0; i < DataFileIndexer.INIT_TOPIC_NUMBER; i++) {
            topicMappedBuff[i] = null;
        }
        numberOfProducer.incrementAndGet();
    }

    public void writeToFile(String topicName, byte[] data) throws IOException {
        int topicNumber = DataFileIndexer.getAssignedTopicNumber(topicName);

        int offset = getMessageWriteOffset(topicNumber, data.length + Integer.BYTES);

        MappedByteBuffer buf = topicMappedBuff[topicNumber];

        buf.put(integerToByteArray.putInt(data.length).array());
        buf.put(data);

    }

    public int getMessageWriteOffset(int topicNumber, int dataLength) throws IOException {
        int offset;
        topicWriteLocks[topicNumber].lock();
        int currentTopicMiniChunkNumber = DataFileIndexer.topicMiniChunkNumber[topicNumber];
        int currentTopicMiniChunkLength = DataFileIndexer.topicMiniChunkLengths[topicNumber][currentTopicMiniChunkNumber];
        if ((MINI_CHUNK_SIZE - currentTopicMiniChunkLength < dataLength) || topicMappedBuff[topicNumber] == null) {
            assignNextMiniChunk(topicNumber);
        }
        currentTopicMiniChunkNumber = DataFileIndexer.topicMiniChunkNumber[topicNumber];
        currentTopicMiniChunkLength = DataFileIndexer.topicMiniChunkLengths[topicNumber][currentTopicMiniChunkNumber];
        offset = currentTopicMiniChunkLength;
        DataFileIndexer.topicMiniChunkLengths[topicNumber][currentTopicMiniChunkNumber] += dataLength;
        topicWriteLocks[topicNumber].unlock();
        return offset;
    }

    public void assignNextMiniChunk(int topicNumber) throws IOException {
        assignMiniChunkLocks[topicNumber].lock();
        int currentTopicMiniChunkNumber = DataFileIndexer.topicMiniChunkNumber[topicNumber];
        int currentTopicMiniChunkLength = DataFileIndexer.topicMiniChunkLengths[topicNumber][currentTopicMiniChunkNumber];

        if ((MINI_CHUNK_SIZE - currentTopicMiniChunkLength < MINI_CHUNK_TAIL_SIZE) || topicMappedBuff[topicNumber] == null) {
            unmap(topicMappedBuff[topicNumber]);
            DataFileIndexer.topicMiniChunkNumber[topicNumber]++;
            DataFileIndexer.topicMiniChunkLengths[topicNumber][DataFileIndexer.topicMiniChunkNumber[topicNumber]] = 0;
            long miniChunkGlobalOffset = DataFileIndexer.topicOffsets[topicNumber] + MINI_CHUNK_SIZE * DataFileIndexer.topicMiniChunkNumber[topicNumber];
            topicMappedBuff[topicNumber] = dataFileChannel.map(FileChannel.MapMode.READ_WRITE, miniChunkGlobalOffset, MINI_CHUNK_SIZE);

            //load into physical memory
            //topicMappedBuff[topicNumber].load();
        }
        assignMiniChunkLocks[topicNumber].unlock();

    }

    static void unmap(MappedByteBuffer mbb) {
        try {
            Method cleaner = mbb.getClass().getMethod("cleaner");
            cleaner.setAccessible(true);
            Method clean = Class.forName("sun.misc.Cleaner").getMethod("clean");
            clean.invoke(cleaner.invoke(mbb));
            //System.out.println("unmap successful");
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    public void close() throws IOException {
        for (int i = 0; i < DataFileIndexer.INIT_TOPIC_NUMBER; i++) {
            if (topicMappedBuff[i] != null) {
                topicMappedBuff[i].force();
            }
        }
        dataFile.close();
        int finished = numberOfFinished.incrementAndGet();
        //the last producer write out the DataIndex object
        if (finished == numberOfProducer.get()) {
            ObjectOutputStream oos = new ObjectOutputStream(
                    new FileOutputStream(new File(rootPath + File.separator + "index.bin")));
            oos.writeObject(dataFileIndexer);
            // close the writing.
            oos.close();
        }

    }
}

class DataFileIndexer implements Serializable {
    public static int INIT_TOPIC_NUMBER = 100;
    public static int MAX_MINI_CHUNK_NUMBER_PER_TOPIC = 10;
    static int TOPIC_CHUNK_SIZE = 80 * 1024 * 1024; // 80 MB

    public static int topicNumber = 0;
    public static ConcurrentHashMap<String, Integer> topicNameToNumber = new ConcurrentHashMap<>(INIT_TOPIC_NUMBER);
    public static long[] topicOffsets = new long[INIT_TOPIC_NUMBER];
    public static int[] topicMiniChunkNumber = new int[INIT_TOPIC_NUMBER];
    public static int[][] topicMiniChunkLengths = new int[INIT_TOPIC_NUMBER][];

    public static long currentGlobalDataOffset = 0;
    public static int currentTopicNumber = 0;
    public static ReentrantLock assignLock = new ReentrantLock();

    public DataFileIndexer() {
        topicNameToNumber.clear();
        for (int i = 0; i < INIT_TOPIC_NUMBER; i++) {
            topicOffsets[i] = 0L;
            topicMiniChunkNumber[i] = 0;
            topicMiniChunkLengths[i] = new int[MAX_MINI_CHUNK_NUMBER_PER_TOPIC];
        }

    }

    static void assignNumberToTopic(String topicName) {
        assignLock.lock();
        if (!topicNameToNumber.containsKey(topicName)) {
            topicNameToNumber.put(topicName, currentTopicNumber);
            topicOffsets[currentTopicNumber] = currentGlobalDataOffset;
            currentGlobalDataOffset += TOPIC_CHUNK_SIZE;
            currentTopicNumber++;
        }
        assignLock.unlock();
    }

    static int getAssignedTopicNumber(String topicName) {
        if (!topicNameToNumber.containsKey(topicName)) {
            assignNumberToTopic(topicName);
        }
        return topicNameToNumber.get(topicName);
    }
}