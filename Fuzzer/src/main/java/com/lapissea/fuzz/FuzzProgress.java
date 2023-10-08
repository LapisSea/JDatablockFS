package com.lapissea.fuzz;

import java.time.Duration;
import java.time.Instant;
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
	
	private final FuzzConfig config;
	private final long       totalIterations;
	public FuzzProgress(FuzzConfig config, long totalIterations){
		this.config = config;
		this.totalIterations = totalIterations;
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
		return (int)((count*1000)/totalIterations);
	}
	
	private void logInc(long count, int progressI){
		if(progressI == last) return;
		var now = Instant.now();
		if(Duration.between(lastLog, now).compareTo(config.logTimeout())>0){
			lastLog = now;
			last = progressI;
			log(count, (float)(progressI/1000D));
		}
	}
	
	private static final Consumer<FuzzConfig.LogState> TO_CONSOLE = state -> {
		final String red   = "\033[0;91m";
		final String green = "\033[0;92m";
		
		final String gray  = "\033[0;90m";
		final String reset = "\033[0m";
		
		var f = String.format("%.1f", state.progress()*100);
		System.out.println(
			(state.hasFail()? red + "FAIL " : green + "OK | ") +
			gray + "Progress: " + reset + (f.length()<4? " " + f : f) + "%" +
			gray + ", ET: " + reset + stdTime(state.estimatedTotalTime()) +
			gray + ", ETR: " + reset + stdTime(state.estimatedTimeRemaining()) +
			gray + ", elapsed: " + reset + stdTime(state.elapsed()) +
			gray + ", ms/op: " + reset + (state.durationPerOp() == null? "--" :
			                              "~" + String.format("%.3f", state.durationPerOp().toNanos()/1000_000D))
		);
	};
	
	private void log(long count, float progress){
		var now = Instant.now();
		
		var elapsedTime   = Duration.between(start, now);
		var elapsedTimeMs = elapsedTime.toMillis();
		
		var totalTimeEstimated = elapsedTimeMs/progress;
		var timeRemaining      = totalTimeEstimated - elapsedTimeMs;
		
		config.logFunct().orElse(TO_CONSOLE).accept(new FuzzConfig.LogState(
			progress,
			count<10? null : Duration.ofMillis((long)totalTimeEstimated),
			count<10? null : Duration.ofMillis((long)timeRemaining),
			elapsedTime,
			count<10? null : elapsedTime.dividedBy(count),
			errMark
		));
	}
}
