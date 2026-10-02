package com.lapissea.fuzz;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static com.lapissea.fuzz.FuzzConfig.none;
import static com.lapissea.fuzz.FuzzConfig.some;
import static com.lapissea.fuzz.FuzzTime.bigToDuration;
import static com.lapissea.fuzz.FuzzTime.durationToBigDecimal;

public final class FuzzProgress{
	
	public final FuzzConfig config;
	
	private final WarmupTiming timing = new WarmupTiming();
	
	private final AtomicLong executedCount  = new AtomicLong();
	private final AtomicLong elapsedSumAcum = new AtomicLong();
	private       BigInteger elapsedSum     = BigInteger.ZERO;
	private       int        last           = -1;
	private       Instant    lastLog        = NanoClock.now();
	private       Instant    start          = NanoClock.now();
	private       boolean    hasErr;
	private       boolean    errMark;
	
	private final int[] incPeriods   = new int[10];
	private       int   incPeriodPos = -1;
	
	private final CompletableFuture<Long> totalIterations;
	private       long                    totalIterationsDone = -1;
	
	private final FuzzLogger logger;
	
	public FuzzProgress(FuzzConfig config, CompletableFuture<Long> totalIterations){
		this.config = config;
		this.totalIterations = totalIterations;
		logger = config.shouldLog()? config.logFunct().orElse(FuzzLogger.TO_CONSOLE) : null;
		Arrays.fill(incPeriods, 2);
	}
	
	synchronized void err(){
		var oldErr = hasErr;
		hasErr = errMark = true;
		if(!oldErr){
			var count = executedCount.get();
			logInc(calcProgressI(count));
		}
	}
	synchronized void errLater(){
		if(errMark || !config.shouldLog()) return;
		errMark = true;
		var count = executedCount.get();
		logInc(calcProgressI(count));
	}
	
	public boolean hasErr(){
		return hasErr;
	}
	
	public void reportIncrementPeriod(int incPeriod){
		synchronized(incPeriods){
			if(incPeriodPos == -1){
				Arrays.fill(incPeriods, incPeriod);
				incPeriodPos = 0;
				return;
			}
			incPeriods[incPeriodPos] = incPeriod;
			incPeriodPos = (incPeriodPos + 1)%incPeriods.length;
		}
	}
	public int estimatedIncrementPeriod(){
		int sum = 0;
		for(var p : incPeriods) sum += p;
		return Math.max(sum/incPeriods.length, 2);
	}
	
	void reportStepsAndPeriod(int num, long nsDuration, int incPeriod){
		reportSteps(num, nsDuration);
		reportIncrementPeriod(incPeriod);
	}
	
	void reportSteps(int num, long nsDuration){
		if(num == 0) return;
		if(num<0) throw new IllegalArgumentException("Can not increment progress by negative value");
		if(nsDuration<0) throw new IllegalArgumentException("Duration must be positive!");
		if(!config.shouldLog()) return;
		
		long count;
		synchronized(this){
			timing.add(num, nsDuration);
			var dur = elapsedSumAcum.addAndGet(nsDuration);
			if(dur>Long.MAX_VALUE/2){
				elapsedSumAcum.getAndAdd(-dur);
				elapsedSum = elapsedSum.add(BigInteger.valueOf(dur));
			}
			count = executedCount.addAndGet(num);
		}
		
		var progressI = calcProgressI(count);
		
		if(progressI == last) return;
		
		logInc(progressI);
	}
	
	synchronized void reportStart(){
		if(logger == null) return;
		start = NanoClock.now();
		lastLog = start.plus(config.initialLogDelay()).minus(config.logTimeout());
		logger.log(new LogMessage.Start(config.name()));
	}
	synchronized void reportDone(){
		if(logger == null) return;
		logger.log(makeLogState(NanoClock.now(), start, errMark, executedCount.get(), BigDecimal.ONE, calcElapsedWork()));
		logger.log(new LogMessage.End());
	}
	synchronized void reportCusomMsg(String msg){
		if(logger == null) return;
		logger.log(new LogMessage.CustomMessage(msg));
	}
	
