package com.lapissea.fuzz;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.lapissea.fuzz.FuzzConfig.LogState.stdTime;

public final class FuzzProgress{
	
	private final AtomicLong executedCount = new AtomicLong();
	private       int        last          = -1;
	private       Instant    lastLog       = Instant.now();
	private final Instant    start         = Instant.now();
	private       boolean    hasErr;
	private       boolean    errMark;
	
	private final FuzzConfig              config;
	private final CompletableFuture<Long> totalIterations;
	
	private final Consumer<FuzzConfig.LogState> logger;
	
	public FuzzProgress(FuzzConfig config, long totalIterations){
		this(config, CompletableFuture.completedFuture(totalIterations));
	}
	public FuzzProgress(FuzzConfig config, CompletableFuture<Long> totalIterations){
		this.config = config;
		this.totalIterations = totalIterations;
		logger = config.shouldLog()? config.logFunct().orElseGet(() -> new Consumer<>(){
			private boolean first = true;
			@Override
			public void accept(FuzzConfig.LogState state){
				if(first){
					first = false;
					config.name().ifPresent(name -> {
						System.out.println("Fuzzing of \033[0;92m" + name + "\033[0m started");
					});
				}
				TO_CONSOLE.accept(state);
			}
		}) : null;
	}
	
	synchronized void err(){
		var oldErr = hasErr;
		hasErr = errMark = true;
		if(!oldErr){
			var count = executedCount.get();
			logInc(count, calcProgressI(count));
		}
	}
	synchronized void errLater(){
		if(errMark || !config.shouldLog()) return;
		errMark = true;
		var count = executedCount.get();
		logInc(count, calcProgressI(count));
	}
	
	public boolean hasErr(){
		return hasErr;
	}
	
	void inc(){
		if(!config.shouldLog()) return;
		var count     = executedCount.incrementAndGet();
		var progressI = calcProgressI(count);
		
		if(progressI == last) return;
		
		synchronized(this){
			logInc(count, progressI);
		}
	}
	
	private int calcProgressI(long count){
		return (int)((count*1000)/totalIterations.getNow(Long.MAX_VALUE));
	}
	
	private void logInc(long count, int progressI){
		var now = Instant.now();
		if(Duration.between(lastLog, now).compareTo(config.logTimeout())>0){
			lastLog = now;
			last = progressI;
			
			BigDecimal progress;
			if(totalIterations.isDone()){
				progress = BigDecimal.valueOf(count).divide(BigDecimal.valueOf(totalIterations.join()), MathContext.DECIMAL128);
			}else progress = BigDecimal.ZERO;
			log(count, progress);
		}
	}
	
	private static final Consumer<FuzzConfig.LogState> TO_CONSOLE = state -> {
		final String red   = "\033[0;91m";
		final String green = "\033[0;92m";
		
		final String gray  = "\033[0;90m";
		final String reset = "\033[0m";
		
		var f = String.format("%.1f", state.progress()*100);
		if(f.equals("100.0")) f = "Done";
		
		var    perOpO = state.nanosecondsPerOp();
		String unit;
		String value;
		if(perOpO.isEmpty()){
			unit = "ms";
			value = "--";
		}else{
			var perOp = perOpO.getAsDouble();
			if(perOp>1000_000_000){
				unit = " S";
				value = String.format("~%.4f", perOp/1000_000_000D);
			}else if(perOp>1000){
				unit = "ms";
				value = String.format("~%.4f", perOp/1_000_000D);
			}else{
				unit = "Ns";
				value = String.format("~%.2f", perOp);
			}
		}
		
		System.out.println(
			(state.hasFail()? red + "FAIL " : green + "OK | ") +
			gray + "Progress: " + reset + (f.length()<4? " " + f : f) + "%" +
			gray + ", ET: " + reset + stdTime(state.estimatedTotalTime()) +
			gray + ", ETR: " + reset + stdTime(state.estimatedTimeRemaining()) +
			gray + ", elapsed: " + reset + stdTime(state.elapsed()) +
			gray + ", " + unit + "/op: " + reset + value
		);
	};
	
	private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1000_000_000L);
	private static BigInteger durationToBigDecimal(Duration duration){
		var seconds = duration.getSeconds();
		var nanos   = duration.getNano();
		return BigInteger.valueOf(seconds).multiply(NANOS_PER_SECOND).add(BigInteger.valueOf(nanos));
	}
	private static Duration bigToDuration(BigDecimal val){
		return val == null? null : bigToDuration(val.toBigInteger());
	}
	private static Duration bigToDuration(BigInteger val){
		if(val == null) return null;
		var seconds = val.divide(NANOS_PER_SECOND);
		var nanos   = val.subtract(seconds.multiply(NANOS_PER_SECOND));
		return Duration.ofSeconds(seconds.longValueExact(), nanos.longValueExact());
	}
	
	private void log(long count, BigDecimal progress){
		var now = Instant.now();
		
		var elapsedTime = Duration.between(start, now);
		
		var progressZero = progress.equals(BigDecimal.ZERO);
		
		var elapsedTimeNs      = new BigDecimal(durationToBigDecimal(elapsedTime));
		var totalTimeEstimated = progressZero? null : elapsedTimeNs.divide(progress, MathContext.DECIMAL32);
		var timeRemaining      = progressZero? null : totalTimeEstimated.subtract(elapsedTimeNs);
		
		logger.accept(new FuzzConfig.LogState(
			progress.doubleValue(),
			count<100? null : bigToDuration(totalTimeEstimated),
			count<100? null : bigToDuration(timeRemaining),
			elapsedTime,
			count<100? OptionalDouble.empty() : OptionalDouble.of(elapsedTimeNs.divide(BigDecimal.valueOf(count), MathContext.DECIMAL64).doubleValue()),
			errMark
		));
	}
}
