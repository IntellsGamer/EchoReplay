package com.echoreplay.model;

public record Vec3d(double x, double y, double z) {
    public static Vec3d of(double x, double y, double z) {
        return new Vec3d(x, y, z);
    }
}
