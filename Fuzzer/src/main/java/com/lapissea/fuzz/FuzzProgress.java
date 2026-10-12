package com.lapissea.fuzz;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static com.lapissea.fuzz.FuzzConfig.none;
import static com.lapissea.fuzz.FuzzConfig.some;
import static com.lapissea.fuzz.FuzzTime.bigToDuration;
import static com.lapissea.fuzz.FuzzTime.durationToBigDecimal;

public final class FuzzProgress{
	
	private static final class Timing{
		BigInteger ops, nanos;
		public Timing(){
			ops = nanos = BigInteger.ZERO;
		}
		void add(Timing other){
			ops = ops.add(other.ops);
			nanos = nanos.add(other.nanos);
		}
		void add(long ops, long nanos){
			this.ops = this.ops.add(BigInteger.valueOf(ops));
			this.nanos = this.nanos.add(BigInteger.valueOf(nanos));
		}
	}
	
	public final FuzzConfig config;
	
	private final    WarmupTiming        timing        = new WarmupTiming();
	private final    Map<String, Timing> workerTimings = new LinkedHashMap<>();
	private final    AtomicLong          executedCount = new AtomicLong();
	private          Instant             lastLog       = NanoClock.now();
	private          Instant             start         = NanoClock.now();
	private volatile boolean             hasErr;
	private          boolean             errMark;
	
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
	
	public synchronized void err(){
		var oldErr = hasErr;
		hasErr = errMark = true;
		if(!oldErr){
			var count = executedCount.get();
			logInc(count, calcProgressI(count));
		}
	}
	public synchronized void errLater(){
		if(errMark || !config.shouldLog()){ return; }
		errMark = true;
		var count = executedCount.get();
		logInc(count, calcProgressI(count));
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
		for(var p : incPeriods){ sum += p; }
		return Math.max(sum/incPeriods.length, 2);
	}
	
	public void reportStepsAndPeriod(int num, long nsDuration, int incPeriod){
		reportSteps(num, nsDuration);
		reportIncrementPeriod(incPeriod);
	}
	
	public void reportSteps(int num, long nsDuration){
		if(num<0 || nsDuration<0){ throw new IllegalArgumentException("Negative progress or duration"); }
		reportTiming(num, nsDuration);
		reportResolvedSteps(num);
	}
	
	/**
	 * Adds execution cost, including duplicate attempts
	 */
	public synchronized void reportWorkerTiming(String worker, long operations, long nsDuration){
		if(operations<0 || nsDuration<0) throw new IllegalArgumentException("Negative timing");
		
		workerTimings.computeIfAbsent(Objects.requireNonNull(worker), key -> new Timing()).add(operations, nsDuration);
		if(worker.equals("local")){
			reportTiming(operations, nsDuration);
		}
	}
	
	private synchronized void reportTiming(long operations, long nsDuration){
		timing.add(operations, nsDuration);
	}
	public void reportResolvedSteps(long operations){
		if(operations<0){ throw new IllegalArgumentException("Negative progress"); }
		long count = executedCount.addAndGet(operations);
		if(logger != null){ logInc(count, calcProgressI(count)); }
	}
	
	public void tick(){
		if(logger != null){
			long count = executedCount.get();
			logInc(count, calcProgressI(count));
		}
	}
	
	public synchronized void reportWorkerConnected(String name){
		reportWorkerTiming(name, 0, 0);
		if(logger != null) logger.log(new LogMessage.WorkerConnected(name));
	}
	
	public synchronized void reportStart(){
		if(logger == null) return;
		start = NanoClock.now();
		lastLog = start.plus(config.initialLogDelay()).minus(config.logTimeout());
		logger.log(new LogMessage.Start(config.name()));
	}
	public synchronized void reportDone(){
		reportDone(!errMark && iterationsComputed() && executedCount.get()>=totalIterations());
	}
	public synchronized void reportDone(boolean complete){
		if(logger == null) return;
		BigDecimal progress = complete && !errMark? BigDecimal.ONE : progress(executedCount.get());
		logger.log(makeLogState(NanoClock.now(), start, errMark, executedCount.get(), progress));
		logger.log(new LogMessage.End());
	}
	public synchronized void reportCustomMsg(String msg){
		if(logger == null) return;
		logger.log(new LogMessage.CustomMessage(msg));
	}
	
