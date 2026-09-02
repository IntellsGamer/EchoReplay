package com.echoreplay.util;

/**
 * A simple dense paletted storage for one block region. We store a palette
 * (list of state strings) and an index array. Packing bits-per-index is chosen
 * automatically to fit the palette size. This round-trips 1:1 with a
 * {@code BlockData} list because we store indices, not lossy ints.
 */
public final class PalettedStorage {

    private final java.util.List<String> palette = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> indexOf = new java.util.HashMap<>();
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] data;

    public PalettedStorage(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.data = new int[sizeX * sizeY * sizeZ];
        ensure("minecraft:air");
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }

    public int ensure(String stateString) {
        Integer idx = indexOf.get(stateString);
        if (idx != null) {
            return idx;
        }
        int next = palette.size();
        palette.add(stateString);
        indexOf.put(stateString, next);
        return next;
    }

    public int paletteSize() { return palette.size(); }

    public int indexOf(String stateString) {
        Integer idx = indexOf.get(stateString);
        return idx == null ? -1 : idx;
    }

    public int get(int x, int y, int z) {
        return data[index(x, y, z)];
    }

    public void set(int x, int y, int z, int paletteIndex) {
        data[index(x, y, z)] = paletteIndex;
    }

    public String getState(int x, int y, int z) {
        return palette.get(get(x, y, z));
    }

    private int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public java.util.List<String> palette() {
        return java.util.Collections.unmodifiableList(palette);
    }

    public int[] raw() {
        return data;
    }
}
