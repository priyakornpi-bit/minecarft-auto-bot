package com.example.client.state;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import com.example.client.planning.InstantCraftingHelper;

/**
 * ตัวควบคุม Finite State Machine (FSM) หลักของ Minecraft Auto Bot
 * เชื่อมโยง Game Tick Loop เข้ากับ State: IDLE, GATHERING, CRAFTING, CLEANUP_INVENTORY, RECOVERY
 */
public final class BotStateMachine {

    private static BotState currentState = BotState.IDLE;
    private static int stateTimeoutTicks = 0;
    private static final int MAX_STATE_TIMEOUT_TICKS = 100; // 5 วินาที (100 ticks)

    // สวิตช์เปิด/ปิดการทำงานของ State Machine
    private static boolean active = false;

    private BotStateMachine() {
    }

    /**
     * ลงทะเบียนเข้ากับ Client Tick Event ของ Fabric เพื่อให้ทำงานทุกๆ Frame/Tick ของเกม
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BotStateMachine::onClientTick);
    }

    /**
     * เมธอดที่ถูกเรียก 20 ครั้งต่อวินาที (ทุก 50ms)
     */
    public static void onClientTick(Minecraft client) {
        if (!active) {
            return;
        }

        // [1] Guard Clause: ตรวจสอบความพร้อมของสภาวะแวดล้อมและผู้เล่น
        if (client.player == null || client.level == null || client.gameMode == null) {
            return;
        }

        // [2] ตรวจสอบการเสียชีวิตของผู้เล่น หากตายให้เข้าสู่โหมด RECOVERY ทันที
        if (!client.player.isAlive()) {
            if (currentState != BotState.RECOVERY) {
                transitionTo(BotState.RECOVERY);
            }
        }

        // [3] Safety Watchdog: ป้องกันไม่ให้บอทค้างในสถานะเดิมนานเกินไป
        if (currentState != BotState.IDLE && ++stateTimeoutTicks > MAX_STATE_TIMEOUT_TICKS) {
            System.err.println("⚠️ [State Timeout] บอทค้างในสถานะ " + currentState + " นานเกินไป! กำลังทำการ Force Reset");
            forceReset(client);
            return;
        }

        // [4] State Execution พร้อมระบบกู้คืนข้อผิดพลาด (Error Handling & Reset)
        try {
            switch (currentState) {
                case IDLE -> handleIdleState(client);
                case GATHERING -> handleGatheringState(client);
                case CRAFTING -> handleCraftingState(client);
                case CLEANUP_INVENTORY -> handleCleanupInventoryState(client);
                case RECOVERY -> handleRecoveryState(client);
            }
        } catch (Exception exception) {
            System.err.println("🚨 [Exception Caught] เกิดข้อผิดพลาดขณะทำงานในสถานะ " + currentState + ": " + exception.getMessage());
            forceReset(client);
        }
    }

    // =========================================================================
    // Handlers สำหรับแต่ละสถานะ
    // =========================================================================

    /**
     * State IDLE: ประเมินสถานการณ์เพื่อตัดสินใจว่าจะทำอะไรต่อ
     */
    private static void handleIdleState(Minecraft client) {
        // 1. ตรวจสอบว่ากระเป๋าเต็มและมีขยะที่ควรทิ้งหรือไม่
        if (isInventoryFull(client) && hasJunkItems(client)) {
            transitionTo(BotState.CLEANUP_INVENTORY);
            return;
        }

        // 2. หากเปิดหน้าต่าง Crafting Table ค้างอยู่ ให้เข้าสู่โหมด CRAFTING
        if (client.player.containerMenu instanceof CraftingMenu) {
            transitionTo(BotState.CRAFTING);
            return;
        }

        // 3. สถานะปกติ เข้าสู่การเก็บเกี่ยวทรัพยากร
        transitionTo(BotState.GATHERING);
    }

    /**
     * State GATHERING: สั่งการค้นหาเส้นทางและการเก็บเกี่ยว
     */
    private static void handleGatheringState(Minecraft client) {
        // หากกระเป๋าเต็ม ให้ตัดเข้าสู่โหมดเคลียร์กระเป๋า
        if (isInventoryFull(client) && hasJunkItems(client)) {
            stopInputs(client);
            transitionTo(BotState.CLEANUP_INVENTORY);
            return;
        }

        // หากมีการเปิดหน้าต่างโต๊ะคราฟต์ ให้ตัดเข้าโหมด CRAFTING
        if (client.player.containerMenu instanceof CraftingMenu) {
            stopInputs(client);
            transitionTo(BotState.CRAFTING);
        }
    }

    /**
     * State CRAFTING: ทำการคราฟต์แบบ Instant ใน Solo mode
     */
    private static void handleCraftingState(Minecraft client) {
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            // หน้าต่างคราฟต์ยังไม่เปิด หรือถูกปิดไปแล้ว ให้กลับไป IDLE
            transitionTo(BotState.IDLE);
            return;
        }

