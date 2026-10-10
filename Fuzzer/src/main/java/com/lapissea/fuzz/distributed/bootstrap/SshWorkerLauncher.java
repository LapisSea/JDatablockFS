package com.lapissea.fuzz.distributed.bootstrap;

import com.google.gson.Gson;
import com.lapissea.fuzz.distributed.master.RemoteConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Launches helper JVMs over ssh: resolves the remote OS, installs the runtime bundle, and opens the return tunnel the helper connects through.
 */
public final class SshWorkerLauncher{
	private static final Gson GSON = new Gson();
	
	private SshWorkerLauncher(){ }
	
	public static final class WorkerProcess implements AutoCloseable{
		private volatile Process      process;
		private final    Set<Process> preparing = ConcurrentHashMap.newKeySet();
		private final    Thread       shutdown;
		private          boolean      closed;
		
		private WorkerProcess(){
			shutdown = Thread.ofPlatform().name("fuzz-ssh-cleanup").unstarted(this::close);
			Runtime.getRuntime().addShutdownHook(shutdown);
		}
		
		public Process process(){ return process; }
		
		synchronized Process start(ProcessBuilder builder) throws IOException{
			if(closed) throw new IOException("SSH worker closed");
			
			// A master started through Windows sshd inherits its descriptor table, which is not valid
			// for Java's child pipes. Nested OpenSSH otherwise hangs (Win32-OpenSSH issue #2037).
			builder.environment().remove("c28fc6f98a2c44abbbd89d6a3037d0d9_POSIX_FD_STATE");
			
			var child = builder.start();
			preparing.add(child);
			return child;
		}
		
		@Override
		public synchronized void close(){
			if(closed) return;
			closed = true;
			
			stop(process);
			for(var child : preparing){
				stop(child);
			}
			
			if(Thread.currentThread() != shutdown){
				try{
					Runtime.getRuntime().removeShutdownHook(shutdown);
				}catch(IllegalStateException ignored){ }
			}
		}
	}
	
