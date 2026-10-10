package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.FailOrder;
import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.LogMessage;
import com.lapissea.fuzz.RunMark;
import com.lapissea.fuzz.distributed.RecipeCodec;
import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import com.lapissea.fuzz.distributed.Wire;
import com.lapissea.fuzz.distributed.helper.HelperProtocolTest;
import org.testng.annotations.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.testng.Assert.*;

public final class DistributedSessionTest{

	@Test(timeOut = 15000)
	public void helperDiagnosticsIgnoreOldJobsAndPreserveQueueBound() throws Exception{
		var session  = new DistributedSession(List.of());
		var executor = Executors.newSingleThreadExecutor();
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		    var socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
		    var wire = new Wire(socket)){
			wire.socket.setSoTimeout(5000);
			session.attachHelper("helper", 1, server.accept());
			var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> Thread.sleep(60000)),
			                                 FuzzingRunner::noopAction);
			var run = executor.submit(() -> session.execute(runner, HelperProtocolTest.recipe("ok"), new FuzzConfig().dontLog().withMaxWorkers(1),
			                                                RunMark.NONE, () -> IntStream.range(0, 100).mapToObj(i -> new FuzzSequence(i, i, i, 1))));

			assertTrue(wire.receive() instanceof Wire.Job);
			var first = (Wire.Work)wire.receive();
			assertTrue(wire.receive() instanceof Wire.Work);
			// An old complaint must neither alter this job nor be mistaken for a START request.
			wire.send(new Wire.Demand(UUID.randomUUID(), 1, 0));
			wire.send(new Wire.Demand(first.assignment().job(), 1, 0));
			wire.send(new Wire.Start(first.assignment()));
			assertEquals(wire.receive(), new Wire.StartAck(first.assignment(), true));
			var refill = (Wire.Work)wire.receive();
			assertEquals(refill.assignment().job(), first.assignment().job());
			assertNotEquals(refill.assignment().id(), first.assignment().id());
			session.close();
			try{ run.get(5, TimeUnit.SECONDS); }catch(ExecutionException e){
				assertTrue(e.getCause() instanceof CancellationException);
			}
		}finally{
			session.close();
			executor.shutdownNow();
		}
	}
	
	@Test(timeOut = 30000)
	public void runLifecycleHooksExecuteOnceOnLocalAndHelperMachines() throws Exception{
		var     markerDir = Files.createTempDirectory("fuzzer-lifecycle");
		var     session   = new DistributedSession(List.of());
		Process helper    = null;
		try(var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))){
			server.setSoTimeout(10000);
			helper = HelperProtocolTest.helperProcess(server, UUID.randomUUID().toString());
			var socket    = server.accept();
			var handshake = new Wire(socket);
			assertTrue(handshake.receive() instanceof Wire.Hello);
			socket.setSoTimeout(Wire.SILENCE_TIMEOUT_SECONDS*1000);
			session.attachHelper("test-helper", 1, socket);
			
			for(int run = 0; run<2; run++){
				boolean failure    = run == 1;
				String  markerPath = markerDir.toAbsolutePath().toString();
				var     runner     = lifecycleRunner(markerPath, failure);
				var     recipe     = RecipeCodec.encode(() -> lifecycleRunner(markerPath, failure));
				var result = session.execute(runner, recipe, new FuzzConfig().dontLog().withMaxWorkers(1).withErrorDelay(Duration.ZERO),
				                             RunMark.NONE,
				                             () -> IntStream.range(0, 10).mapToObj(i -> new FuzzSequence(i, i, i, 1)));
				assertEquals(result.isEmpty(), !failure);
				awaitLifecycleEvents(markerDir.resolve(helper.pid() + ".events"), (run + 1)*2);
			}
			
			List<String> expected = List.of("prepare", "cleanup", "prepare", "cleanup");
			assertEquals(Files.readAllLines(markerDir.resolve(ProcessHandle.current().pid() + ".events")), expected);
			assertEquals(Files.readAllLines(markerDir.resolve(helper.pid() + ".events")), expected);
		}finally{
			session.close();
			if(helper != null){
				helper.destroy();
				if(!helper.waitFor(5, TimeUnit.SECONDS)) helper.destroyForcibly();
			}
			try(var files = Files.list(markerDir)){ files.forEach(file -> { try{ Files.deleteIfExists(file); }catch(IOException ignored){ } }); }
			Files.deleteIfExists(markerDir);
		}
	}
	
	private static FuzzingRunner<Long, Object, Exception> lifecycleRunner(String markerDir, boolean failure){
		var prepared = new AtomicBoolean();
		var env = new FuzzingStateEnv.Marked<Long, Object, Exception>(){
			@Override
			public void prepareRun() throws Exception{
				if(!prepared.compareAndSet(false, true)) throw new IllegalStateException("Run state was not reset before prepare");
				recordLifecycle(markerDir, "prepare");
			}
			@Override
			public void cleanupRun() throws Exception{
				if(!prepared.compareAndSet(true, false)) throw new IllegalStateException("Run state was not prepared before cleanup");
				recordLifecycle(markerDir, "cleanup");
			}
			@Override
			public void applyAction(Long state, long actionIndex, Object action, RunMark mark){
				if(!prepared.get()) throw new IllegalStateException("Action ran outside a prepared run");
				if(failure) throw new IllegalStateException("expected lifecycle test failure");
			}
			@Override
			public Long create(RandomGenerator random, long sequenceIndex, RunMark mark){ return sequenceIndex; }
		};
		return new FuzzingRunner<>(env, FuzzingRunner::noopAction);
	}
	
	private static void recordLifecycle(String markerDir, String event) throws IOException{
		var file = Path.of(markerDir).resolve(ProcessHandle.current().pid() + ".events");
		Files.writeString(file, event + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
	
	private static void awaitLifecycleEvents(Path file, int expectedCount) throws Exception{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while(System.nanoTime()<deadline && (!Files.exists(file) || Files.readAllLines(file).size()<expectedCount)){
			Thread.sleep(5);
		}
		assertTrue(Files.exists(file), "Lifecycle hooks did not write " + file);
		assertEquals(Files.readAllLines(file).size(), expectedCount);
	}
	
	@Test(timeOut = 10000)
	public void originalOrderPreservesFailuresLocallyAndDistributed(){
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			throw new IllegalStateException("failure " + index);
		}), FuzzingRunner::noopAction);
		var config = new FuzzConfig().dontLog().withMaxWorkers(1).withFailOrder(FailOrder.ORIGINAL_ORDER)
		                             .withErrorDelay(Duration.ofSeconds(10))
		                             .withErrorTracking(new FuzzConfig.ErrorTracking(OptionalInt.of(2), true));
		Supplier<Stream<FuzzSequence>> source =
			() -> IntStream.range(0, 5).mapToObj(i -> new FuzzSequence(i, i, i, 1));

		var local = runner.runLocal(config, RunMark.NONE, source::get);
		assertEquals(local.size(), 1);
		assertEquals(local.getFirst().e().getMessage(), "failure 0");

		var session = new DistributedSession(List.of());
		try{
			var distributed = session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE, source);
			assertEquals(distributed.size(), 1);
			assertEquals(distributed.getFirst().e().getMessage(), "failure 0");
		}finally{ session.close(); }
	}
	
	@Test(timeOut = 10000)
	public void loggingAndFailureTrackingConfigurationIsPreserved(){
		var session = new DistributedSession(List.of());
		var logs    = new ArrayList<LogMessage>();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			Thread.sleep(15);
			throw new IllegalStateException("tracked failure");
		}), FuzzingRunner::noopAction);
		var config = new FuzzConfig().withMaxWorkers(1).withInitialLogDelay(Duration.ZERO).withLogTimeout(Duration.ofMillis(1))
		                             .withErrorDelay(Duration.ofSeconds(10)).logWith(logs::add)
		                             .withErrorTracking(new FuzzConfig.ErrorTracking(OptionalInt.of(2), false));
		try{
			var failures = session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE,
			                               () -> IntStream.range(0, 5).mapToObj(i -> new FuzzSequence(i, i, i, 1)));
			assertEquals(failures.size(), 2);
			assertTrue(logs.getFirst() instanceof LogMessage.Start);
			assertTrue(logs.getLast() instanceof LogMessage.End);
			assertTrue(logs.stream().anyMatch(m -> m instanceof LogMessage.State));

			var deduplicated = session.execute(runner, HelperProtocolTest.recipe("ok"),
			                                   config.withErrorTracking(new FuzzConfig.ErrorTracking(OptionalInt.empty(), true)), RunMark.NONE,
			                                   () -> IntStream.range(0, 5).mapToObj(i -> new FuzzSequence(i, i, i, 1)));
			assertEquals(deduplicated.size(), 1);
		}finally{ session.close(); }
	}

	@Test(timeOut = 10000)
	public void shortLocalJobAndHelperReuseRequireMasterReplay() throws Exception{
		var session = new DistributedSession(List.of());
		var replays = new AtomicInteger();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			if(mark.hasSequence()){
				replays.incrementAndGet();
				throw new IllegalStateException("master reproduced");
			}
			Thread.sleep(100);
		}), FuzzingRunner::noopAction);
		var config = new FuzzConfig().dontLog().withMaxWorkers(1).withErrorDelay(Duration.ZERO).withFailOrder(FailOrder.ORIGINAL_ORDER);
		try{
			assertTrue(session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE,
			                           () -> Stream.of(new FuzzSequence(0, 0, 0, 1))).isEmpty());

			try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var helper = new Socket(InetAddress.getLoopbackAddress(),
			                                                                                                   server.getLocalPort())){
				session.attachHelper("helper", 1, server.accept());
				var mock = mockHelper(helper, true);
				var fails = session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE,
				                            () -> Stream.of(new FuzzSequence(0, 1, 1, 1)));
				assertEquals(fails.size(), 1);
				assertEquals(replays.get(), 1);
				assertTrue(fails.getFirst().trace().contains("master reproduced"));
				var again = session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE,
				                            () -> Stream.of(new FuzzSequence(0, 2, 2, 1)));
				assertEquals(again.size(), 1);
				assertEquals(replays.get(), 2);
				session.close();
				mock.join(1000);
			}
		}finally{ session.close(); }
	}

	@Test(timeOut = 10000)
	public void remoteFailureThatPassesOnMasterStillFails() throws Exception{
		var session = new DistributedSession(List.of());
		var replays = new AtomicInteger();
		
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			if(mark.hasSequence()){
				replays.incrementAndGet();
			}else{
				Thread.sleep(100);
			}
		}), FuzzingRunner::noopAction);
		
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		    var helper = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
			
			session.attachHelper("helper", 1, server.accept());
			mockHelper(helper, true);
			boolean noFail = false;
			try{
				session.execute(
					runner, HelperProtocolTest.recipe("ok"),
					new FuzzConfig().dontLog().withMaxWorkers(1).withErrorDelay(Duration.ZERO), RunMark.NONE,
					() -> Stream.of(new FuzzSequence(0, 1, 1, 1))
				);
				noFail = true;
			}catch(AssertionError error){
				assertTrue(error.getMessage().contains("did not reproduce"), "Did not get 'did not reproduce' in '" + error.getMessage() + "'");
				assertTrue(error.getMessage().contains("mock remote stack"), "Did not get 'mock remote stack' in '" + error.getMessage() + "'");
			}
			if(noFail){
				fail("Remote-only failure must fail master test");
			}
			assertEquals(replays.get(), 1);
		}finally{
			session.close();
		}
	}

	@Test(timeOut = 10000)
	public void progressIncludesHelperTimingsAndAnnouncesConnectionOnce() throws Exception{
		var session = new DistributedSession(List.of());
		var logs    = new ArrayList<LogMessage>();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> Thread.sleep(50)),
		                                 FuzzingRunner::noopAction);
		var config = new FuzzConfig().withName("logging").withMaxWorkers(1).withInitialLogDelay(Duration.ZERO).withLogTimeout(Duration.ZERO)
		                             .logWith(logs::add);
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var helper = new Socket(InetAddress.getLoopbackAddress(),
		                                                                                                   server.getLocalPort())){
			session.attachHelper("workbench 1", 1, server.accept());
			var mock = mockHelper(helper, false);
			for(int job = 0; job<2; job++){
				assertTrue(session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE,
				                           () -> IntStream.range(0, 10).mapToObj(i -> new FuzzSequence(i, i, i, 1))).isEmpty());
			}

			assertEquals(logs.stream().filter(m -> m instanceof LogMessage.WorkerConnected).count(), 1L);
			assertFalse(logs.stream().anyMatch(m -> m instanceof LogMessage.CustomMessage));
			var states = logs.stream().filter(m -> m instanceof LogMessage.State).map(m -> (LogMessage.State)m).toList();
			assertTrue(states.stream().allMatch(m -> m.progress()>=0 && m.progress()<=1));
			assertEquals(states.getLast().progress(), 1D);
			assertTrue(states.stream().anyMatch(m -> m.workerNanosecondsPerOp().getOrDefault("workbench 1", Double.NaN) == 2_000_000D));
			session.close();
			mock.join(1000);
		}finally{ session.close(); }
	}

	@Test(timeOut = 10000)
	public void localTimingAvailableWhileSequenceStillRunning() throws Exception{
		var session = new DistributedSession(List.of());
		var logs    = new CopyOnWriteArrayList<LogMessage>();
		var release = new CountDownLatch(1);
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			if(index == 1){ release.await(); }
		}), FuzzingRunner::noopAction);
		var config   = new FuzzConfig().withMaxWorkers(1).withInitialLogDelay(Duration.ZERO).withLogTimeout(Duration.ofMillis(5)).logWith(logs::add);
		var finished = new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try{
				session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE,
				                () -> Stream.of(new FuzzSequence(0, 0, 0, 3)));
				finished.complete(null);
			}catch(Throwable e){
				finished.completeExceptionally(e);
			}
		});
		try{
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while(logs.stream().noneMatch(m -> m instanceof LogMessage.State state && state.nanosecondsPerOp().isPresent()) &&
			      System.nanoTime()<deadline){ Thread.sleep(5); }
			assertFalse(finished.isDone());
			assertTrue(
				logs.stream().anyMatch(m -> m instanceof LogMessage.State state && state.progress() == 1D/3 && state.nanosecondsPerOp().isPresent() &&
				                            state.estimatedTotalTime().isPresent() && state.estimatedTimeRemaining().isPresent()));
		}finally{
			release.countDown();
			finished.get(5, TimeUnit.SECONDS);
			session.close();
		}
	}

	@Test(timeOut = 10000)
	public void remoteTimingAvailableBeforeRemoteResult() throws Exception{
		var session = new DistributedSession(List.of());
		var logs    = new CopyOnWriteArrayList<LogMessage>();
		var release = new CountDownLatch(1);
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> release.await()),
		                                 FuzzingRunner::noopAction);
		var finished = new CompletableFuture<Void>();
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); var helper = new Socket(InetAddress.getLoopbackAddress(),
		                                                                                                   server.getLocalPort())){
			session.attachHelper("live helper", 1, server.accept());
			var wire = new Wire(helper);
			Thread.ofVirtual().start(() -> {
				try{
					RemoteSequenceResult result = null;
					while(true){
						var frame = wire.receive();
						switch(frame){
							case Wire.Job ignored -> { }
							case Wire.Work work -> {
								var sequence = work.sequence();
								result = new RemoteSequenceResult(
									work.assignment(), sequence, new RemoteSequenceResult.Ok(),
									RemoteSequenceResult.CapturedOutput.EMPTY,
									new RemoteSequenceResult.ExecutionStats(3, 6_000_000));
								wire.send(new Wire.Start(work.assignment()));
							}
							case Wire.StartAck ack -> {
								if(ack.allowed()){
									wire.send(new Wire.Progress(ack.assignment(), new RemoteSequenceResult.ExecutionStats(1, 2_000_000)));
									release.await();
									wire.send(new Wire.Result(result));
								}
							}
							case Wire.Cancel cancel -> {
								if(cancel.jobId() != null){
									wire.send(new Wire.CleanupComplete(cancel.jobId(), null));
								}
								return;
							}
							case Wire.CancelAssignment ignored -> { }
							default -> throw new IllegalStateException();
						}
					}
				}catch(Exception ignored){ }
			});
			var config = new FuzzConfig().withMaxWorkers(1).withInitialLogDelay(Duration.ZERO).withLogTimeout(Duration.ofMillis(5))
			                             .logWith(logs::add);
			Thread.ofVirtual().start(() -> {
				try{
					session.execute(runner, HelperProtocolTest.recipe("ok"), config, RunMark.NONE,
					                () -> Stream.of(new FuzzSequence(0, 0, 0, 3)));
					finished.complete(null);
				}catch(Throwable e){
					finished.completeExceptionally(e);
				}
			});
			try{
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while(logs.stream().noneMatch(m -> m instanceof LogMessage.State state &&
				                                   state.workerNanosecondsPerOp().getOrDefault("live helper", Double.NaN) == 2_000_000D) &&
				      System.nanoTime()<deadline){ Thread.sleep(5); }
				assertFalse(finished.isDone());
				assertTrue(logs.stream().anyMatch(m -> m instanceof LogMessage.State state && state.progress() == 1D/3 &&
				                                       state.estimatedTotalTime().isPresent() && state.estimatedTimeRemaining().isPresent() &&
				                                       state.workerNanosecondsPerOp().getOrDefault("live helper", Double.NaN) == 2_000_000D));
			}finally{
				release.countDown();
				finished.get(5, TimeUnit.SECONDS);
			}
		}finally{
			release.countDown();
			session.close();
		}
	}

	@Test(timeOut = 10000)
	public void failedSequenceOnlyCreditsCompletedOperations(){
		var session = new DistributedSession(List.of());
		var logs    = new ArrayList<LogMessage>();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			if(index == 1){ throw new IllegalStateException("second operation failed"); }
		}), FuzzingRunner::noopAction);
		try{
			var failures = session.execute(runner, HelperProtocolTest.recipe("ok"),
			                               new FuzzConfig().withMaxWorkers(1).logWith(logs::add), RunMark.NONE,
			                               () -> Stream.of(new FuzzSequence(0, 0, 0, 3)));

			assertEquals(failures.size(), 1);
			var state = logs.stream().filter(LogMessage.State.class::isInstance).map(LogMessage.State.class::cast)
			                .reduce((a, b) -> b).orElseThrow();
			assertTrue(state.hasFail());
			assertEquals(state.progress(), 1D/3);
		}finally{ session.close(); }
	}
	
	@Test(timeOut = 15000)
	public void replayKeepsCoordinatorResponsiveAndStopsOnInterruption() throws Exception{
		var session             = new DistributedSession(List.of());
		var replayStarted       = new CountDownLatch(1);
		var replayStopped       = new CountDownLatch(1);
		var release             = new CountDownLatch(1);
		var finished            = new CompletableFuture<Throwable>();
		var acknowledgedResults = new AtomicInteger();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			if(mark.hasSequence()){
				replayStarted.countDown();
				try{ release.await(); }finally{ replayStopped.countDown(); }
			}else{ release.await(); }
		}), FuzzingRunner::noopAction);
		Thread master = null;
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		    var helper = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
			
			session.attachHelper("helper", 1, server.accept());
			mockHelper(helper, true, acknowledgedResults);
			
			master = Thread.ofPlatform().start(() -> {
				try{
					session.execute(runner, HelperProtocolTest.recipe("ok"),
					                new FuzzConfig().dontLog().withMaxWorkers(1).withErrorDelay(Duration.ofSeconds(10)), RunMark.NONE,
					                () -> IntStream.range(0, 4).mapToObj(i -> new FuzzSequence(i, i, i, 1)));
					finished.complete(null);
				}catch(Throwable e){
					finished.complete(e);
				}
			});
			assertTrue(replayStarted.await(5, TimeUnit.SECONDS));
			// A second remote result requires another START acknowledgement while the first replay is blocked.
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while(acknowledgedResults.get()<2 && System.nanoTime()<deadline){
				Thread.sleep(5);
			}
			
			assertTrue(acknowledgedResults.get()>=2);
			master.interrupt();
			assertTrue(finished.get(5, TimeUnit.SECONDS) instanceof CancellationException);
			assertEquals(replayStopped.getCount(), 0L);
		}finally{
			release.countDown();
			if(master != null){
				master.interrupt();
				master.join(5000);
			}
			session.close();
		}
	}

	@Test(timeOut = 10000)
	public void invalidAndDuplicateFinishedReportsCannotChangeTiming() throws Exception{
		var session = new DistributedSession(List.of());
		var logs    = new ArrayList<LogMessage>();
		var runner = new FuzzingRunner<>(
			FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> Thread.sleep(100)),
			FuzzingRunner::noopAction
		);
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		    var helper = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
			session.attachHelper("helper", 1, server.accept());
			mockHelper(helper, false, new AtomicInteger(), true);
			var failures = session.execute(
				runner,
				HelperProtocolTest.recipe("ok"),
				new FuzzConfig().withMaxWorkers(1).withInitialLogDelay(Duration.ZERO)
				                .withLogTimeout(Duration.ZERO).logWith(logs::add), RunMark.NONE,
				() -> IntStream.range(0, 10).mapToObj(i -> new FuzzSequence(i, i, i, 1))
			);

			assertTrue(failures.isEmpty());
			var states = logs.stream().filter(LogMessage.State.class::isInstance).map(LogMessage.State.class::cast).toList();
			assertEquals(states.getLast().progress(), 1D);
			assertEquals(states.getLast().workerNanosecondsPerOp().get("helper"), 2_000_000D);
			assertTrue(states.stream().allMatch(state -> {
				var timing = state.workerNanosecondsPerOp().getOrDefault("helper", Double.NaN);
				return Double.isNaN(timing) || timing == 2_000_000D;
			}));
		}finally{ session.close(); }
	}
	
	@Test(timeOut = 10000)
	public void malformedHandshakesCloseWhileAnotherConnectionStalls() throws Exception{
		var session = new DistributedSession(List.of());
		try(var server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
		    var stalled = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
			session.acceptConnections(server);
			for(boolean truncatedFrame : new boolean[]{false, true}){
				try(var invalid = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
					invalid.setSoTimeout(2000);
					var out = new DataOutputStream(invalid.getOutputStream());
					out.writeInt(truncatedFrame? 20 : 1);
					out.writeByte(1); // HELLO opcode, deliberately missing protocol version and identity.
					out.flush();
					if(truncatedFrame){ invalid.shutdownOutput(); }
					assertEquals(invalid.getInputStream().read(), -1, "Malformed handshake must close promptly despite the stalled connection");
				}
			}
			session.close();
			stalled.setSoTimeout(2000);
			assertEquals(stalled.getInputStream().read(), -1, "Shutdown must also close pending handshakes");
		}finally{ session.close(); }
	}
	
	@Test(timeOut = 10000)
	public void disconnectedHelperSocketIsClosed() throws Exception{
		var session = new DistributedSession(List.of());
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		    var helper = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
			var accepted = server.accept();
			session.attachHelper("helper", 1, accepted);
			helper.close();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while(!accepted.isClosed() && System.nanoTime()<deadline){ Thread.sleep(5); }
			assertTrue(accepted.isClosed(), "Master must close a disconnected helper's socket");
		}finally{
			session.close();
		}
	}
	
	@Test(timeOut = 60000)
	public void stalledHelperDoesNotWedgeTheCoordinator() throws Exception{
		var session = new DistributedSession(List.of());
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		    var helper = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
			var accepted = server.accept();
			accepted.setSendBufferSize(4096);
			session.attachHelper("stalled", 1, accepted);
			// The helper never reads, so a recipe larger than its socket buffer blocks the Job write.
			var runner = new FuzzingRunner<>(
				FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> { }),
				FuzzingRunner::noopAction);
			var bigRecipe = new byte[1<<20];

			var fails = session.execute(runner, bigRecipe, new FuzzConfig().dontLog().withMaxWorkers(1), RunMark.NONE,
			                            () -> IntStream.range(0, 3).mapToObj(i -> new FuzzSequence(i, i, i, 1)));
			assertTrue(fails.isEmpty());
		}finally{
			session.close();
		}
	}
	
	@Test(timeOut = 10000)
	public void attachHelperRejectsDuplicatesAndClosedSessions() throws Exception{
		var session = new DistributedSession(List.of());
		try(var server = new ServerSocket(0, 3, InetAddress.getLoopbackAddress());
		    var c1 = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
		    var c2 = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
		    var c3 = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())){
			var s1 = server.accept();
			var s2 = server.accept();
			var s3 = server.accept();

			session.attachHelper("dup", 1, s1);
			assertThrows(IOException.class, () -> session.attachHelper("dup", 1, s2));
			session.close();
			assertThrows(IOException.class, () -> session.attachHelper("after-close", 1, s3));
		}finally{
			session.close();
		}
	}
	
	private static Thread mockHelper(Socket socket, boolean failure) throws Exception{
		return mockHelper(socket, failure, new AtomicInteger());
	}

	private static Thread mockHelper(Socket socket, boolean failure, AtomicInteger acknowledgedResults) throws Exception{
		return mockHelper(socket, failure, acknowledgedResults, false);
	}

	private static Thread mockHelper(Socket socket, boolean failure, AtomicInteger acknowledgedResults, boolean invalidReports) throws Exception{
		var wire     = new Wire(socket);
		var assigned = new HashMap<Long, RemoteSequenceResult>();
		return Thread.ofPlatform().name("mock-helper").start(() -> {
			try{
				while(true){
					var frame = wire.receive();
					switch(frame){
						case Wire.Job job -> {
							System.out.println("[mock helper]: " + job);
						}
						case Wire.Work work -> {
							System.out.println("[mock helper]: " + work);
							var sequence = work.sequence();
							
							RemoteSequenceResult.Status status;
							if(failure){
								status = new RemoteSequenceResult.Failed(new RemoteSequenceResult.RemoteFailure(
									sequence.startIndex(), "MockFailure", "problem", "mock remote stack"
								));
							}else status = new RemoteSequenceResult.Ok();
							
							RemoteSequenceResult value = new RemoteSequenceResult(
								work.assignment(), sequence, status,
								RemoteSequenceResult.CapturedOutput.EMPTY,
								new RemoteSequenceResult.ExecutionStats(sequence.iterations(), sequence.iterations()*2_000_000L)
							);
							
							assigned.put(work.assignment().id(), value);
							
							if(invalidReports){
								wire.send(new Wire.Result(new RemoteSequenceResult(
									work.assignment(), sequence, new RemoteSequenceResult.Ok(),
									RemoteSequenceResult.CapturedOutput.EMPTY,
									new RemoteSequenceResult.ExecutionStats(sequence.iterations(), 999_000_000)
								)));
							}
							wire.send(new Wire.Start(work.assignment()));
						}
						case Wire.StartAck ack -> {
							System.out.println("[mock helper]: " + ack);
							long    id      = ack.assignment().id();
							boolean allowed = ack.allowed();
							if(allowed){
								var             result     = assigned.remove(id);
								Wire.Assignment assignment = new Wire.Assignment(result.assignment().job(), id);
								if(invalidReports){
									wire.send(new Wire.Result(new RemoteSequenceResult(
										assignment,
										new FuzzSequence(0, 999, 999, 1), result.status(), result.output(),
										new RemoteSequenceResult.ExecutionStats(1, 999_000_000)
									)));
								}
								wire.send(new Wire.Result(result));
								if(invalidReports){
									wire.send(new Wire.Result(new RemoteSequenceResult(
										assignment, result.sequence(), result.status(), result.output(),
										new RemoteSequenceResult.ExecutionStats(result.sequence().iterations(), 999_000_000)
									)));
								}
								acknowledgedResults.incrementAndGet();
							}
						}
						case Wire.Cancel cancel -> {
							System.out.println("[mock helper]: " + cancel);
							var jobId = cancel.jobId();
							
							if(jobId != null){
								wire.send(new Wire.CleanupComplete(jobId, null));
							}
						}
						case Wire.CancelAssignment cancelAssignment -> {
							System.out.println("[mock helper] CancelAssignment: " + cancelAssignment);
						}
						default -> throw new IllegalStateException();
					}
				}
			}catch(Exception ignored){ }
		});
	}
}
