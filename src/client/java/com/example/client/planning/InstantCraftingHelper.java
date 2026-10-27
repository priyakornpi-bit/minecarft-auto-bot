package com.example.client.planning;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * เครื่องมือช่วยคราฟต์ไอเทมแบบ Instant (เสร็จสิ้นใน 1 Tick) สำหรับโหมด Singleplayer (Solo)
 * เนื่องจากทำงานบน Integrated Server (Ping = 0ms และไม่มี Anti-Cheat)
 * จึงสามารถส่งคำสั่งจัดวางไอเทมทุกช่องลงใน Crafting Grid 3x3 ได้ทันทีโดยไม่ต้องหน่วงเวลา
 */
public final class InstantCraftingHelper {

    private InstantCraftingHelper() {
    }

    /**
     * คราฟต์ไอเทมลงใน Crafting Table (3x3 Grid) ให้เสร็จสิ้นภายใน Tick เดียว
     *
     * @param client       Minecraft instance
     * @param menu         CraftingMenu ของโต๊ะคราฟต์ที่กำลังเปิดอยู่
     * @param materialPath ชื่อ registry path ของวัตถุดิบหลัก (เช่น "oak_planks", "cobblestone", "iron_ingot")
     * @param materialCells ตำแหน่งใน 3x3 Grid ที่ต้องการวางวัตถุดิบหลัก (ช่อง 1 ถึง 9)
     * @param handlePath   ชื่อ registry path ของด้ามจับ (เช่น "stick")
     * @param handleCells  ตำแหน่งใน 3x3 Grid ที่ต้องการวางด้ามจับ (เช่น ช่อง 5 และ 8 สำหรับ Pickaxe/Axe)
     * @return true หากคราฟต์สำเร็จและได้รับไอเทมผลลัพธ์
     */
    public static boolean craftInstant(Minecraft client, CraftingMenu menu,
                                       String materialPath, int[] materialCells,
                                       String handlePath, int[] handleCells) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }

        var player = client.player;
        var gameMode = client.gameMode;
        int containerId = menu.containerId;

        // 1. ค้นหาช่องใน Inventory ที่มีวัตถุดิบเพียงพอ
        int matInvSlot = findItemSlot(menu, materialPath, materialCells.length);
        int handleInvSlot = (handleCells != null && handleCells.length > 0)
                ? findItemSlot(menu, handlePath, handleCells.length)
                : -1;

        if (matInvSlot == -1 || (handleCells != null && handleCells.length > 0 && handleInvSlot == -1)) {
            return false; // วัตถุดิบไม่เพียงพอ
        }

        // =========================================================================
        // 2. จัดวางวัตถุดิบหลักลงใน Crafting Grid
        // =========================================================================
        // [2.1] Left-Click (ปุ่ม 0) หยิบวัตถุดิบทั้งกองขึ้นมาถือไว้บนเมาส์
        gameMode.handleContainerInput(containerId, matInvSlot, 0, ContainerInput.PICKUP, player);

        // [2.2] Right-Click (ปุ่ม 1) หยอดวัตถุดิบทีละ 1 ชิ้นลงในช่องเป้าหมายของตาราง 3x3
        for (int cellIndex : materialCells) {
            gameMode.handleContainerInput(containerId, cellIndex, 1, ContainerInput.PICKUP, player);
        }

        // [2.3] Left-Click (ปุ่ม 0) วางวัตถุดิบส่วนเกินที่เหลือบนมือกลับคืนช่องเดิม (ป้องกันของหล่น)
        gameMode.handleContainerInput(containerId, matInvSlot, 0, ContainerInput.PICKUP, player);

        // =========================================================================
        // 3. จัดวางด้ามจับ (Sticks) ถ้าสูตรนั้นต้องใช้
        // =========================================================================
        if (handleCells != null && handleCells.length > 0 && handleInvSlot != -1) {
            // [3.1] Left-Click หยิบ Sticks
            gameMode.handleContainerInput(containerId, handleInvSlot, 0, ContainerInput.PICKUP, player);

            // [3.2] Right-Click หยอด Sticks ทีละชิ้นลงในช่อง Grid
            for (int cellIndex : handleCells) {
                gameMode.handleContainerInput(containerId, cellIndex, 1, ContainerInput.PICKUP, player);
            }

            // [3.3] Left-Click นำ Sticks ที่เหลือกลับคืนช่องเดิม
            gameMode.handleContainerInput(containerId, handleInvSlot, 0, ContainerInput.PICKUP, player);
        }

        // =========================================================================
        // 4. ดึงไอเทมผลลัพธ์จาก Result Slot (ช่อง Index 0)
        // =========================================================================
        Slot resultSlot = menu.getSlot(0);
        boolean success = false;
        if (resultSlot.hasItem()) {
            // ใช้ QUICK_MOVE (Shift-Click) ย้ายผลลัพธ์เข้าสู่ Hotbar/Inventory ทันที
            gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
            success = true;
        }

        // 5. ปิดหน้าต่าง Crafting Table ทันทีอย่างปลอดภัย (เพราะเคลียร์ไอเทมบนเคอร์เซอร์เรียบร้อยแล้ว)
        client.setScreenAndShow(null);
        return success;
    }

    /**
     * ค้นหา Slot Index ใน Inventory (ช่อง 10 ถึง 45) ที่มีไอเทมตรงตามที่ต้องการและมีจำนวนเพียงพอ
     */
    private static int findItemSlot(CraftingMenu menu, String itemPath, int minCount) {
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (path.equals(itemPath) && stack.getCount() >= minCount) {
                return i;
            }
        }
        return -1;
    }
}

