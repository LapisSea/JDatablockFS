package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzFail;
import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.RunMark;
import com.lapissea.fuzz.SequenceOutcome;
import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import com.lapissea.fuzz.distributed.Wire;
import com.lapissea.fuzz.distributed.bootstrap.RuntimeBundle;
import com.lapissea.fuzz.distributed.bootstrap.SshWorkerLauncher;
import com.lapissea.fuzz.distributed.helper.HelperMain;
import com.lapissea.fuzz.internal.RunCoordinator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DistributedSession{
	private static final AtomicBoolean preloadRequested = new AtomicBoolean();
	
	/**
	 * Call this as soon as possible if fuzzing will be done. Will start the connections to helper machines or determine that there is none
	 */
	public static void preload(){
		if(preloadRequested.compareAndSet(false, true)){
			Thread.ofPlatform().name("fuzz-preload").start(() -> {
				var dummy = Holder.INSTANCE;
			});
		}
	}
	
	private static final class Holder{
		static final DistributedSession INSTANCE = new DistributedSession();
	}
	
	private final UUID sessionId = UUID.randomUUID();
	
	private final List<RemoteConfig.Host>              hosts;
	private final Map<String, RemoteHelper>            helpers        = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Void>> pendingHelpers = new ConcurrentHashMap<>();
	
	private final Object lifecycleLock = new Object();
	private final Object runLock       = new Object();
	
	private final    List<AutoCloseable> resources = new CopyOnWriteArrayList<>();
	private volatile boolean             closed;
	private volatile String              bundleId;
	
	private volatile DistributedScheduler scheduler;
	
	private record Event(
		String worker, Wire.Assignment assignment, RemoteSequenceResult result, boolean disconnected,
		RemoteSequenceResult.ExecutionStats timing, Demand demand
	){
		Event(String worker, Wire.Assignment assignment, RemoteSequenceResult result, boolean disconnected,
		      RemoteSequenceResult.ExecutionStats timing){
			this(worker, assignment, result, disconnected, timing, null);
		}
		Event(String worker, Wire.Assignment assignment, RemoteSequenceResult result, boolean disconnected){
			this(worker, assignment, result, disconnected, null);
		}
	}
	
	private record Demand(int idle, int awaitingStart){ }
	
	private record ReplayTask<S, A>(DistributedScheduler.Replay replay, Future<SequenceOutcome<S, A>> future){ }
	
	private volatile Runnable wake = () -> { };
	
	private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
	
	/**
	 * Serializes master->helper writes so a helper that stops draining its socket cannot block the
	 * coordinator thread: each send is bounded by {@code SEND_TIMEOUT_NANOS}.
	 */
	private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(
		Thread.ofVirtual().name("fuzz-master-send").factory());
	
	private static final class OutputDecoder{
		final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
		                                                     .onMalformedInput(CodingErrorAction.REPLACE)
		                                                     .onUnmappableCharacter(CodingErrorAction.REPLACE);
		
		byte[] remaining = new byte[0];
		
		String decode(byte[] bytes){
			var input = ByteBuffer.allocate(remaining.length + bytes.length);
			input.put(remaining).put(bytes).flip();
			
			var chars = CharBuffer.allocate(input.remaining() + 1);
			decoder.decode(input, chars, false);
			
			remaining = new byte[input.remaining()];
			input.get(remaining);
			
			return chars.flip().toString();
		}
	}
	
	/**
	 * Groups a helper's decoders, buffered output and cleanup-ack state on the master.
	 */
	static final class RemoteHelper{
		
		final OutputDecoder outDecoder = new OutputDecoder();
		final OutputDecoder errDecoder = new OutputDecoder();
		final StringBuilder stdout     = new StringBuilder();
		final StringBuilder stderr     = new StringBuilder();
		
		final String name;
		final int    concurrency;
		final Wire   wire;
		
		boolean connectionAnnounced;
		volatile UUID      job;
		final    Set<Long> assignments = new HashSet<>();
		
		private UUID                    cleanupJob;
		private CompletableFuture<Void> cleanupAck;
		
		RemoteHelper(String name, int concurrency, Wire wire){
			this.name = name;
			this.concurrency = concurrency;
			this.wire = wire;
		}
		
		synchronized void log(boolean error, byte[] bytes){ log(error, (error? errDecoder : outDecoder).decode(bytes)); }
		synchronized void log(boolean error, String text){
			final String gray  = "\033[0;90m";
			final String reset = "\033[0m";
			(error? System.err : System.out).println(
				text.lines().map(l -> gray + "[" + name + "] " + reset + l).collect(Collectors.joining("\n"))
			);
			(error? stderr : stdout).append(text);
		}
		
		/**
		 * Returns the output buffered since the previous consume and clears it, so each result carries only
		 * its own output instead of every result carrying all output since the job started.
		 */
		synchronized RemoteSequenceResult.CapturedOutput consumeOutput(){
			var output = new RemoteSequenceResult.CapturedOutput(stdout.toString(), stderr.toString());
			stdout.setLength(0);
			stderr.setLength(0);
			return output;
		}
		/**
		 * @return the ack to wait on for the given job. If a cleanup from a previous job is still pending (that run's
		 * close already gave up on it) it is replaced, since no one is waiting on that ack any more.
		 */
		synchronized CompletableFuture<Void> expectCleanup(UUID job){
			if(cleanupJob == null || !cleanupJob.equals(job)){
				cleanupJob = job;
				cleanupAck = new CompletableFuture<>();
			}
			return cleanupAck;
		}
		synchronized void cleanupComplete(Wire.CleanupComplete message){
			if(cleanupAck != null && Objects.equals(cleanupJob, message.jobId())){
				var ack = cleanupAck;
				cleanupJob = null;
				cleanupAck = null;
				if(message.failure() == null){
					ack.complete(null);
				}else{
					ack.completeExceptionally(new IllegalStateException("Helper cleanup failed: " + message.failure()));
				}
			}
			// A CleanupComplete for a job that is not (or no longer) pending is stale and is ignored.
		}
		synchronized void cleanupFailed(Throwable failure){
			if(cleanupAck != null){
				var ack = cleanupAck;
				cleanupJob = null;
				cleanupAck = null;
				ack.completeExceptionally(failure);
			}
		}
	}
	
	private DistributedSession(){
		RemoteConfig.ConfigResult res;
		try{
			res = RemoteConfig.load();
		}catch(Exception e){
			System.err.println("Distributed fuzzer configuration unavailable: " + e);
			res = new RemoteConfig.ConfigResult(new RemoteConfig(List.of()), null);
		}
		
		var configured = res.config().hosts();
		System.out.println(
			"Found configured host" + (configured.size() == 1? "" : "s") + ": " +
			configured.stream().map(RemoteConfig.Host::name).collect(Collectors.joining(", ")) +
			(res.path() != null? "\n  Config file: " + res.path() : "")
		);
		
		hosts = List.copyOf(configured);
		initPendingHelpers();
		if(!hosts.isEmpty()){
			Thread.ofVirtual().name("fuzz-prepare").start(this::prepare);
			Runtime.getRuntime().addShutdownHook(new Thread(this::close, "fuzz-helper-cleanup"));
		}
	}
	DistributedSession(List<RemoteConfig.Host> hosts){
		this.hosts = List.copyOf(hosts);
		initPendingHelpers();
	}
	private void initPendingHelpers(){
		hosts.forEach(host -> pendingHelpers.put(host.name(), new CompletableFuture<>()));
	}
	
	boolean hasPotentialHelpers(){
		synchronized(lifecycleLock){ return !closed && (!pendingHelpers.isEmpty() || !helpers.isEmpty()); }
	}
	void helperResolved(String name){
		synchronized(lifecycleLock){
			var pending = pendingHelpers.remove(name);
			if(pending != null){ pending.complete(null); }
		}
		wake.run();
	}
	void attachHelper(String name, int concurrency, Socket socket) throws IOException{
		var helper = new RemoteHelper(name, concurrency, new Wire(socket));
		synchronized(lifecycleLock){
			if(closed || helpers.putIfAbsent(name, helper) != null){
				throw new IOException("Helper already attached or session closed: " + name);
			}
		}
		helperResolved(name);
		registerResource(helper.wire);
		CompletableFuture.runAsync(
			() -> read(helper),
			Thread.ofVirtual().name("fuzz-test-helper-reader")::start
		);
	}
	private void prepare(){
		if(closed){ return; }
		try{
			var server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
			acceptConnections(server);
			var bundle = RuntimeBundle.capture();
			registerResource(bundle);
			if(closed){ return; }
			bundleId = bundle.id();
			for(var host : hosts){
				Thread.ofVirtual().name("fuzz-launch-" + host.name()).start(() -> {
					if(closed) return;
					try{
						var process = SshWorkerLauncher.launch(
							host, bundle, server.getLocalPort(), HelperMain.class.getName(),
							List.of("127.0.0.1", Integer.toString(host.tunnelPort()), sessionId.toString(),
							        host.name(), Integer.toString(host.parallelism()), bundle.id())
						);
						
						registerResource(process);
						process.process().onExit().thenRun(() -> helperResolved(host.name()));
						
						Thread.ofVirtual().name("fuzz-drain-" + host.name()).start(() -> {
							drain(process.process().getInputStream(), System.out, host.name());
						});
						
						var pending = pendingHelpers.get(host.name());
						if(pending != null){
							try{
								pending.get(60, TimeUnit.SECONDS);
							}catch(TimeoutException timeout){
								boolean expired;
								synchronized(lifecycleLock){ expired = pendingHelpers.remove(host.name(), pending); }
								if(expired){
									pending.complete(null);
									process.close();
									wake.run();
									System.err.println("Fuzzer helper " + host.name() + " did not complete its handshake");
								}
							}
						}
					}catch(Exception e){
						helperResolved(host.name());
						// Configuration problems carry their own explanation; an unexpected failure
						// keeps its class name, which is the part worth reading there.
						System.err.println("Fuzzer helper \"" + host.name() + "\" unavailable for this session:\n  " +
						                   (e instanceof IOException? e.getMessage() : e));
					}
				});
			}
		}catch(Exception e){
			pendingHelpers.keySet().forEach(this::helperResolved);
			System.err.println("Distributed fuzzer preparation failed: " + e);
		}
	}
	void acceptConnections(ServerSocket server){
		registerResource(server);
		Thread.ofVirtual().name("fuzz-accept").start(() -> {
			while(!closed){
				try{
					var socket = server.accept();
					registerResource(socket);
					Thread.ofVirtual().name("fuzz-handshake").start(() -> handshake(socket));
				}catch(IOException e){
					if(!closed){ System.err.println("Fuzzer helper connection: " + e); }
				}
			}
		});
	}
	private void handshake(Socket socket){
		boolean transferred = false;
		try{
			socket.setSoTimeout(15000);
			var wire = new Wire(socket);
			
			if(!(wire.receive() instanceof Wire.Hello(
				int version, String token, String name, int concurrency, String remoteBundle, int javaFeature
			))){
				System.err.println("[fuzz] Child did not greet with Hello");
				wire.close();
				return;
			}
			if(version != Wire.VERSION){
				System.err.println("[fuzz] Child '" + name + "' handshake: invalid Wire version");
				wire.close();
				return;
			}
			if(!token.equals(sessionId.toString())){
				System.err.println("[fuzz] Child '" + name + "' handshake: session ID mismatch");
				wire.close();
				return;
			}
			
			var hostO = hosts.stream().filter(h -> h.name().equals(name)).findFirst();
			if(hostO.isEmpty()){
				System.err.println("[fuzz] Child '" + name + "' handshake: not recognised by name");
				wire.close();
				return;
			}
			var host = hostO.get();
			
			if(!pendingHelpers.containsKey(name)){
				System.err.println("[fuzz] Child '" + name + "' handshake: not a pending helper");
				wire.close();
				return;
			}
			if(!Objects.equals(bundleId, remoteBundle)){
				System.err.println("[fuzz] Child '" + name + "' handshake: bundle mismatch");
				wire.close();
				return;
			}
			if(javaFeature<21){
				System.err.println("[fuzz] Child '" + name + "' handshake: illegal Java version (" + javaFeature + ")");
				wire.close();
				return;
			}
			if(concurrency<1 || concurrency>65536 || (host.parallelism() != 0 && concurrency != host.parallelism())){
				System.err.println("[fuzz] Child '" + name + "' handshake: illegal concurrency (" + concurrency + ")");
				wire.close();
				return;
			}
			
			socket.setSoTimeout(Wire.SILENCE_TIMEOUT_SECONDS*1000);
			
			var helper = new RemoteHelper(name, concurrency, wire);
			synchronized(lifecycleLock){
				if(closed || !pendingHelpers.containsKey(name) || helpers.putIfAbsent(name, helper) != null){
					return;
				}
				helperResolved(name);
			}
			registerResource(wire);
			
			Thread.ofVirtual().name("fuzz-read-" + name).start(() -> read(helper));
			Thread.ofVirtual().name("fuzz-heartbeat-" + name).start(() -> {
				while(!closed && helpers.get(name) == helper){
					try{
						helper.wire.send(new Wire.Ping());
						TimeUnit.SECONDS.sleep(Wire.HEARTBEAT_SECONDS);
					}catch(Exception e){
						quietClose(helper.wire);
						break;
					}
				}
			});
			transferred = true;
		}catch(Exception e){
			if(!closed){
				new RuntimeException("Fuzzer helper handshake unexpected close", e).printStackTrace();
			}
		}finally{
			resources.remove(socket);
			if(!transferred){ quietClose(socket); }
		}
	}
	
	private static void drain(InputStream in, PrintStream out, String name){
		try(var reader = new BufferedReader(new InputStreamReader(in))){
			String line;
			while((line = reader.readLine()) != null){ out.println("[fuzz SSH " + name + "] " + line); }
		}catch(IOException ignored){ }
	}
	private void read(RemoteHelper remoteHelper){
		try{
			while(!closed){
				var frame = remoteHelper.wire.receive();
				switch(frame){
					case Wire.Start(var assignment) -> {
						events.add(new Event(remoteHelper.name, assignment, null, false));
					}
					case Wire.Result(var result) -> {
						result = new RemoteSequenceResult(result.assignment(), result.sequence(), result.status(), remoteHelper.consumeOutput(), result.stats());
						events.add(new Event(remoteHelper.name, result.assignment(), result, false));
					}
					case Wire.Progress progress -> {
						events.add(new Event(remoteHelper.name, progress.assignment(), null, false, progress.stats()));
					}
					case Wire.Demand(UUID jobId, int idle, int awaitingStart) -> {
						if(idle<0 || idle>remoteHelper.concurrency || awaitingStart<0 || awaitingStart>remoteHelper.concurrency){
							throw new IOException("Invalid worker demand");
						}
						var assignment = new Wire.Assignment(jobId, 0);
						events.add(new Event(remoteHelper.name, assignment, null, false, null, new Demand(idle, awaitingStart)));
					}
					case Wire.Output(var error, var bytes) -> remoteHelper.log(error, bytes);
					case Wire.CleanupComplete message -> remoteHelper.cleanupComplete(message);
					case Wire.Ping() -> {
						var s = scheduler;
						if(s != null && remoteHelper.job != null){
							try{
								s.heartbeat(remoteHelper.name);
							}catch(IllegalArgumentException ignored){ }
						}
					}
					default -> throw new IOException("Unknown helper message");
				}
				wake.run();
			}
		}catch(Exception e){
			remoteHelper.cleanupFailed(e);
			helpers.remove(remoteHelper.name, remoteHelper);
			quietClose(remoteHelper.wire);
			events.add(new Event(remoteHelper.name, new Wire.Assignment(remoteHelper.job, 0), null, true));
			wake.run();
			if(!closed){ System.err.println("Fuzzer helper " + remoteHelper.name + " disconnected: " + e); }
		}
	}
	public static Optional<DistributedScheduler.Snapshot> snapshot(){
		return Optional.ofNullable(Holder.INSTANCE.scheduler).map(DistributedScheduler::snapshot);
	}
	public static UUID sessionId(){ return Holder.INSTANCE.sessionId; }
	public static <S, A, E extends Throwable> List<FuzzFail<S, A>> run(FuzzingRunner<S, A, E> runner, byte[] recipe, FuzzConfig config, RunMark mark,
	                                                                   Supplier<Stream<FuzzSequence>> sequences){
		return Holder.INSTANCE.execute(runner, recipe, config == null? new FuzzConfig() : config, mark, sequences);
	}
	<S, A, E extends Throwable> List<FuzzFail<S, A>> execute(
		FuzzingRunner<S, A, E> runner, byte[] recipe, FuzzConfig config, RunMark mark, Supplier<Stream<FuzzSequence>> sequences
	){
		return runner.runWithLifecycle(() -> {
			if(!hasPotentialHelpers()){
				return RunCoordinator.run(runner, config, mark, sequences::get, null);
			}
			synchronized(runLock){
				return RunCoordinator.run(runner, config, mark, sequences::get, (context, source) -> {
					if(!hasPotentialHelpers()){ return null; }
					return new RemoteRun<>(runner, recipe, config, context, source);
				});
			}
		});
	}
	
	/**
	 * Remote leases and replay are attached to the common run only while helpers may participate
	 */
	private final class RemoteRun<S, A, E extends Throwable> implements RunCoordinator.Workers<S, A>{
		
		private final FuzzingRunner<S, A, E> runner;
		private final byte[]                 recipe;
		private final FuzzConfig             config;
		
		private final RunCoordinator.Context<S, A> context;
		private final DistributedScheduler         scheduler;
		
		private final UUID job = UUID.randomUUID();
		
		private final Set<String>                            registered     = new HashSet<>();
		private final Deque<DistributedScheduler.Assignment> localQueue     = new ArrayDeque<>();
		private final AttemptTiming                          timings        = new AttemptTiming();
		private final List<ReplayTask<S, A>>                 pendingReplays = new ArrayList<>();
		private final List<String>                           unreproduced   = new ArrayList<>();
		
		private final Map<RemoteHelper, CompletableFuture<Void>> cleanupAcks = new IdentityHashMap<>();
		private final Set<RemoteHelper>                          cleanupSent = Collections.newSetFromMap(new IdentityHashMap<>());
		
		private ExecutorService replayPool;
		private boolean         cancelled;
		
		RemoteRun(
			FuzzingRunner<S, A, E> runner, byte[] recipe, FuzzConfig config,
			RunCoordinator.Context<S, A> context, Iterator<FuzzSequence> source
		){
			this.runner = runner;
			this.recipe = recipe;
			this.config = config;
			this.context = context;
			
			scheduler = new DistributedScheduler(job, source);
			DistributedSession.this.scheduler = scheduler;
			
			wake = context::wake;
			scheduler.registerWorker("local", config.maxWorkers(), config.maxWorkers());
			registered.add("local");
		}
		
		public RunCoordinator.Work claimLocal(){
			if(cancelled){ return null; }
			localQueue.addAll(scheduler.fillQueue("local"));
			while(!localQueue.isEmpty()){
				var assignment = localQueue.peek();
				if(!scheduler.assignmentActive("local", assignment.id())){
					localQueue.remove();
					continue;
				}
				if(!scheduler.start("local", assignment.id())){ return null; }
				localQueue.remove();
				return new RunCoordinator.Work(
					assignment.id(), assignment.sequence(), scheduler.cancellationSignal("local", assignment.id())
				);
			}
			return null;
		}
		
		public void progress(RunCoordinator.Work work, FuzzingRunner.SequenceProgress sample){
			events.add(new Event(
				"local", new Wire.Assignment(job, work.id()), null, false,
				RemoteSequenceResult.ExecutionStats.of(sample)
			));
			context.wake();
		}
		
		public boolean completeLocal(RunCoordinator.Work work, SequenceOutcome<S, A> outcome, FuzzingRunner.SequenceProgress sample){
			var result = RemoteSequenceResult.of(
				new Wire.Assignment(job, work.id()), work.sequence(), outcome, RemoteSequenceResult.ExecutionStats.of(sample)
			);
			return finish("local", result, false) == DistributedScheduler.Resolution.ACCEPTED;
		}
		
		private DistributedScheduler.Resolution finish(String worker, RemoteSequenceResult result, boolean replay){
			long before     = scheduler.completedOperations();
			var  resolution = scheduler.complete(worker, result, replay);
			
			if(resolution != DistributedScheduler.Resolution.IGNORED){
				var delta = timings.finish(worker, result.assignment().id(), result.stats());
				context.progress().reportWorkerTiming(worker, delta.completedOperations(), delta.executionNanos());
			}
			
			timings.discardInactive(scheduler::assignmentActive);
			context.progress().reportResolvedSteps(scheduler.completedOperations() - before);
			
			if(resolution == DistributedScheduler.Resolution.ACCEPTED && result.status() instanceof RemoteSequenceResult.Failed){
				context.failureObserved();
			}
			return resolution;
		}
		
		@Override
		public void tick() throws InterruptedException{
			if(closed){
				throw new CancellationException("Master cancelled");
			}
			if(!cancelled && !context.deadlineReached()){
				for(var helper : helpers.values()){
					if(!registered.add(helper.name)){ continue; }
					scheduler.registerWorker(helper.name, helper.concurrency, helper.concurrency*2);
					helper.job = job;
					if(config.shouldLog() && !helper.connectionAnnounced){
						context.progress().reportWorkerConnected(helper.name);
						helper.connectionAnnounced = true;
					}
					context.progress().reportWorkerTiming(helper.name, 0, 0);
					helper.assignments.clear();
					synchronized(helper){
						helper.stdout.setLength(0);
						helper.stderr.setLength(0);
					}
					send(helper, new Wire.Job(recipe));
				}
			}
			// Bound each drain so continuous telemetry cannot starve dispatch or the common error deadline.
			for(int i = 0; i<1024 && !context.deadlineReached(); i++){
				var event = events.poll();
				if(event == null){ break; }
				if(event.disconnected()){
					if(registered.contains(event.worker())){ scheduler.disconnect(event.worker()); }
					timings.discardInactive(scheduler::assignmentActive);
					continue;
				}
				if(!job.equals(event.assignment.job())){
					if(event.result() == null && event.timing() == null && event.demand() == null){
						var helper = helpers.get(event.worker());
						if(helper != null){ send(helper, new Wire.StartAck(event.assignment(), false)); }
					}
					continue;
				}
				if(event.demand() != null){
					scheduler.demand(event.worker(), event.demand().idle(), event.demand().awaitingStart());
				}else if(event.timing() != null){
					if(scheduler.acceptsProgress(event.worker(), event.assignment.id(), event.timing().completedOperations())){
						var delta = timings.accept(event.worker(), event.assignment.id(), event.timing());
						context.progress().reportWorkerTiming(event.worker(), delta.completedOperations(), delta.executionNanos());
						context.progress().reportResolvedSteps(scheduler.reportProgress(event.worker(), event.assignment.id(), event.timing().completedOperations()));
					}
				}else if(event.result() != null){ finish(event.worker(), event.result(), true); }else{
					boolean allowed = scheduler.start(event.worker(), event.assignment.id());
					var     helper  = helpers.get(event.worker());
					if(helper != null){
						send(helper, new Wire.StartAck(event.assignment, allowed));
					}
				}
			}
			Optional<DistributedScheduler.Replay> replay;
			while((replay = scheduler.takeReplay()).isPresent()){
				var r       = replay.get();
				var result  = r.result();
				var failure = ((RemoteSequenceResult.Failed)result.status()).failure();
				if(replayPool == null){ replayPool = Executors.newSingleThreadExecutor(); }
				pendingReplays.add(new ReplayTask<>(r, replayPool.submit(() -> runner.executeSequence(
					new RunMark(result.sequence().index(), failure.actionIndex()), result.sequence(), () -> closed || Thread.currentThread().isInterrupted()))));
			}
			for(var it = pendingReplays.iterator(); it.hasNext(); ){
				var task = it.next();
				if(!task.future().isDone()){ continue; }
				SequenceOutcome<S, A> outcome;
				try{ outcome = task.future().get(); }catch(ExecutionException e){
					throw new IllegalStateException("Master replay failed to execute", e.getCause());
				}
				if(outcome instanceof SequenceOutcome.Cancelled<S, A>){ throw new CancellationException("Master replay cancelled"); }
				if(outcome instanceof SequenceOutcome.Failed<S, A> failure){ context.retainFailure(failure.failure()); }else{
					var r      = task.replay();
					var result = r.result();
					unreproduced.add("Remote failure on " + r.workerId() + " did not reproduce on master: " + result.sequence() + "\n" +
					                 ((RemoteSequenceResult.Failed)result.status()).failure().stackTrace() + "\n" + result.output());
				}
				it.remove();
			}
			if(!cancelled && !context.deadlineReached()){
				for(var helper : helpers.values()){
					if(!registered.contains(helper.name)){ continue; }
					for(var it = helper.assignments.iterator(); it.hasNext(); ){
						long id = it.next();
						if(!scheduler.assignmentActive(helper.name, id)){
							send(helper, new Wire.CancelAssignment(new Wire.Assignment(job, id)));
							it.remove();
						}
					}
					for(var assignment : scheduler.fillQueue(helper.name)){
						helper.assignments.add(assignment.id());
						if(!send(helper, new Wire.Work(new Wire.Assignment(job, assignment.id()), assignment.sequence()))){ break; }
					}
				}
			}
		}
		private static final long SEND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
		
		private boolean send(RemoteHelper remoteHelper, Wire.Message message){
			Future<?> task;
			try{
				task = sendExecutor.submit(() -> {
					remoteHelper.wire.send(message);
					return null;
				});
			}catch(RejectedExecutionException sessionClosing){
				dropHelper(remoteHelper);
				return false;
			}
			try{
				task.get(SEND_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
				return true;
			}catch(TimeoutException e){
				// A helper that is not draining its socket must never block the coordinator forever: closing
				// the wire unblocks the stuck write, and the read thread reports the disconnect.
				dropHelper(remoteHelper);
				task.cancel(true);
				return false;
			}catch(ExecutionException e){
				dropHelper(remoteHelper);
				return false;
			}catch(InterruptedException e){
				Thread.currentThread().interrupt();
				task.cancel(true);
				return false;
			}
		}
		private void dropHelper(RemoteHelper remoteHelper){
			if(registered.contains(remoteHelper.name)){ scheduler.disconnect(remoteHelper.name); }
			helpers.remove(remoteHelper.name, remoteHelper);
			quietClose(remoteHelper.wire);
		}
		public boolean isComplete(){
			if(!scheduler.isComplete() || !pendingReplays.isEmpty()){ return false; }
			if(!unreproduced.isEmpty()){ throw new AssertionError(String.join("\n", unreproduced)); }
			return true;
		}
		public void cancel(){
			if(cancelled){ return; }
			cancelled = true;
			scheduler.cancel();
			for(var helper : helpers.values()){
				if(job.equals(helper.job)){
					requestCleanup(helper);
				}
			}
		}
		private void requestCleanup(RemoteHelper remoteHelper){
			cleanupAcks.put(remoteHelper, remoteHelper.expectCleanup(job));
			if(cleanupSent.add(remoteHelper) && !send(remoteHelper, new Wire.Cancel(job))){
				remoteHelper.cleanupFailed(new IOException("Failed to send cleanup request to " + remoteHelper.name));
			}
		}
		public void close(){
			try{
				closeRun();
			}finally{
				// The session outlives the run; do not keep reporting this finished run's scheduler.
				DistributedSession.this.scheduler = null;
			}
		}
		private void closeRun(){
			wake = () -> { };
			for(var helper : helpers.values()){
				// cancel() may already have requested the cleanup (and the helper may have answered it in the
				// meantime): reuse the recorded ack instead of requesting a second, never answered one.
				if(job.equals(helper.job) && !cleanupAcks.containsKey(helper)){
					requestCleanup(helper);
				}
			}
			long      deadline       = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			Throwable cleanupFailure = null;
			boolean   interrupted    = Thread.interrupted();
			try{
				for(var entry : cleanupAcks.entrySet()){
					var helper = entry.getKey();
					try{
						long remaining = deadline - System.nanoTime();
						if(remaining<=0) throw new TimeoutException("Timed out waiting for helper cleanup");
						entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
					}catch(InterruptedException e){
						// The master is being cancelled: the request was sent, so let the helpers finish their
						// cleanup asynchronously rather than masking the cancellation with a failure.
						break;
					}catch(ExecutionException|TimeoutException e){
						if(cleanupFailure == null)
							cleanupFailure = new IllegalStateException(
								"Failed to finish cleanup on helper " + helper.name, e.getCause() == null? e : e.getCause());
					}finally{
						helper.job = null;
					}
				}
			}finally{
				if(interrupted){
					Thread.currentThread().interrupt();
				}
			}
			for(var task : pendingReplays){ task.future().cancel(true); }
			if(replayPool != null){
				replayPool.shutdownNow();
				boolean replayInterrupted = Thread.interrupted();
				try{
					if(!replayPool.awaitTermination(30, TimeUnit.SECONDS)){
						throw new IllegalStateException("Master replay did not stop after cancellation");
					}
				}catch(InterruptedException e){ replayInterrupted = true; }finally{ if(replayInterrupted){ Thread.currentThread().interrupt(); } }
			}
			for(var worker : scheduler.snapshot().workers()){
				if(worker.demandReports() == 0){ continue; }
				context.progress().reportCustomMsg("Worker " + worker.id() + " queue demand: " + worker.demandReports() +
				                                   " empty-queue samples, " + worker.idleReports() + " with idle threads, " +
				                                   worker.startWaitReports() + " awaiting start approval, queue capacity " + worker.queueCapacity() +
				                                   " (250 ms samples, includes startup/tail)");
			}
			if(cleanupFailure != null){
				throw new IllegalStateException("A distributed helper did not clean up", cleanupFailure);
			}
		}
	}
	private static void quietClose(AutoCloseable resource){
		try{
			resource.close();
		}catch(Exception ignored){ }
	}
	private void registerResource(AutoCloseable resource){
		synchronized(lifecycleLock){
			if(!closed){
				resources.add(resource);
				return;
			}
		}
		quietClose(resource);
	}
	
	void close(){
		List<AutoCloseable> closing;
		synchronized(lifecycleLock){
			if(closed){ return; }
			closed = true;
			closing = new ArrayList<>(resources);
			resources.clear();
		}
		Collections.reverse(closing);
		for(var resource : closing){ quietClose(resource); }
		sendExecutor.shutdownNow();
	}
}