	private static final int  PROGRESS_PRECISION = 1000;
	private static final long MAX_FAST_COUNT     = (Long.MAX_VALUE/PROGRESS_PRECISION);
	
	private int calcProgressI(long count){
		var ti = totalIterations();
		if(ti == 0){
			return PROGRESS_PRECISION;
		}
		if(count<MAX_FAST_COUNT){
			return (int)((count*PROGRESS_PRECISION)/ti);
		}
		return BigInteger.valueOf(count).multiply(BigInteger.valueOf(PROGRESS_PRECISION))
		                 .divide(BigInteger.valueOf(ti))
		                 .intValueExact();
	}
	
	
	private boolean iterationsComputed(){
		return totalIterationsDone != -1 || totalIterations.isDone();
	}
	private long totalIterations(){
		long iters = totalIterationsDone;
		if(iters != -1) return iters;
		if(totalIterations.isDone()){
			iters = totalIterations.join();
			if(iters<0) throw new IllegalStateException("Total iterations can't be negative");
			totalIterationsDone = iters;
		}else{
			iters = Long.MAX_VALUE;
		}
		return iters;
	}
	
	private void logInc(int progressI){
		var now   = NanoClock.now();
		var ltout = config.logTimeout();
		if(Duration.between(lastLog, now).compareTo(ltout)<=0) return;
		
		synchronized(this){
			if(Duration.between(lastLog, now).compareTo(ltout)<=0){
				return;
			}
			
			lastLog = now;
			last = progressI;
			
			long       count = executedCount.get();
			BigDecimal progress;
			if(iterationsComputed() && totalIterations()>0){
				progress = BigDecimal.valueOf(count).divide(BigDecimal.valueOf(totalIterations()), MathContext.DECIMAL128);
			}else{
				progress = BigDecimal.ZERO;
			}
			
			var logState = makeLogState(now, start, errMark, count, progress, calcElapsedWork());
			logger.log(logState);
		}
	}
	private BigInteger calcElapsedWork(){
		return elapsedSum.add(BigInteger.valueOf(elapsedSumAcum.get()));
	}
	private static Optional<Duration> estimatedDuration(BigDecimal nanos){
		try{
			return some(bigToDuration(nanos));
		}catch(ArithmeticException e){
			return none();
		}
	}
	
	private LogMessage.State makeLogState(Instant now, Instant start, boolean errMark, long count, BigDecimal progress, BigInteger elapsedWork){
		var elapsedTime = Duration.between(start, now);
		
		double     perOp         = timing.nanosPerOp();
		boolean    hasData       = count>0 && Double.isFinite(perOp);
		boolean    estimate      = hasData && progress.signum()>0;
		BigDecimal elapsedTimeNs = new BigDecimal(durationToBigDecimal(elapsedTime));
		BigDecimal timeRemaining = null, totalTimeEstimated = null;
		
		if(estimate){
			BigDecimal correction;
			if(elapsedWork.signum() == 0){
				correction = BigDecimal.ONE;
			}else{
				correction = BigDecimal.valueOf(perOp)
				                       .multiply(BigDecimal.valueOf(count))
				                       .divide(new BigDecimal(elapsedWork), MathContext.DECIMAL64);
			}
			timeRemaining = elapsedTimeNs.multiply(BigDecimal.ONE.divide(progress, MathContext.DECIMAL64).subtract(BigDecimal.ONE))
			                             .multiply(correction)
			                             .max(BigDecimal.ZERO);
			totalTimeEstimated = elapsedTimeNs.add(timeRemaining);
		}
		
		return new LogMessage.State(
			progress.doubleValue(),
			!estimate? none() : estimatedDuration(totalTimeEstimated),
			!estimate? none() : estimatedDuration(timeRemaining),
			elapsedTime,
			!hasData? OptionalDouble.empty() : OptionalDouble.of(perOp),
			errMark
		);
	}
}
