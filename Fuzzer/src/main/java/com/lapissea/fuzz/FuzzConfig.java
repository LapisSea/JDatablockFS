package com.lapissea.fuzz;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public record FuzzConfig(
	boolean shouldLog, Optional<Consumer<LogState>> logFunct, Duration logTimeout,
	Optional<String> name, int maxWorkers,
	Duration errorDelay, Optional<FailOrder> failOrder
){
	public record LogState(
		float progress,
		Duration estimatedTotalTime, Duration estimatedTimeRemaining,
		Duration elapsed, Duration durationPerOp,
		boolean hasFail
	){
		public static String stdTime(Duration tim){
			if(tim == null) return "-:--:--";
			return String.format("%d:%02d:%02d",
			                     tim.toHoursPart(),
			                     tim.toMinutesPart(),
			                     tim.toSecondsPart());
		}
	}
	public FuzzConfig{
		Objects.requireNonNull(logFunct);
		if(logTimeout.isNegative()) throw new IllegalArgumentException("logTimeout must be positive");
		if(errorDelay.isNegative()) throw new IllegalArgumentException("errorDelay must be positive");
		if(name.map(String::isEmpty).orElse(false)) throw new IllegalArgumentException("Name can not be empty");
		if(maxWorkers<=0) throw new IllegalArgumentException("maxWorkers must be greater than 0");
	}
	public FuzzConfig(){
		this(
			true, Optional.empty(), Duration.ofMillis(900),
			Optional.empty(), Math.max(1, Runtime.getRuntime().availableProcessors() + 1),
			Duration.ofSeconds(2), Optional.empty()
		);
	}
	
	public FuzzConfig dontLog()                           { return new FuzzConfig(false, Optional.empty(), logTimeout, name, maxWorkers, errorDelay, failOrder); }
	public FuzzConfig logWith(Consumer<LogState> logFunct){ return new FuzzConfig(true, Optional.of(logFunct), logTimeout, name, maxWorkers, errorDelay, failOrder); }
	public FuzzConfig withLogTimeout(Duration logTimeout) { return new FuzzConfig(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, failOrder); }
	public FuzzConfig withName(String name)               { return new FuzzConfig(shouldLog, logFunct, logTimeout, Optional.of(name), maxWorkers, errorDelay, failOrder); }
	public FuzzConfig withMaxWorkers(int maxWorkers)      { return new FuzzConfig(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, failOrder); }
	public FuzzConfig withErrorDelay(Duration errorDelay) { return new FuzzConfig(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, failOrder); }
	public FuzzConfig withFailOrder(FailOrder failOrder)  { return new FuzzConfig(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, Optional.of(failOrder)); }
}