	/**
	 * Installs the bundle on the helper, then starts its supervisor JVM on the return tunnel.
	 *
	 * @return the live worker, whose process carries the helper's stdout
	 */
	public static WorkerProcess launch(
		RemoteConfig.Host host, RuntimeBundle bundle, int masterPort, String helperMain, List<String> helperArgs
	) throws IOException, InterruptedException{
		if(masterPort<1 || masterPort>65535) throw new IllegalArgumentException("Invalid coordinator port");
		if(helperArgs.size()<2) throw new IllegalArgumentException("Helper arguments are missing the tunnel port placeholder");
		
		var    os              = resolveOs(host);
		var    bootstrap       = os.bootstrap();
		var    worker          = new WorkerProcess();
		String preparedCache   = null;
		UUID   preparedSession = null;
		try{
			var sessionId = helperArgs.size()>2? UUID.fromString(helperArgs.get(2)) : UUID.randomUUID();
			var parent    = host.workDirectory();
			if(parent == null || parent.isBlank()){
				parent = os.join(host.cacheDirectory(), "runs");
			}
			var artifactNames = bundle.artifacts().stream().map(RuntimeBundle.Artifact::name).toList();
			
			// The warm Java phase probes and uploads in one ssh round-trip. The cold-cache bootstrap
			// covers machines where HelperOps is not cached yet, and stays split (prepare then upload).
			HelperProtocol.CacheProbe probe;
			try{
				probe = install(worker, host, os, sessionId, bundle, host.cacheDirectory(), parent, artifactNames);
			}catch(IOException javaPhase){
				try{
					var script = bootstrap.prepare(host.cacheDirectory(), parent, sessionId, artifactNames);
					probe = parseProbe(host, output(worker, new ProcessBuilder(ssh(host, script.command())), script.stdinLines(), 30).lines().toList());
					requireRemotePath(host, os, probe.cache(), "cache directory");
					requireRemotePath(host, os, probe.parent(), "work parent directory");
					
					var missing = missingArtifacts(host, bundle, probe);
					if(!missing.isEmpty()){
						var upload = bootstrap.upload(probe.cache(), sessionId, missing.stream().map(RuntimeBundle.Artifact::name).toList()).command();
						upload(worker, new ProcessBuilder(ssh(host, upload)), missing);
					}
				}catch(IOException bootstrapPhase){
					// Both families failed. That is usually a wrong `os`, and the original failure is
					// far more useful than the cleanup that replaced it, so report all of it.
					throw new IOException(willNotConnect(
						host,
						"neither preparation phase could run: the java phase failed (" + javaPhase.getMessage() + ") " +
						"and so did the " + label(os) + " bootstrap (" + bootstrapPhase.getMessage() + "). If this machine is not a " +
						label(os) + " host, set \"os\" to \"" + os.other().name().toLowerCase() + "\" for it in " +
						RemoteConfig.configurationPath()), bootstrapPhase
					);
				}
			}
			var cache      = probe.cache();
			var workParent = probe.parent();
			preparedCache = cache;
			preparedSession = sessionId;
			
			var working      = os.join(workParent, sessionId.toString());
			var resolvedArgs = new ArrayList<>(helperArgs);
			resolvedArgs.set(1, HelperOps.PORT_SENTINEL);
			resolvedArgs.addAll(List.of(cache, workParent, working));
			
			// The bundle is cached by now, so the supervisor runs as Java.
			var supervisor = javaInvocation(host, os, bundle, cache, HelperOps.class.getName(), List.of("run"));
			worker.process = worker.start(new ProcessBuilder(helperCommand(host, masterPort, remoteCommand(worker, host, os, sessionId, supervisor))));
			
			connectTunnel(host, worker.process, port -> new HelperProtocol.Run(cache, workParent, working, helperMain, resolvedArgs, port, artifactNames));
			worker.preparing.remove(worker.process);
			
			return worker;
		}catch(IOException|InterruptedException|RuntimeException e){
			if(preparedCache != null && !Thread.currentThread().isInterrupted()){
				try{
					output(worker, new ProcessBuilder(ssh(host, bootstrap.failed(preparedCache, preparedSession).command())), 5);
				}catch(InterruptedException interrupted){
					Thread.currentThread().interrupt();
				}catch(IOException cleanupFailure){
					// Report the cleanup gap instead of hiding it, so a stranded reservation is diagnosable.
					System.err.println("Fuzzer helper " + host.name() + " cleanup after a failed launch failed: " + cleanupFailure);
				}
			}
			worker.close();
			throw e;
		}
	}
	/**
	 * The machine's OS: declared in the config, or detected with one ssh round-trip ({@link OsProbe}) when unset.
	 */
	private static HostOs resolveOs(RemoteConfig.Host host) throws IOException, InterruptedException{
		var declared = host.declaredOs();
		if(declared.isPresent()){ return declared.get(); }
		var answer = OsProbe.detect(host);
		System.out.println("Fuzzer helper " + host.name() + " detected as " + answer.os().name().toLowerCase() +
		                   " (" + answer.evidence4K() + ")");
		return answer.os();
	}
	
	/**
	 * A preparation path must be absolute for the helper's own OS. A Windows answer under a linux
	 * declaration (or the reverse)
	 */
	static void requireRemotePath(RemoteConfig.Host host, HostOs os, String path, String what) throws IOException{
		if(os.isAbsolute(path)){ return; }
		var looksLike = os.other().isAbsolute(path)? os.other() : null;
		throw new IOException(willNotConnect(
			host, looksLike == null?
			      "the " + what + " it reported, \"" + path + "\", is not an absolute " + label(os) + " path. Check its \"cacheDirectory\" and \"workDirectory\" in " + RemoteConfig.configurationPath() :
			      "the " + what + " it reported, \"" + path + "\", is an absolute " + label(looksLike) + " path, but it is configured as " + label(os) + ". Set \"os\": \"" + label(looksLike) + "\" for it in " + RemoteConfig.configurationPath()
		));
	}
	
	private static String label(HostOs os){ return os.name().toLowerCase(); }
	
