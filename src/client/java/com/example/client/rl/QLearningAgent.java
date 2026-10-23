package com.example.client.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ตัวแทนการเรียนรู้แบบ Q-learning ที่เก็บค่า Q ของแต่ละสถานะและการกระทำไว้ในตาราง
 * พร้อมใช้นโยบาย epsilon-greedy เพื่อสลับระหว่างการสำรวจและการเลือกการกระทำที่ดีที่สุด
 */
public final class QLearningAgent {
	// ค่า Q ใช้ประมาณผลตอบแทนระยะยาวของการเลือกการกระทำในแต่ละสถานะ
	private final Map<String, EnumMap<RlAction, Double>> qValues = new java.util.HashMap<>();
	private final Random random = new Random();
	private double learningRate = 0.10;
	private double discount = 0.95;
	private double explorationRate = 0.15;

	/**
	 * เลือกการกระทำด้วยนโยบาย epsilon-greedy:
	 * สุ่มการกระทำบางครั้งเพื่อสำรวจ และเลือกการกระทำที่มีค่า Q สูงสุดในครั้งอื่น ๆ
	 */
	public RlAction chooseAction(String state, List<RlAction> legalActions) {
		if (legalActions.isEmpty()) throw new IllegalArgumentException("At least one legal action is required");
		if (random.nextDouble() < explorationRate) {
			return legalActions.get(random.nextInt(legalActions.size()));
		}

		EnumMap<RlAction, Double> stateValues = qValues.get(state);
		double bestValue = Double.NEGATIVE_INFINITY;
		java.util.ArrayList<RlAction> bestActions = new java.util.ArrayList<>();
		for (RlAction action : legalActions) {
			double value = stateValues == null ? 0.0 : stateValues.getOrDefault(action, 0.0);
			if (value > bestValue) {
				bestValue = value;
				bestActions.clear();
				bestActions.add(action);
			} else if (Double.compare(value, bestValue) == 0) {
				bestActions.add(action);
			}
		}
		return bestActions.get(random.nextInt(bestActions.size()));
	}

	/**
	 * ปรับปรุงค่า Q จากผลตอบแทนที่ได้รับ โดยใช้สมการ Bellman
	 * และไม่คาดการณ์ค่าของสถานะถัดไปเมื่อสถานะปัจจุบันเป็นสถานะสิ้นสุด
	 */
	public void learn(String state, RlAction action, double reward, String nextState,
			Collection<RlAction> legalNextActions, boolean terminal) {
		EnumMap<RlAction, Double> stateValues = qValues.computeIfAbsent(state, ignored -> new EnumMap<>(RlAction.class));
		double oldValue = stateValues.getOrDefault(action, 0.0);
		double nextBest = 0.0;
		if (!terminal && !legalNextActions.isEmpty()) {
			EnumMap<RlAction, Double> nextValues = qValues.get(nextState);
			nextBest = Double.NEGATIVE_INFINITY;
			for (RlAction nextAction : legalNextActions) {
				nextBest = Math.max(nextBest, nextValues == null ? 0.0 : nextValues.getOrDefault(nextAction, 0.0));
			}
		}
		double target = reward + (terminal ? 0.0 : discount * nextBest);
		stateValues.put(action, oldValue + learningRate * (target - oldValue));
	}

	public void setLearningRate(double learningRate) {
		this.learningRate = clamp(learningRate, 0.0, 1.0);
	}

	public void setDiscount(double discount) {
		this.discount = clamp(discount, 0.0, 1.0);
	}

	public void setExplorationRate(double explorationRate) {
		this.explorationRate = clamp(explorationRate, 0.0, 1.0);
	}

	public int getKnownStateCount() {
		return qValues.size();
	}

	public void load(Path path) throws IOException {
		if (!Files.exists(path)) return;
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			String[] columns = line.split("\\t", -1);
			if (columns.length != 3) continue;
			try {
				String state = columns[0];
				RlAction action = RlAction.valueOf(columns[1]);
				double value = Double.parseDouble(columns[2]);
				qValues.computeIfAbsent(state, ignored -> new EnumMap<>(RlAction.class)).put(action, value);
			} catch (IllegalArgumentException ignored) {
				// ข้ามแถวที่มีข้อมูลไม่ถูกต้อง เพื่อไม่ให้ข้อมูลเสียเพียงแถวเดียวทำให้ตารางทั้งหมดใช้ไม่ได้
			}
		}
	}

	public void save(Path path) throws IOException {
		if (path.getParent() != null) Files.createDirectories(path.getParent());
		java.util.ArrayList<String> lines = new java.util.ArrayList<>();
		qValues.forEach((state, values) -> values.forEach((action, value) ->
				lines.add(state + "\t" + action.name() + "\t" + value)));
		Files.write(path, lines, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	private static double clamp(double value, double minimum, double maximum) {
		if (!Double.isFinite(value)) throw new IllegalArgumentException("Value must be finite");
		return Math.max(minimum, Math.min(maximum, value));
	}
}
