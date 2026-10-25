package com.example.client.rl;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** สถานะย่อสำหรับเรียนรู้การต่อสู้ โดยเก็บเฉพาะข้อมูลที่มีผลต่อการหลบและจังหวะโจมตี */
public record CombatState(String enemyType, int distanceBand, int playerHealthBand,
		int enemyHealthBand, int attackStrengthBand, boolean onGround) {

	public static CombatState observe(Player player, LivingEntity enemy) {
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()).getPath();
		return new CombatState(type, band((float) player.distanceTo(enemy), 2.5F, 4.0F, 7.0F),
				band(player.getHealth(), 6.0F, 12.0F, 16.0F),
				band(enemy.getHealth(), 4.0F, 10.0F, 20.0F),
				band(player.getAttackStrengthScale(0.5F), 0.3F, 0.6F, 0.9F), player.onGround());
	}

	public String key() {
		String raw = enemyType + "\n" + distanceBand + "\n" + playerHealthBand + "\n"
				+ enemyHealthBand + "\n" + attackStrengthBand + "\n" + onGround;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static int band(float value, float first, float second, float third) {
		if (value <= first) return 0;
		if (value <= second) return 1;
		if (value <= third) return 2;
		return 3;
	}
}