	static String willNotConnect(RemoteConfig.Host host, String reason){
		return "Fuzzer helper \"" + host.name() + "\" (" + host.sshHost() + ") will not connect: " + reason + ".";
	}
	private static void connectTunnel(RemoteConfig.Host host, Process process, IntFunction<?> requestForPort) throws IOException, InterruptedException{
		var port = new CompletableFuture<Integer>();
		if(host.tunnelPort() != 0) port.complete(host.tunnelPort());
		
		Thread.ofVirtual().name("fuzz-ssh-stderr-" + host.name()).start(() -> {
			try(var reader = process.errorReader(UTF_8)){
				String line;
				while((line = reader.readLine()) != null){
					if(line.startsWith("#< CLIXML") || line.startsWith("<Objs")){ continue; }
					var allocated = allocatedPort(line);
					if(allocated.isPresent()){
						port.complete(allocated.getAsInt());
					}else{
						System.err.println("[fuzz " + host.name() + "] " + line);
					}
				}
				port.completeExceptionally(new IOException("SSH exited before allocating the helper port"));
			}catch(IOException e){
				port.completeExceptionally(e);
			}
		});
		
		try(var input = process.getOutputStream()){
			int allocated = port.get(host.connectTimeoutSeconds() + 5L, TimeUnit.SECONDS);
			// The helper supervisor receives the whole run request (including the artifact manifest
			// so it can record which cache entries this session references) once the port is known.
			input.write((GSON.toJson(requestForPort.apply(allocated)) + "\n").getBytes(UTF_8));
		}catch(ExecutionException|TimeoutException e){
			throw new IOException("SSH tunnel setup failed for " + host.name(), e);
		}
	}
	
