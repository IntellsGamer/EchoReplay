package com.echoreplay.model;

public record Rotation(float pitch, float yaw, float headYaw) {
    public static Rotation of(float pitch, float yaw, float headYaw) {
        return new Rotation(pitch, yaw, headYaw);
    }
}
