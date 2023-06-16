package com.lapissea.cfs.run.fuzzing;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class FuzzProgress{
	
	private final AtomicLong executedCount = new AtomicLong();
	private       int        last          = -1;
	private       Instant    lastLog       = Instant.now();
	private final Instant    start         = Instant.now();
	private       boolean    hasErr;
	
	private final FuzzingRunner.Config config;
	private final long                 totalIterations;
	public FuzzProgress(FuzzingRunner.Config config, long totalIterations){
		this.config = config;
		this.totalIterations = totalIterations;
	}
	
	void err(){
		hasErr = true;
	}
	
	public boolean hasErr(){
		return hasErr;
	}
	
	void inc(){
		if(!config.shouldLog()) return;
		var count     = executedCount.incrementAndGet();
		var progressI = (int)((count*1000)/totalIterations);
		
		if(progressI == last) return;
		synchronized(this){
			if(progressI == last) return;
			var now = Instant.now();
			if(Duration.between(lastLog, now).compareTo(config.logTimeout())>0){
				lastLog = now;
				last = progressI;
				log(count, (float)(progressI/1000D));
			}
		}
	}
	
	private static final Consumer<FuzzingRunner.LogState> TO_CONSOLE = state -> {
		final String gray  = "\033[0;90m";
		final String reset = "\033[0m";
		
		var f = String.format("%.1f", state.progress()*100);
		System.out.println(
			gray + "Progress: " + reset + (f.length()<4? " " + f : f) + "%" +
			gray + ", ET: " + reset + tim(state.estimatedTotalTime()) +
			gray + ", ETR: " + reset + tim(state.estimatedTimeRemaining()) +
			gray + ", elapsed: " + reset + tim(state.elapsed()) +
			gray + ", ms/op: " + reset + (state.durationPerOp() == null? "--" :
			                              "~" + String.format("%.3f", state.durationPerOp().toNanos()/1000_000D))
		);
	};
	
	private static String tim(Duration tim){
		if(tim == null) return "-:--:--";
		return String.format("%d:%02d:%02d",
		                     tim.toHoursPart(),
		                     tim.toMinutesPart(),
		                     tim.toSecondsPart());
	}
	
	private void log(long count, float progress){
		var now = Instant.now();
		
		var elapsedTime   = Duration.between(start, now);
		var elapsedTimeMs = elapsedTime.toMillis();
		
		var totalTimeEstimated = elapsedTimeMs/progress;
		var timeRemaining      = totalTimeEstimated - elapsedTimeMs;
		
		config.logFunct().orElse(TO_CONSOLE).accept(new FuzzingRunner.LogState(
			progress,
			count<10? null : Duration.ofMillis((long)totalTimeEstimated),
			count<10? null : Duration.ofMillis((long)timeRemaining),
			elapsedTime,
			count<10? null : elapsedTime.dividedBy(count)
		));
	}
}