	private static void upload(WorkerProcess worker, ProcessBuilder builder,
	                           List<RuntimeBundle.Artifact> missing) throws IOException, InterruptedException{
		var process = worker.start(builder.redirectError(ProcessBuilder.Redirect.PIPE).redirectOutput(ProcessBuilder.Redirect.DISCARD));
		pipeStderr(process, true);
		var writer = new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try(var out = process.getOutputStream()){
				TarIO.write(out, missing);
				writer.complete(null);
			}catch(Throwable e){
				writer.completeExceptionally(e);
			}
		});
		try{
			check(process, 120, "bundle upload");
			writer.get(5, TimeUnit.SECONDS);
		}catch(ExecutionException|TimeoutException e){
			throw new IOException("Bundle transfer failed", e.getCause());
		}finally{
			stop(process);
			worker.preparing.remove(process);
		}
	}
	/**
	 * Probes and uploads in one ssh round-trip: writes the request line, reads the probe line, then
	 * streams a tar of only the missing artifacts.
	 *
	 * @return the helper's cache probe
	 */
	private static HelperProtocol.CacheProbe install(
		WorkerProcess worker, RemoteConfig.Host host, HostOs os, UUID session, RuntimeBundle bundle,
		String cacheDir, String parent, List<String> artifactNames
	) throws IOException, InterruptedException{
		
		var invocation = javaInvocation(host, os, bundle, cacheDir, HelperOps.class.getName(), List.of("install"));
		
		var process = worker.start(
			new ProcessBuilder(ssh(host, remoteCommand(worker, host, os, session, invocation)))
				.redirectError(ProcessBuilder.Redirect.PIPE)
		);
		pipeStderr(process, false);
		try{
			var stdin = process.getOutputStream();
			stdin.write((GSON.toJson(new HelperProtocol.Install(cacheDir, parent, session, artifactNames)) + "\n").getBytes(UTF_8));
			stdin.flush();
			
			var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
			var line   = reader.readLine();
			if(line == null){
				throw new IOException("Truncated install response");
			}
			
			HelperProtocol.CacheProbe probe;
			try{
				probe = GSON.fromJson(line, HelperProtocol.CacheProbe.class);
			}catch(RuntimeException e){
				throw new IOException("Invalid install response: " + line, e);
			}
			if(probe == null){
				throw new IOException("Empty install response");
			}
			
			requireRemotePath(host, os, probe.cache(), "cache directory");
			requireRemotePath(host, os, probe.parent(), "work parent directory");
			
			TarIO.write(stdin, missingArtifacts(host, bundle, probe));
			stdin.flush();
			stdin.close();
			
			check(process, 120, "bundle install");
			return probe;
		}finally{
			stop(process);
			worker.preparing.remove(process);
		}
	}
	
	private static HelperProtocol.CacheProbe parseProbe(RemoteConfig.Host host, List<String> lines) throws IOException{
		if(lines.size()<2){
			throw new IOException(willNotConnect(host,
			                                     "its preparation response had " + lines.size() + " line(s), expected the canonical cache path and work " +
			                                     "parent path first: " + lines));
		}
		var missing = new ArrayList<Integer>(lines.size() - 2);
		for(var line : lines.subList(2, lines.size())){
			try{
				missing.add(Integer.parseInt(line.strip()));
			}catch(NumberFormatException e){
				throw new IOException("Invalid artifact index: " + line, e);
			}
		}
		return new HelperProtocol.CacheProbe(lines.get(0), lines.get(1), missing);
	}
	private static ArrayList<RuntimeBundle.Artifact> missingArtifacts(RemoteConfig.Host host, RuntimeBundle bundle, HelperProtocol.CacheProbe probe) throws IOException{
		var missing = new ArrayList<RuntimeBundle.Artifact>(probe.missing().size());
		for(var index : probe.missing()){
			missing.add(artifactAt(host, bundle, index));
		}
		return missing;
	}
	private static RuntimeBundle.Artifact artifactAt(RemoteConfig.Host host, RuntimeBundle bundle, int index) throws IOException{
		if(index<0 || index>=bundle.artifacts().size()){
			throw new IOException(willNotConnect(host, "its cache probe reported artifact index \"" + index +
			                                           "\", which the bundle it was given does not contain"));
		}
		return bundle.artifacts().get(index);
	}
	
	static OptionalInt allocatedPort(String line){
		var match = Pattern.compile("^Allocated port ([0-9]+) for remote forward to .+$").matcher(line);
		if(!match.matches()){ return OptionalInt.empty(); }
		try{
			int port = Integer.parseInt(match.group(1));
			return port>0 && port<=65535? OptionalInt.of(port) : OptionalInt.empty();
		}catch(NumberFormatException ignored){ return OptionalInt.empty(); }
	}
	static List<String> helperCommand(RemoteConfig.Host host, int masterPort, String command){
		var args = ssh(host, command);
		args.addAll(1, List.of("-o", "ExitOnForwardFailure=yes", "-R", "127.0.0.1:" + host.tunnelPort() + ":127.0.0.1:" + masterPort));
		return args;
	}
	
	private static void stop(Process process){
		if(process == null || !process.isAlive()){ return; }
		process.destroy();
		try{
			if(!process.waitFor(2, TimeUnit.SECONDS)){ process.destroyForcibly(); }
		}catch(InterruptedException e){
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}
	static List<String> javaInvocation(RemoteConfig.Host host, HostOs os, RuntimeBundle bundle, String cache, String helperMain, List<String> helperArgs){
		var invocation = new ArrayList<String>();
		invocation.add(host.javaExecutable());
		for(var flag : bundle.jvmArguments()){
			if(flag.startsWith("--patch-module=")){
				// The bundle stores the canonical ':' separator. The helper JVM needs its own platform's separator.
				int separator = flag.indexOf('=', "--patch-module=".length());
				flag = flag.substring(0, separator + 1) + paths(os, cache, List.of(flag.substring(separator + 1).split(":")));
			}
			invocation.add(flag);
		}
		for(var property : System.getProperties().stringPropertyNames()){
			var value = System.getProperty(property);
			if((property.startsWith("fuzz.") || property.startsWith("dfs.")) && !property.startsWith("fuzz.remote.") &&
			   value.indexOf('\n')<0 && value.indexOf('\r')<0){
				invocation.add("-D" + property + "=" + value);
			}
		}
		if(!bundle.modulePath().isEmpty()){
			invocation.add("--module-path");
			invocation.add(paths(os, cache, bundle.modulePath()));
			invocation.add("--add-modules");
			invocation.add("ALL-MODULE-PATH");
		}
		invocation.add("-cp");
		invocation.add(paths(os, cache, bundle.classPath()));
		invocation.add(helperMain);
		invocation.addAll(helperArgs);
		return invocation;
	}
	private static String paths(HostOs os, String cache, List<String> entries){
		return entries.stream().map(n -> os.join(cache, n)).collect(Collectors.joining(os.pathSeparator()));
	}
	private static String remoteCommand(WorkerProcess worker, RemoteConfig.Host host, HostOs os, UUID session, List<String> parts) throws IOException, InterruptedException{
		return os.bootstrap().remoteInvocation(
			session, parts,
			(command, payload) -> {
				streamStdin(worker, new ProcessBuilder(ssh(host, command)), payload, 30);
			});
	}
	private static void streamStdin(WorkerProcess worker, ProcessBuilder builder, byte[] payload,
	                                int timeoutSeconds) throws IOException, InterruptedException{
		var process = worker.start(builder.redirectError(ProcessBuilder.Redirect.PIPE).redirectOutput(ProcessBuilder.Redirect.DISCARD));
		pipeStderr(process, true);
		var writer = new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try(var out = process.getOutputStream()){
				out.write(payload);
				writer.complete(null);
			}catch(Throwable e){
				writer.completeExceptionally(e);
			}
		});
		try{
			check(process, timeoutSeconds, "out-of-band transfer");
			writer.get(5, TimeUnit.SECONDS);
		}catch(ExecutionException|TimeoutException e){
			throw new IOException("Remote transfer failed", e.getCause());
		}finally{
			stop(process);
			worker.preparing.remove(process);
		}
	}
	static ArrayList<String> ssh(RemoteConfig.Host host, String command){
		var args = new ArrayList<>(
			List.of("ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=" + host.connectTimeoutSeconds(), "-o", "ServerAliveInterval=10", "-o",
			        "ServerAliveCountMax=2", "-o", "ControlMaster=no", "-o", "ControlPath=none", "-o", "ControlPersist=no", "-o", "LogLevel=INFO", "--", host.sshHost()));
		if(command != null){ args.add(command); }
		return args;
	}
	private static String output(WorkerProcess worker, ProcessBuilder builder, int timeout) throws IOException, InterruptedException{
		return output(worker, builder, List.of(), timeout, true);
	}
	private static String output(WorkerProcess worker, ProcessBuilder builder, List<String> inputLines,
	                             int timeout) throws IOException, InterruptedException{
		return output(worker, builder, inputLines, timeout, true);
	}
	private static String output(WorkerProcess worker, ProcessBuilder builder, List<String> inputLines,
	                             int timeout, boolean forwardErr) throws IOException, InterruptedException{
		var process = worker.start(builder.redirectError(ProcessBuilder.Redirect.PIPE));
		pipeStderr(process, forwardErr);
		var bytes  = new ByteArrayOutputStream();
		var reader = new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try(var input = process.getInputStream()){
				input.transferTo(bytes);
				reader.complete(null);
			}catch(IOException e){
				reader.completeExceptionally(e);
			}
		});
		try{
			try(var output = process.getOutputStream()){
				for(var line : inputLines){
					output.write(line.getBytes(UTF_8));
					output.write('\n');
				}
			}
			check(process, timeout);
			reader.get(5, TimeUnit.SECONDS);
			return bytes.toString(UTF_8);
		}catch(ExecutionException|TimeoutException e){
			throw new IOException("SSH response failed", e.getCause());
		}finally{
			stop(process);
			worker.preparing.remove(process);
		}
	}
	private static void check(Process process, int timeout) throws IOException, InterruptedException{
		check(process, timeout, "remote command");
	}
	private static void check(Process process, int timeout, String what) throws IOException, InterruptedException{
		if(!process.waitFor(timeout, TimeUnit.SECONDS)){ throw new IOException("SSH operation timed out (" + what + ")"); }
		if(process.exitValue() != 0){ throw new IOException("SSH operation failed (" + what + "): exit " + process.exitValue()); }
	}
	
	/**
	 * Drains a process's stderr, dropping PowerShell's CLIXML startup noise and forwarding the rest when asked.
	 */
	private static void pipeStderr(Process process, boolean forward){
		Thread.ofVirtual().name("fuzz-ssh-stderr").start(() -> {
			try(var reader = process.errorReader(UTF_8)){
				String  line;
				boolean clixml = false;
				while((line = reader.readLine()) != null){
					if(line.startsWith("#< CLIXML")){
						clixml = true;
						continue;
					}
					if(line.startsWith("<Objs")){
						clixml = false;
						continue;
					}
					if(clixml || !forward){ continue; }
					System.err.println(line);
				}
			}catch(IOException ignored){ }
		});
	}
}
