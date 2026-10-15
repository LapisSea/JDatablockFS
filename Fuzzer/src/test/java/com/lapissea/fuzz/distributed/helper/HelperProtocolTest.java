package com.lapissea.fuzz.distributed.helper;

import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.distributed.RecipeCodec;
import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import com.lapissea.fuzz.distributed.Wire;
import org.testng.annotations.Test;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public final class HelperProtocolTest{
	
	static FuzzingRunner<?, ?, ?> runner(String mode){
		return new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			System.out.print("helper-output\n");
			if(mode.equals("fail")){ throw new IllegalStateException("remote problem"); }
			if(mode.equals("slow")){ Thread.sleep(60_000); }
		}), FuzzingRunner::noopAction);
	}
	
	public static byte[] recipe(String mode){
		return RecipeCodec.encode(() -> runner(mode));
	}
	
	private static FuzzingRunner<?, ?, ?> lifecycleRunner(){
		var env = FuzzingStateEnv.JustRandom.<Object, Exception>of(
			(random, index, mark) -> { },
			() -> {
				System.out.println("lifecycle-prepare-stdout");
				System.err.println("lifecycle-prepare-stderr");
			},
			() -> {
				System.out.println("lifecycle-cleanup-stdout");
				System.err.println("lifecycle-cleanup-stderr");
			}
		);
		return new FuzzingRunner<>(env, FuzzingRunner::noopAction);
	}
	
	private static byte[] lifecycleRecipe(){ return RecipeCodec.encode(HelperProtocolTest::lifecycleRunner); }
	
	public static Process helperProcess(ServerSocket server, String token) throws Exception{
		String classpath  = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		String modulePath = System.getProperty("jdk.module.path", "");
		if(!modulePath.isEmpty()){ classpath += File.pathSeparator + modulePath; }
		return new ProcessBuilder(
			Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-Dfuzz.workerDiagnostics=true", "-Dfuzz.silenceTimeoutSeconds=5", "-cp", classpath,
			HelperMain.class.getName(), "127.0.0.1", Integer.toString(server.getLocalPort()), token, "test-helper",
			"1", "test-bundle"
		).redirectError(ProcessBuilder.Redirect.INHERIT)
		 .start();
	}
	
	@Test(timeOut = 7000)
	public void helperExitsWhenMasterGoesSilent() throws Exception{
		try(var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))){
			server.setSoTimeout(1000);
			
			var process = helperProcess(server, UUID.randomUUID().toString());
			try(var wire = new Wire(server.accept())){
				wire.socket.setSoTimeout(1000);
				
				assertTrue(wire.receive() instanceof Wire.Hello);
				wire.send(new Wire.Ping());
				assertEquals(wire.receive(), new Wire.Ping());
				
				// Keep the transport open: EOF must not be what triggers this shutdown
				assertTrue(process.waitFor(6, TimeUnit.SECONDS), "Silent master left its helper alive");
				assertEquals(process.exitValue(), 2);
			}finally{
				process.destroyForcibly();
				process.waitFor(5*1000/10, TimeUnit.MILLISECONDS);
			}
		}
	}
	
	@Test(timeOut = 30000)
	public void helperReportsFailureOutputAndCancellation() throws Exception{
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())){
			server.setSoTimeout(10000);
			
			String token   = UUID.randomUUID().toString();
			var    process = helperProcess(server, token);
			
			try(var wire = new Wire(server.accept())){
				wire.socket.setSoTimeout(10000);
				
				assertEquals(wire.receive(), new Wire.Hello(
					Wire.VERSION, token, "test-helper", 1, "test-bundle",
					Runtime.version().feature())
				);
				var job = UUID.randomUUID();
				job(wire, "fail");
				work(wire, job, 1, new FuzzSequence(17, 2, 9, 1));
				
				boolean              output  = false;
				RemoteSequenceResult failure = null;
				while(failure == null){
					var frame = wire.receive();
					switch(frame){
						case Wire.Demand ignored -> { }
						case Wire.Start message -> ack(wire, message);
						case Wire.Output message -> {
							assertFalse(message.error());
							output |= new String(message.bytes(), StandardCharsets.UTF_8).contains(
								"helper-output");
						}
						case Wire.Progress ignored -> { }
						case Wire.Result message -> failure = message.result();
						default -> fail("Unexpected message");
					}
				}
				
				assertTrue(output);
				assertEquals(failure.sequence(), new FuzzSequence(17, 2, 9, 1));
				assertTrue(failure.status() instanceof RemoteSequenceResult.Failed);
				
				var detail = ((RemoteSequenceResult.Failed)failure.status()).failure();
				assertEquals(detail.actionIndex(), 17L);
				assertTrue(detail.stackTrace().contains("remote problem"));
				
				assertEquals(failure.stats().completedOperations(), 0L);
				assertTrue(failure.stats().executionNanos()>0);
				
				// Leave the helper without work: it must complain, with this job's identity.
				assertEquals(wire.receive(), new Wire.Demand(job, 1, 0));
				job(wire, "ok");
				work(wire, job, 3, new FuzzSequence(0, 4, 11, 3));
				
				RemoteSequenceResult success       = null;
				long[]               lastProgress  = {-1, -1};
				boolean              firstProgress = false;
				while(success == null){
					var frame = wire.receive();
					switch(frame){
						case Wire.Demand ignored -> { }
						case Wire.Start message -> ack(wire, message);
						case Wire.Output ignored -> { }
						case Wire.Progress message -> {
							assertEquals(message.assignment().job(), job);
							assertEquals(message.assignment().id(), 3L);
							long ops = message.stats().completedOperations(), nanos = message.stats().executionNanos();
							if(!firstProgress){
								assertEquals(ops, 1L);
								firstProgress = true;
							}
							assertTrue(ops>=lastProgress[0]);
							assertTrue(nanos>=lastProgress[1]);
							lastProgress[0] = ops;
							lastProgress[1] = nanos;
						}
						case Wire.Result message -> success = message.result();
						default -> fail("Unexpected message");
					}
				}
				
				assertTrue(success.status() instanceof RemoteSequenceResult.Ok);
				assertEquals(success.stats().completedOperations(), 3L);
				assertTrue(success.stats().executionNanos()>0);
				
				assertTrue(firstProgress);
				
				assertEquals(success.stats().completedOperations(), lastProgress[0]);
				assertEquals(success.stats().executionNanos(), lastProgress[1]);
				
				for(boolean assignmentCancellation : new boolean[]{false, true}){
					job(wire, "slow");
					work(wire, job, 2, new FuzzSequence(0, 3, 10, 100000));
					boolean cancelled = false;
					while(!cancelled){
						var frame = wire.receive();
						switch(frame){
							case Wire.Start message -> {
								ack(wire, message);
							}
							case Wire.Output ignored -> wire.send(
								assignmentCancellation? new Wire.CancelAssignment(new Wire.Assignment(job, 2)) : new Wire.Cancel()
							);
							case Wire.Progress ignored -> { }
							case Wire.Demand ignored -> { }
							case Wire.Result message -> {
								var result = message.result();
								assertTrue(result.status() instanceof RemoteSequenceResult.Cancelled);
								assertTrue(result.stats().completedOperations()<100000);
								assertTrue(result.stats().executionNanos()>0);
								cancelled = true;
							}
							default -> fail("Unexpected message");
						}
					}
				}
			}finally{
				process.destroy();
				if(!process.waitFor(5, TimeUnit.SECONDS)){ process.destroyForcibly(); }
			}
		}
	}
	
	@Test(timeOut = 15000)
	public void helperSendsLifecycleOutputToMaster() throws Exception{
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())){
			server.setSoTimeout(10000);
			var process = helperProcess(server, UUID.randomUUID().toString());
			try(var wire = new Wire(server.accept())){
				wire.socket.setSoTimeout(10000);
				
				assertTrue(wire.receive() instanceof Wire.Hello);
				var job = UUID.randomUUID();
				wire.send(new Wire.Job(lifecycleRecipe()));
				var setup = readOutputUntil(wire, "lifecycle-prepare-stdout", "lifecycle-prepare-stderr");
				assertTrue(setup.stdout().contains("lifecycle-prepare-stdout"));
				assertTrue(setup.stderr().contains("lifecycle-prepare-stderr"));
				
				wire.send(new Wire.Cancel());
				var cleanup = readOutputUntil(wire, "lifecycle-cleanup-stdout", "lifecycle-cleanup-stderr");
				assertTrue(cleanup.stdout().contains("lifecycle-cleanup-stdout"));
				assertTrue(cleanup.stderr().contains("lifecycle-cleanup-stderr"));
			}finally{
				process.destroy();
				if(!process.waitFor(5, TimeUnit.SECONDS)){ process.destroyForcibly(); }
			}
		}
	}
	
	private static RemoteSequenceResult.CapturedOutput readOutputUntil(Wire wire, String stdoutMarker, String stderrMarker) throws Exception{
		var stdout = new StringBuilder();
		var stderr = new StringBuilder();
		while(!stdout.toString().contains(stdoutMarker) || !stderr.toString().contains(stderrMarker)){
			switch(wire.receive()){
				case Wire.Output output -> (output.error()? stderr : stdout).append(new String(output.bytes(), StandardCharsets.UTF_8));
				case Wire.Demand ignored -> { }
				default -> fail("Unexpected helper message while waiting for lifecycle output");
			}
		}
		return new RemoteSequenceResult.CapturedOutput(stdout.toString(), stderr.toString());
	}
	
	@Test(timeOut = 10000)
	public void revokedQueuedTasksAreRemovedBeforeWorkerBecomesAvailable() throws Exception{
		var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
		                                  new LinkedBlockingQueue<>());
		var entered  = new CountDownLatch(1);
		var release  = new CountDownLatch(1);
		var executed = new AtomicInteger();
		var retired  = new AtomicInteger();
		try{
			pool.submit(() -> {
				entered.countDown();
				try{
					release.await();
				}catch(InterruptedException e){
					Thread.currentThread().interrupt();
				}
			});
			
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			
			for(int i = 0; i<1000; i++){
				var revoked = new AtomicBoolean();
				var task = new HelperMain.WorkTask(revoked, executed::incrementAndGet){
					@Override
					protected void done(){ retired.incrementAndGet(); }
				};
				pool.execute(task);
				task.revoke(pool);
				assertTrue(revoked.get());
				assertTrue(task.isCancelled());
				assertTrue(pool.getQueue().isEmpty());
			}
			assertEquals(retired.get(), 1000);
			release.countDown();
			pool.submit(() -> { }).get(5, TimeUnit.SECONDS);
			assertEquals(executed.get(), 0);
		}finally{
			release.countDown();
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
		}
	}
	
	@Test(timeOut = 15000)
	public void failedJobReplacementReportsCleanupForActiveJob() throws Exception{
		try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())){
			server.setSoTimeout(10000);
			var process = helperProcess(server, UUID.randomUUID().toString());
			try(var wire = new Wire(server.accept())){
				wire.socket.setSoTimeout(10000);
				
				assertTrue(wire.receive() instanceof Wire.Hello);
				
				var job = UUID.randomUUID();
				job(wire, "ok");
				work(wire, job, 1, new FuzzSequence(0, 0, 1, 1));
				
				boolean result = false;
				while(!result){
					switch(wire.receive()){
						case Wire.Start message -> ack(wire, message);
						case Wire.Output ignored -> { }
						case Wire.Progress ignored -> { }
						case Wire.Demand ignored -> { }
						case Wire.Result ignored -> result = true;
						default -> fail("Unexpected message");
					}
				}
				
				// A broken replacement recipe must be reported for the last job the master saw, rather than
				// the helper dying silently and letting the master's cleanup ack time out.
				wire.send(new Wire.Job(new byte[]{0, 0, 0, 0}));
				boolean reported = false;
				try{
					while(true){
						if(wire.receive() instanceof Wire.CleanupComplete cleanup){
							assertEquals(cleanup.jobId(), job);
							assertNotNull(cleanup.failure());
							reported = true;
						}
					}
				}catch(Exception expectedClose){ }
				assertTrue(reported, "Helper must report the failed job replacement before dying");
			}finally{
				process.destroy();
				if(!process.waitFor(5, TimeUnit.SECONDS)){ process.destroyForcibly(); }
			}
		}
	}
	
	private static void job(Wire wire, String mode) throws Exception{
		wire.send(new Wire.Job(recipe(mode)));
	}
	
	private static void work(Wire wire, UUID job, long id, FuzzSequence sequence) throws Exception{
		wire.send(new Wire.Work(new Wire.Assignment(job, id), sequence));
	}
	
	private static void ack(Wire wire, Wire.Start start) throws Exception{
		wire.send(new Wire.StartAck(start.assignment(), true));
	}
}
