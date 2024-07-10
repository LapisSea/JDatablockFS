package com.lapissea.fuzz;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

@SuppressWarnings("unused")
public record FuzzConfig(
	boolean shouldLog, Optional<FuzzLogger> logFunct, Duration logTimeout, Duration initialLogDelay,
	Optional<String> name, int maxWorkers,
	Duration errorDelay, Optional<FailOrder> failOrder,
	OptionalInt maxErrorsTracked
){
	
	static <T> Optional<T> none()   { return Optional.empty(); }
	static <T> Optional<T> some(T t){ return Optional.of(t); }
	static OptionalInt some(int i)  { return OptionalInt.of(i); }
	
	public FuzzConfig{
		Objects.requireNonNull(logFunct);
		if(logTimeout.isNegative()) throw new IllegalArgumentException("logTimeout must be positive");
		if(errorDelay.isNegative()) throw new IllegalArgumentException("errorDelay must be positive");
		if(name.map(String::isEmpty).orElse(false)) throw new IllegalArgumentException("Name can not be empty");
		if(maxWorkers<=0) throw new IllegalArgumentException("maxWorkers must be greater than 0");
	}
	public FuzzConfig(){
		this(
			true, none(), Duration.ofMillis(900), Duration.ofSeconds(1),
			none(), Runtime.getRuntime().availableProcessors() == 1? 1 : Runtime.getRuntime().availableProcessors() + 1,
			Duration.ofSeconds(2), none(),
			some(100)
		);
	}
	
	public FuzzConfig dontLog()                                    { return new FuzzConfig(false, none(), logTimeout, initialLogDelay, name, maxWorkers, errorDelay, failOrder, maxErrorsTracked); }
	public FuzzConfig logWith(FuzzLogger logFunct)                 { return new FuzzConfig(true, some(logFunct), logTimeout, initialLogDelay, name, maxWorkers, errorDelay, failOrder, maxErrorsTracked); }
	public FuzzConfig withLogTimeout(Duration logTimeout)          { return new FuzzConfig(shouldLog, logFunct, logTimeout, initialLogDelay, name, maxWorkers, errorDelay, failOrder, maxErrorsTracked); }
	public FuzzConfig withInitialLogDelay(Duration initialLogDelay){ return new FuzzConfig(shouldLog, logFunct, logTimeout, initialLogDelay, name, maxWorkers, errorDelay, failOrder, maxErrorsTracked); }
	public FuzzConfig withName(String name)                        { return new FuzzConfig(shouldLog, logFunct, logTimeout, initialLogDelay, some(name), maxWorkers, errorDelay, failOrder, maxErrorsTracked); }
	public FuzzConfig withMaxWorkers(int maxWorkers)               { return new FuzzConfig(shouldLog, logFunct, logTimeout, initialLogDelay, name, maxWorkers, errorDelay, failOrder, maxErrorsTracked); }
	public FuzzConfig withErrorDelay(Duration errorDelay)          { return new FuzzConfig(shouldLog, logFunct, logTimeout, initialLogDelay, name, maxWorkers, errorDelay, failOrder, maxErrorsTracked); }
	public FuzzConfig withFailOrder(FailOrder failOrder)           { return new FuzzConfig(shouldLog, logFunct, logTimeout, initialLogDelay, name, maxWorkers, errorDelay, some(failOrder), maxErrorsTracked); }
	public FuzzConfig withMaxErrorsTracked(int maxCount)           { return new FuzzConfig(shouldLog, logFunct, logTimeout, initialLogDelay, name, maxWorkers, errorDelay, failOrder, some(maxCount)); }
	
}