	private static final int  PROGRESS_PRECISION = 1000;
	private static final long MAX_FAST_COUNT     = (Long.MAX_VALUE/PROGRESS_PRECISION);
	
	private int calcProgressI(long count){
		var ti = totalIterations();
		if(ti == 0) return 0;
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
		if(iters != -1){ return iters; }
		if(totalIterations.isDone()){
			try{
				iters = totalIterations.join();
			}catch(CompletionException e){
				// An async count that failed is treated like an unknown total: never let progress logging crash the run.
				iters = Long.MAX_VALUE;
			}
			if(iters<0){ throw new IllegalStateException("Total iterations can't be negative"); }
			totalIterationsDone = iters;
		}else{
			iters = Long.MAX_VALUE;
		}
		return iters;
	}
	
	private void logInc(long count, int progressI){
		if(logger == null){ return; }
		var now   = NanoClock.now();
		var ltout = config.logTimeout();
		if(Duration.between(lastLog, now).compareTo(ltout)<=0){
			return;
		}
		
		synchronized(this){
			if(Duration.between(lastLog, now).compareTo(ltout)<=0){
				return;
			}
			
			lastLog = now;
			
			BigDecimal progress = progress(count);
			
			var logState = makeLogState(now, start, errMark, count, progress);
			logger.log(logState);
		}
	}
	private BigDecimal progress(long count){
		if(!iterationsComputed() || totalIterations() == 0){
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(count)
		                 .divide(BigDecimal.valueOf(totalIterations()), MathContext.DECIMAL128)
		                 .min(BigDecimal.ONE);
	}
	private static Optional<Duration> estimatedDuration(BigDecimal nanos){
		try{
			return some(bigToDuration(nanos));
		}catch(ArithmeticException e){
			return none();
		}
	}
	
	private LogMessage.State makeLogState(Instant now, Instant start, boolean errMark, long count, BigDecimal progress){
		var elapsedTime = Duration.between(start, now);
		
		double     perOp         = timing.nanosPerOp();
		boolean    hasData       = Double.isFinite(perOp);
		boolean    estimate      = count>0 && progress.signum()>0;
		BigDecimal elapsedTimeNs = new BigDecimal(durationToBigDecimal(elapsedTime));
		BigDecimal timeRemaining = null, totalTimeEstimated = null;
		
		if(estimate){
			var total = new Timing();
			for(var worker : workerTimings.values()){
				total.add(worker);
			}
			BigDecimal correction;
			if(total.ops.signum() == 0 || total.nanos.signum() == 0){
				correction = BigDecimal.ONE;
			}else{
				correction = BigDecimal.valueOf(count).divide(new BigDecimal(total.ops), MathContext.DECIMAL64);
			}
			timeRemaining = elapsedTimeNs.multiply(BigDecimal.ONE.divide(progress, MathContext.DECIMAL64).subtract(BigDecimal.ONE))
			                             .multiply(correction)
			                             .max(BigDecimal.ZERO);
			totalTimeEstimated = elapsedTimeNs.add(timeRemaining);
		}
		
		var workers = new LinkedHashMap<String, Double>();
		workerTimings.forEach((name, timing) -> {
			if(!name.equals("local")){
				double value;
				if(timing.ops.signum() == 0) value = Double.NaN;
				else{
					value = new BigDecimal(timing.nanos)
						        .divide(new BigDecimal(timing.ops), MathContext.DECIMAL64)
						        .doubleValue();
				}
				workers.put(name, value);
			}
		});
		
		return new LogMessage.State(
			progress.doubleValue(),
			!estimate? none() : estimatedDuration(totalTimeEstimated),
			!estimate? none() : estimatedDuration(timeRemaining),
			elapsedTime,
			!hasData? OptionalDouble.empty() : OptionalDouble.of(perOp),
			errMark, workers
		);
	}
}