        // ตัวอย่างการคราฟต์ Pickaxe ด้วยหิน (Cobblestone) และ Sticks
        // สูตร: หิน 3 ก้อน (ช่อง 1, 2, 3) + ไม้แท่ง 2 อัน (ช่อง 5, 8)
        int[] pickaxeHeadCells = {1, 2, 3};
        int[] pickaxeHandleCells = {5, 8};

        boolean crafted = InstantCraftingHelper.craftInstant(
                client, menu,
                "cobblestone", pickaxeHeadCells,
                "stick", pickaxeHandleCells
        );

        if (!crafted) {
            // หากไม่มีหิน ให้ลองคราฟต์ด้วยไม้แปรรูป (Oak Planks)
            crafted = InstantCraftingHelper.craftInstant(
                    client, menu,
                    "oak_planks", pickaxeHeadCells,
                    "stick", pickaxeHandleCells
            );
        }

        if (crafted) {
            sendFeedback(client, "คราฟต์เครื่องมือสำเร็จเรียบร้อย (Instant 1-Tick)!");
        } else {
            // หากวัตถุดิบไม่พอ ให้ปิดหน้าต่างและกลับไป IDLE
            client.setScreenAndShow(null);
        }

        transitionTo(BotState.IDLE);
    }

    /**
     * State CLEANUP_INVENTORY: วนลูปตรวจสอบกระเป๋าและดรอปไอเทมขยะทิ้ง
     */
    private static void handleCleanupInventoryState(Minecraft client) {
        var menu = client.player.inventoryMenu;
        int containerId = menu.containerId;
        int itemsDropped = 0;

        // วนลูปตรวจสอบ Main Inventory และ Hotbar (ช่อง 9 ถึง 44)
        for (int i = 9; i < 45; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;

            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();

            // รายการไอเทมขยะที่ไม่จำเป็นต้องเก็บไว้
            if (isJunkItem(path)) {
                // button = 1 หมายถึง ทิ้งทั้งกอง (Drop entire stack = Ctrl+Q)
                // ContainerInput.THROW ส่งแพ็กเกจโยนไอเทมออกนอกตัว
                client.gameMode.handleContainerInput(containerId, i, 1, ContainerInput.THROW, client.player);
                itemsDropped++;
            }
        }

        if (itemsDropped > 0) {
            sendFeedback(client, "เคลียร์กระเป๋า: ทิ้งไอเทมขยะไปแล้ว " + itemsDropped + " รายการ");
        }

        transitionTo(BotState.IDLE);
    }

    /**
     * State RECOVERY: จัดการเมื่อเกิดข้อผิดพลาด ปลดล็อกทุกอย่างและคืนสถานะปลอดภัย
     */
    private static void handleRecoveryState(Minecraft client) {
        forceReset(client);

        if (client.player != null && !client.player.isAlive()) {
            client.player.respawn();
            sendFeedback(client, "เกิดใหม่และเริ่มต้นสถานะใหม่ (Respawned)");
        }

        transitionTo(BotState.IDLE);
    }

    // =========================================================================
    // Utilities & Helper Methods
    // =========================================================================

    /**
     * สลับสถานะของ State Machine
     */
    public static void transitionTo(BotState newState) {
        System.out.println("🔄 [Bot State] เปลี่ยนสถานะจาก " + currentState + " -> " + newState);
        currentState = newState;
        stateTimeoutTicks = 0;
    }

    /**
     * สั่ง Force Reset: ปิด GUI ทุกอย่าง และปล่อยปุ่มควบคุมทั้งหมด
     */
    public static void forceReset(Minecraft client) {
        client.setScreenAndShow(null);
        stopInputs(client);
        currentState = BotState.IDLE;
        stateTimeoutTicks = 0;
    }

    /**
     * ปล่อยปุ่มเดิน กระโดด โจมตี และใช้งานไอเทมทั้งหมด
     */
    public static void stopInputs(Minecraft client) {
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
    }

    /**
     * ตรวจสอบว่ากระเป๋าผู้เล่นเต็มหรือไม่
     */
    public static boolean isInventoryFull(Minecraft client) {
        if (client.player == null) return false;
        var inventory = client.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * ตรวจสอบว่าในกระเป๋ามีไอเทมขยะอยู่หรือไม่
     */
    public static boolean hasJunkItems(Minecraft client) {
        if (client.player == null) return false;
        var inventory = client.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                if (isJunkItem(path)) return true;
            }
        }
        return false;
    }

    private static boolean isJunkItem(String path) {
        return path.equals("rotten_flesh")
                || path.equals("poisonous_potato")
                || path.equals("spider_eye")
                || path.equals("pufferfish");
    }

    private static void sendFeedback(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§6[Auto Bot] §f" + message));
        }
    }

    public static BotState getCurrentState() {
        return currentState;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean enable) {
        active = enable;
    }
}

