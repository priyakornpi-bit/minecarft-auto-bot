package com.example.client.container;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * [Javadoc] คลาสจัดการและค้นหา container ใกล้ผู้เล่น 
 * ปรับปรุงด้วย Optional<BlockPos> เพื่อความปลอดภัยจาก NullPointerException (NPE)
 */
public final class ContainerManager {

    // ป้องกันการสร้าง instance ของคลาสนี้ (Utility Class)
    private ContainerManager() {
    }

    /**
     * ค้นหากล่องเก็บของที่ใกล้ตัวผู้เล่นมากที่สุดในรัศมีที่กำหนด
     * 
     * @param level  สภาวะโลกในเกมฝั่ง Client
     * @param origin ตำแหน่งเริ่มต้นของผู้เล่น
     * @param radius รัศมีค้นหา (บล็อก)
     * @return Optional<BlockPos> คืนค่าพิกัดกล่องที่ใกล้ที่สุด หรือ Optional.empty() หากไม่พบกล่อง
     */
    public static Optional<BlockPos> findNearestContainer(ClientLevel level, BlockPos origin, int radius) {
        // [1] Guard Clause: ตรวจสอบความพร้อมของสภาวะเกม หากข้อมูลไม่ครบให้คืนค่าว่างเปล่าทันที
        if (level == null || origin == null || radius <= 0) {
            return Optional.empty();
        }

        BlockPos nearest = null;
        double bestDistance = Double.MAX_VALUE;

        // [2] วนลูปสแกนพิกัดบล็อกรอบตัวผู้เล่นตามช่วงรัศมี X, Y, Z
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -Math.min(radius, 6), -radius),
                origin.offset(radius, Math.min(radius, 6), radius))) {
            
            BlockState state = level.getBlockState(pos);
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

            // [3] ข้ามการทำงานหากบล็อกนั้นไม่ใช่ container
            if (!isContainer(path)) {
                continue;
            }

            // [4] คำนวณระยะทางยกกำลังสองเพื่อหาพิกัดที่ใกล้ที่สุด
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                // ตรึงค่าพิกัดไว้เป็น immutable เพื่อป้องกันค่าเพี้ยน
                nearest = pos.immutable();
            }
        }

        // [5] ห่อหุ้มผลลัพธ์ด้วย Optional.ofNullable แทนการส่งกลับ null ตรงๆ
        return Optional.ofNullable(nearest);
    }

    /**
     * ตรวจสอบว่าชื่อ Path ของบล็อกเป็นประเภท Container หรือไม่
     */
    private static boolean isContainer(String path) {
        if (path == null) return false;
        
        return path.equals("chest") || path.equals("trapped_chest")
                || path.equals("barrel") || path.endsWith("_shulker_box");
    }
}