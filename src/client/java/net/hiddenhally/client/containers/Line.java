package net.hiddenhally.client.containers;

import net.minecraft.world.phys.Vec3;

public record Line(Vec3 start, Vec3 end, int color) {
    public Line offset(Vec3 offset) {
        return new Line(this.start.add(offset), this.end.add(offset), this.color);
    }
}
