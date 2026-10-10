package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.FailOrder;
import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzSequenceSource;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.RunMark;
import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import com.lapissea.fuzz.distributed.Wire;
import com.lapissea.fuzz.distributed.helper.HelperProtocolTest;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class UnifiedRunTest{
	private static RemoteConfig.Host pendingHost(){ return new RemoteConfig.Host("late", "unused", "java", null, 1, 1, 0, null, null); }
	private static Stream<FuzzSequence> failureSequences(){
		return Stream.of(new FuzzSequence(100, 0, 1, 10), new FuzzSequence(200, 1, 2, 10));
	}
	private static FuzzingRunner<Long, Object, Exception> failingRunner(){
		return new FuzzingRunner<>(new FuzzingStateEnv.Marked<Long, Object, Exception>(){
			public Long create(RandomGenerator random, long sequenceIndex, RunMark mark){ return sequenceIndex; }
			public void applyAction(Long sequence, long index, Object action, RunMark mark){
				if(index == (sequence == 0? 108 : 201)){ throw new IllegalStateException("bug"); }
			}
		}, FuzzingRunner::noopAction);
	}
	@Test
	public void failureDelayAndOrderingAreShared(){
		for(var delay : List.of(Duration.ZERO, Duration.ofSeconds(5))){
			var config = new FuzzConfig().dontLog().withMaxWorkers(1).withErrorDelay(delay).withFailOrder(FailOrder.LEAST_ACTION)
			                             .withErrorTracking(new FuzzConfig.ErrorTracking(OptionalInt.empty(), false));
			for(int mode = 0; mode<3; mode++){
				var session = new DistributedSession(mode == 2? List.of(pendingHost()) : List.of());
				try{
					var runner = failingRunner();
					var failures = mode == 0? runner.runLocal(config, RunMark.NONE, UnifiedRunTest::failureSequences) :
					               session.execute(runner, new byte[0], config, RunMark.NONE, UnifiedRunTest::failureSequences);
					assertEquals(failures.size(), delay.isZero()? 1 : 2, "mode=" + mode);
					assertEquals(failures.getFirst().sequence().index(), delay.isZero()? 0L : 1L, "mode=" + mode);
				}finally{ session.close(); }
			}
		}
	}
	@Test
	public void filteringAndRepeatedOccurrencesPreserveIndexes(){
		var calls = new ConcurrentHashMap<Long, AtomicInteger>();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((r, i, m) -> calls.computeIfAbsent(i, k -> new AtomicInteger()).incrementAndGet()),
		                                 FuzzingRunner::noopAction);
		var closes = new AtomicInteger();
		FuzzSequenceSource source = () -> Stream.of(new FuzzSequence(10, 1, 0, 3), new FuzzSequence(100, 2, 0, 2), new FuzzSequence(100, 2, 0, 2))
		                                        .onClose(closes::incrementAndGet);
		assertTrue(runner.runLocal(new FuzzConfig().dontLog().withMaxWorkers(2), new RunMark(2, 100), source).isEmpty());
		assertEquals(calls.size(), 2);
		assertEquals(calls.get(100L).get(), 2);
		assertEquals(calls.get(101L).get(), 2);
		assertEquals(closes.get(), 2);
	}
	@Test
	public void settledEmptyDiscoveryDoesNotCreateRemoteCoordinator() throws Exception{
		var session = new DistributedSession(List.of(pendingHost()));
		assertTrue(session.hasPotentialHelpers());
		session.helperResolved("late");
		assertFalse(session.hasPotentialHelpers());
		var calls  = new AtomicInteger();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((r, i, m) -> calls.incrementAndGet()), FuzzingRunner::noopAction);
		try{
			assertTrue(session.execute(runner, new byte[0], new FuzzConfig().dontLog(), RunMark.NONE,
			                           () -> Stream.of(new FuzzSequence(30, 9, 7, 3))).isEmpty());
			assertEquals(calls.get(), 3);
			var scheduler = DistributedSession.class.getDeclaredField("scheduler");
			scheduler.setAccessible(true);
			assertNull(scheduler.get(session));
		}finally{ session.close(); }
	}
	@Test(timeOut = 10000)
	public void localWorkStartsBeforePendingHelperAndHelperJoinsMidRun() throws Exception{
		var session    = new DistributedSession(List.of(pendingHost()));
		var started    = new CountDownLatch(1);
		var remoteDone = new AtomicInteger();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((r, i, m) -> {
			started.countDown();
			Thread.sleep(10);
		}), FuzzingRunner::noopAction);
		try(var executor = Executors.newSingleThreadExecutor(); var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())){
			var run = executor.submit(() -> session.execute(runner, HelperProtocolTest.recipe("ok"), new FuzzConfig().dontLog().withMaxWorkers(1),
			                                                RunMark.NONE, () -> IntStream.range(0, 20).mapToObj(i -> new FuzzSequence(i*10, i, i, 10))));
			assertTrue(started.await(2, TimeUnit.SECONDS), "Local execution waited for the helper");
			try(var socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort()); var wire = new Wire(socket)){
				session.attachHelper("late", 1, server.accept());
				var helper = Thread.ofVirtual().start(() -> {
					var       queued = new ArrayDeque<Wire.Work>();
					Wire.Work active = null;
					try{
						while(true){
							switch(wire.receive()){
								case Wire.Work work -> queued.add(work);
								case Wire.StartAck ack -> {
									if(ack.allowed()){
										Objects.requireNonNull(active, "Can not ack non existent work");
										wire.send(new Wire.Result(new RemoteSequenceResult(
											active.assignment(), active.sequence(),
											new RemoteSequenceResult.Ok(), RemoteSequenceResult.CapturedOutput.EMPTY,
											new RemoteSequenceResult.ExecutionStats(active.sequence().iterations(), 1000)
										)));
										remoteDone.incrementAndGet();
									}
									active = null;
								}
								case Wire.Cancel cancel -> {
								if(cancel.jobId() != null){
									wire.send(new Wire.CleanupComplete(cancel.jobId(), null));
								}
								return;
							}
								default -> { }
							}
							if(active == null && !queued.isEmpty()){
								active = queued.remove();
								wire.send(new Wire.Start(active.assignment()));
							}
						}
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				});
				assertTrue(run.get(5, TimeUnit.SECONDS).isEmpty());
				assertTrue(remoteDone.get()>0, "Late helper never received work");
				helper.join(1000);
			}
		}finally{ session.close(); }
	}
	@Test(timeOut = 10000)
	public void optedInLocalRunsDoNotSerializeEachOther() throws Exception{
		var session = new DistributedSession(List.of());
		var both    = new CountDownLatch(2);
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((r, i, m) -> {
			both.countDown();
			assertTrue(both.await(2, TimeUnit.SECONDS), "Local runs were serialized by the remote session");
		}), FuzzingRunner::noopAction);
		try(var pool = Executors.newFixedThreadPool(2)){
			var runs = new ArrayList<Future<?>>();
			for(int i = 0; i<2; i++){
				runs.add(pool.submit(() -> assertTrue(session.execute(runner, new byte[0], new FuzzConfig().dontLog(), RunMark.NONE,
				                                                      () -> Stream.of(new FuzzSequence(0, 0, 0, 1))).isEmpty())));
			}
			for(var run : runs){ run.get(5, TimeUnit.SECONDS); }
		}finally{ session.close(); }
	}
}
