package com.lapissea.fuzz;

import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class FuzzProgressLoggingTest{
	private static FuzzProgress progress(List<LogMessage> messages, long total){
		return new FuzzProgress(new FuzzConfig().logWith(messages::add).withInitialLogDelay(Duration.ZERO).withLogTimeout(Duration.ZERO),
		                        CompletableFuture.completedFuture(total));
	}
	private static LogMessage.State lastState(List<LogMessage> messages){
		return messages.stream().filter(LogMessage.State.class::isInstance).map(LogMessage.State.class::cast).reduce((a, b) -> b).orElseThrow();
	}
	
	@Test
	public void localProgressCountsOnlyCompletedActions(){
		for(var mode : List.of("success", "action", "factory", "null", "cancel")){
			for(int stop : new int[]{0, 2, 150}){
				var messages  = new ArrayList<LogMessage>();
				var progress  = progress(messages, 200);
				var completed = new AtomicInteger();
				var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
					if(mode.equals("action") && completed.get() == stop){ throw new IllegalStateException("action"); }
					completed.incrementAndGet();
					if(mode.equals("cancel") && completed.get() == stop){ progress.err(); }
				}), random -> {
					if(completed.get() == stop){
						if(mode.equals("factory")){ throw new IllegalStateException("factory"); }
						if(mode.equals("null")){ return null; }
					}
					return new Object();
				});
				
				progress.reportStart();
				if(mode.equals("cancel") && stop == 0){ progress.err(); }
				var failure = runner.runSequence(RunMark.NONE, new FuzzSequence(0, 0, 7, 200), progress);
				assertEquals(failure.isPresent(), !mode.equals("success") && !mode.equals("cancel"));
				progress.reportDone();
				assertEquals(lastState(messages).progress(), (mode.equals("success")? 200 : stop)/200D, mode + " at " + stop);
			}
		}
	}
	
	@Test
	public void singleLocalFailureMarksFinalLog(){
		var messages = new ArrayList<LogMessage>();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			if(index == 2){ throw new IllegalStateException("action"); }
		}), FuzzingRunner::noopAction);
		var failures = runner.runLocal(new FuzzConfig().logWith(messages::add).withInitialLogDelay(Duration.ZERO), RunMark.NONE,
		                               () -> Stream.of(new FuzzSequence(0, 0, 7, 5)));
		
		assertEquals(failures.size(), 1);
		assertEquals(lastState(messages).progress(), 0.4D);
		assertTrue(lastState(messages).hasFail());
	}
	
	@Test
	public void timingDoesNotResolveDuplicateAttempts(){
		var messages = new ArrayList<LogMessage>();
		var progress = progress(messages, 100);
		
		progress.reportStart();
		progress.reportWorkerConnected("Ryzen");
		progress.tick();
		
		var idle = lastState(messages);
		assertEquals(idle.progress(), 0D);
		assertTrue(Double.isNaN(idle.workerNanosecondsPerOp().get("Ryzen")));
		
		progress.reportWorkerTiming("local", 10, 20_000_000);
		progress.reportWorkerTiming("Ryzen", 20, 10_000_000);
		progress.reportWorkerTiming("Ryzen", 20, 10_000_000); // duplicate attempt work counts as timing only
		progress.reportResolvedSteps(20);
		progress.reportDone(false);
		
		var state = lastState(messages);
		assertEquals(state.progress(), 0.2D);
		assertEquals(state.nanosecondsPerOp().orElseThrow(), 2_000_000D);
		assertEquals(state.workerNanosecondsPerOp().get("Ryzen").doubleValue(), 500_000D);
		assertFalse(state.workerNanosecondsPerOp().containsKey("local"));
	}
	
	@Test
	public void elapsedOnlyTerminalCostUpdatesLocalTiming(){
		var messages = new ArrayList<LogMessage>();
		var progress = progress(messages, 10);
		progress.reportStart();
		progress.reportWorkerTiming("local", 2, 1000);
		progress.reportWorkerTiming("local", 0, 600);
		progress.reportDone(false);
		
		assertEquals(lastState(messages).nanosecondsPerOp().orElseThrow(), 800D);
	}
	
	@Test
	public void localWorkerTimingRetainsWarmupDetection(){
		var messages = new ArrayList<LogMessage>();
		var progress = progress(messages, 10_000);
		progress.reportStart();
		progress.reportWorkerConnected("local");
		for(int i = 0; i<6; i++){
			progress.reportWorkerTiming("local", 10, 100_000);
		}
		for(int i = 0; i<100; i++){
			progress.reportWorkerTiming("local", 10, 10_000);
		}
		progress.reportResolvedSteps(1060);
		progress.reportDone(false);
		
		assertEquals(lastState(messages).nanosecondsPerOp().orElseThrow(), 1000D);
	}
	
	@Test
	public void partialFailureAndEmptyJobs(){
		var messages = new ArrayList<LogMessage>();
		var progress = progress(messages, 100);
		progress.reportStart();
		progress.reportResolvedSteps(5);
		progress.err();
		progress.reportDone();
		
		assertEquals(lastState(messages).progress(), 0.05D);
		assertTrue(lastState(messages).hasFail());
		
		var empty = new ArrayList<LogMessage>();
		var zero  = progress(empty, 0);
		zero.reportStart();
		zero.tick();
		
		assertEquals(lastState(empty).progress(), 0D);
		zero.reportDone(true);
		assertEquals(lastState(empty).progress(), 1D);
		
		new FuzzProgress(new FuzzConfig().dontLog(), CompletableFuture.completedFuture(0L)).err();
	}
	
	@Test
	public void consoleUsesMillisecondsAndStructuredConnection(){
		var output = new ByteArrayOutputStream();
		var saved  = System.out;
		try(var capture = new PrintStream(output, true, StandardCharsets.UTF_8)){
			System.setOut(capture);
			FuzzLogger.TO_CONSOLE.log(new LogMessage.WorkerConnected("Ryzen"));
			FuzzLogger.TO_CONSOLE.log(new LogMessage.State(0.25, Optional.empty(), Optional.empty(), Duration.ZERO, OptionalDouble.of(500), false,
			                                               Map.of("Ryzen", 250_000D)));
		}finally{ System.setOut(saved); }
		
		var text = output.toString(StandardCharsets.UTF_8).replaceAll("\\x1B\\[[0-9;]*m", "");
		assertTrue(text.contains("OK | Distributed worker connected: \"Ryzen\""));
		assertTrue(text.contains("ms/op: ~0.0005"));
		assertTrue(text.contains("Ryzen ms/op: ~0.2500"));
	}
	
	@Test
	public void csvPreservesSchemaAndWritesWorkerMetadata() throws Exception{
		var file = Files.createTempFile("fuzz-progress", ".csv");
		try{
			var logger = new FuzzLogger.LogCSV(file.toFile());
			logger.log(new LogMessage.Start(Optional.empty()));
			logger.log(new LogMessage.WorkerConnected("Ryzen"));
			logger.log(
				new LogMessage.State(0, Optional.empty(), Optional.empty(), Duration.ZERO, OptionalDouble.empty(), false, Map.of("Ryzen", 500D)));
			logger.log(new LogMessage.End());
			
			var lines = Files.readAllLines(file);
			assertEquals(lines.getFirst(),
			             "State,Progress,Estimated time,Estimated time remaining,elapsed,Estimated time ms,Estimated time remaining ms,elapsed ms,ns/op,extra");
			assertTrue(lines.get(1).contains("Distributed worker connected"));
			assertTrue(lines.get(2).endsWith("{Ryzen=500.0}"));
		}finally{ Files.deleteIfExists(file); }
	}
	
	@Test
	public void failedTotalCountDoesNotCrashLogging(){
		var messages = new ArrayList<LogMessage>();
		var total    = new CompletableFuture<Long>();
		total.completeExceptionally(new RuntimeException("source failed"));
		var progress = new FuzzProgress(
			new FuzzConfig().logWith(messages::add).withInitialLogDelay(Duration.ZERO).withLogTimeout(Duration.ZERO),
			total);
		
		progress.reportStart();
		progress.tick();
		progress.reportDone();
	}
}
