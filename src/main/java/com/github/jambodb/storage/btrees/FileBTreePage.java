package com.github.jambodb.storage.btrees;

import com.github.jambodb.storage.blocks.BlockStorage;
import com.github.jambodb.storage.blocks.FileBlockStorage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class FileBTreePage<K, V> implements BTreePage<K, V> {
    private static final int HEADER_BLOCK_SIZE = 4;
    private static final int BLOCK_SIZE = 8 * 1024; // todo

    private final Serializer<K> keySerializer;
    private final Serializer<V> valueSerializer;
    private final int id;
    private final boolean leaf;
    private final int maxDegree;
    private final Object[] keys;
    private final Object[] values;
    private final int[] children;

    private int size;

    public FileBTreePage(int id, boolean leaf, int maxDegree, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        this.id = id;
        this.leaf = leaf;
        this.maxDegree = maxDegree;
        keys = new Object[maxDegree + 1];
        values = new Object[maxDegree + 1];
        children = new int[maxDegree + 2];
        Arrays.fill(children, -1);
        size = 0;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
    }

    public FileBTreePage(int id, File dir, Serializer<K> keySerializer, Serializer<V> valueSerializer) throws IOException {
        this.id = id;
        int[] blocks = readHeader(dir);
        this.leaf = blocks[1] == 1;
        this.maxDegree = blocks[2];
        keys = new Object[maxDegree + 1];
        values = new Object[maxDegree + 1];
        children = new int[maxDegree + 2];
        Arrays.fill(children, -1);
        size = blocks[3];
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        //noinspection unchecked
        readObjects(dir, "k", keys, (Serializer<Object>) keySerializer);
        //noinspection unchecked
        readObjects(dir, "v", values, (Serializer<Object>) valueSerializer);
        readChildren(dir);
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void size(int size) {
        this.size = size;
        for (int i = size; i <= maxDegree; i++) {
            children[i + 1] = -1;
            keys[i] = null;
            values[i] = null;
        }
    }

    @Override
    public boolean isLeaf() {
        return leaf;
    }

    @Override
    public boolean isFull() {
        return this.size > maxDegree;
    }

    @Override
    public boolean isHalf() {
        return this.size < (maxDegree / 2) - 1;
    }

    @Override
    public boolean canBorrow() {
        return this.size >= (maxDegree / 2);
    }

    @Override
    public K key(int index) {
        if (index < keys.length) {
            //noinspection unchecked
            return (K) keys[index];
        }
        return null;
    }

    @Override
    public void key(int index, K key) {
        if (index < keys.length) {
            keys[index] = key;
        }
    }

    @Override
    public V value(int index) {
        if (index < values.length) {
            //noinspection unchecked
            return (V) values[index];
        }
        return null;
    }

    @Override
    public void value(int index, V value) {
        if (index < values.length) {
            values[index] = value;
        }
    }

    @Override
    public int child(int index) {
        if (index < children.length) {
            return children[index];
        }
        return -1;
    }

    @Override
    public void child(int index, int child) {
        if (index < children.length) {
            children[index] = child;
        }
    }

    public void fsync(File dir) throws IOException {
        if (dir == null || !dir.isDirectory() || !dir.canWrite()) {
            throw new IOException("Invalid directory");
        }
        writeHeader(dir);
        //noinspection unchecked
        writeObjects(dir, "k", keys, (Serializer<Object>) keySerializer);
        //noinspection unchecked
        writeObjects(dir, "v", values, (Serializer<Object>) valueSerializer);
        writeChildren(dir);
    }

    private int[] readHeader(File dir) throws IOException {
        File file = getFile(dir, "h", false);
        BlockStorage blockStorage = new FileBlockStorage(new RandomAccessFile(file, "r"));
        int[] blocks = new int[4];
        for (int i = 0; i < blocks.length; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_BLOCK_SIZE);
            blockStorage.read(i, buffer);
            blocks[i] = buffer.getInt();
        }
        return blocks;
    }

    private void writeHeader(File dir) throws IOException {
        File file = getFile(dir, "h", true);
        int[] blocks = new int[4];
        blocks[0] = id;
        blocks[1] = leaf ? 1 : 0;
        blocks[2] = maxDegree;
        blocks[3] = size;
        BlockStorage blockStorage = new FileBlockStorage(HEADER_BLOCK_SIZE, new RandomAccessFile(file, "rw"));
        for (int block : blocks) {
            int index = blockStorage.createBlock();
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_BLOCK_SIZE);
            buffer.putInt(block);
            blockStorage.write(index, buffer);
        }
    }

    private void readObjects(File dir, String prefix, Object[] array, Serializer<Object> serializer) throws IOException {
        File file = getFile(dir, prefix, false);
        BlockStorage blockStorage = new FileBlockStorage(BLOCK_SIZE, new RandomAccessFile(file, "r"));
        for (int i = 0; i < size; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(BLOCK_SIZE);
            blockStorage.read(i, buffer);
            Object object = serializer.read(buffer);
            array[i] = object;
        }
    }

    private void writeObjects(File dir, String prefix, Object[] array, Serializer<Object> serializer) throws IOException {
        File file = getFile(dir, prefix, true);
        BlockStorage blockStorage = new FileBlockStorage(BLOCK_SIZE, new RandomAccessFile(file, "rw"));
        for (int i = 0; i < size; i++) {
            int index = blockStorage.createBlock();
            ByteBuffer buffer = ByteBuffer.allocate(BLOCK_SIZE);
            serializer.write(array[i], buffer);
            blockStorage.write(index, buffer);
        }
    }

    private void readChildren(File dir) throws IOException {
        File file = getFile(dir, "c", false);
        BlockStorage blockStorage = new FileBlockStorage(HEADER_BLOCK_SIZE, new RandomAccessFile(file, "r"));
        for (int i = 0; i <= size; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_BLOCK_SIZE);
            blockStorage.read(i, buffer);
            children[i] = buffer.getInt();
        }
    }

    private void writeChildren(File dir) throws IOException {
        File file = getFile(dir, "c", true);
        BlockStorage blockStorage = new FileBlockStorage(HEADER_BLOCK_SIZE, new RandomAccessFile(file, "rw"));
        for (int i = 0; i <= size; i++) {
            int index = blockStorage.createBlock();
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_BLOCK_SIZE);
            buffer.putInt(children[i]);
            blockStorage.write(index, buffer);
        }
    }

    private File getFile(File dir, String prefix, boolean create) throws IOException {
        File file = new File(dir, String.format("%s-%d.jmb.dat", prefix, id));
        if (create && file.exists() && !file.delete()) {
            throw new IOException("Could not write file " + file.getAbsolutePath());
        }
        if (create && !file.exists() && !file.createNewFile()) {
            throw new IOException("Could not write file " + file.getAbsolutePath());
        }
        if (!create && !file.exists()) {
            throw new IOException("Could not read file " + file.getAbsolutePath());
        }
        return file;
    }
}
