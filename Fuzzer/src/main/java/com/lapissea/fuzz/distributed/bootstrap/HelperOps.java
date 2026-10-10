package com.lapissea.fuzz.distributed.bootstrap;

import com.google.gson.Gson;
import com.lapissea.fuzz.distributed.FileCleanup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The helper-side phases run over ssh: cache preparation/probing and the helper supervisor. The
 * command is the first argument; the rest of the request is a JSON line on stdin, per {@link HelperProtocol}.
 *
 * <ul>
 *     <li>{@code install}: a {@link HelperProtocol.Install}, then the tar of the missing artifacts answers with
 *     a {@link HelperProtocol.CacheProbe} line.</li>
 *     <li>{@code run}: a {@link HelperProtocol.Run}; replaces the {@value #PORT_SENTINEL} argument with the
 *     port and invokes the helper main in this JVM.</li>
 * </ul>
 */
public final class HelperOps{
	
	public static final String PORT_SENTINEL = "%PORT%";
	
	private static final Gson GSON = new Gson();
	
	private HelperOps(){ }
	
	public static void main(String[] args) throws Exception{
		if(args.length == 0){
			throw new IllegalArgumentException("Expected a HelperOps command");
		}
		switch(args[0]){
			case "install" -> install(GSON.fromJson(requiredLine(System.in), HelperProtocol.Install.class), System.in, System.out);
			case "run" -> run(GSON.fromJson(requiredLine(System.in), HelperProtocol.Run.class));
			default -> throw new IllegalArgumentException("Unknown HelperOps command: " + args[0]);
		}
	}
	
	/**
	 * Reserves the session, then reports the canonical cache and parent paths and the indexes of the artifacts that are not cached yet.
	 */
	static HelperProtocol.CacheProbe prepare(HelperProtocol.Install request) throws IOException{
		var cache  = Path.of(request.cache());
		var parent = Path.of(request.parent());
		Files.createDirectories(cache);
		Files.createDirectories(parent);
		var canonicalCache  = cache.toRealPath();
		var canonicalParent = parent.toRealPath();
		
		var artifacts = request.artifacts();
		var missing   = new ArrayList<Integer>();
		try(var gate = CacheGate.acquire(canonicalCache, true)){
			var reservations = canonicalCache.resolve(".preparing");
			Files.createDirectories(reservations);
			var reservation = reservations.resolve(request.session().toString());
			if(!Files.exists(reservation)){
				Files.createFile(reservation);
			}
			for(int i = 0; i<artifacts.size(); i++){
				var file = canonicalCache.resolve(artifacts.get(i));
				requireManagedDirectory(file.getParent(), false);
				if(!isRealRegularFile(file)){
					missing.add(i);
				}
			}
		}
		return new HelperProtocol.CacheProbe(canonicalCache.toString(), canonicalParent.toString(), missing);
	}
	
	static List<String> extractArtifacts(Path cache, UUID session, InputStream tar) throws IOException{
		var staging = cache.resolve(".upload-" + session);
		try{
			Files.createDirectories(staging);
			Files.writeString(staging.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			
			var extracted = TarIO.extract(tar, staging);
			
			for(var name : extracted){
				verifyChecksum(staging.resolve(name), name);
			}
			
			try(var gate = CacheGate.acquire(cache, true)){
				for(var name : extracted){
					var destination = cache.resolve(name);
					requireManagedDirectory(destination.getParent(), true);
					Files.move(staging.resolve(name), destination, StandardCopyOption.REPLACE_EXISTING);
					Files.setLastModifiedTime(destination, FileTime.from(Instant.now()));
				}
			}
			return extracted;
		}finally{
			FileCleanup.deleteTree(staging);
		}
	}
	
	/**
	 * Registers the workspace under the cache gate, then invokes the helper main in this JVM.
	 */
	static void run(HelperProtocol.Run request) throws Exception{
		var port = request.port();
		if(port<1 || port>65535){
			throw new IOException("Invalid allocated tunnel port: " + port);
		}
		var helperArgs = request.args().toArray(String[]::new);
		var slot       = -1;
		for(int i = 0; i<helperArgs.length; i++){
			if(PORT_SENTINEL.equals(helperArgs[i])){
				slot = i;
				break;
			}
		}
		if(slot<0){
			throw new IllegalArgumentException("Helper arguments are missing the " + PORT_SENTINEL + " port placeholder");
		}
		helperArgs = helperArgs.clone();
		helperArgs[slot] = Integer.toString(port);
		
		var cache   = Path.of(request.cache());
		var parent  = Path.of(request.parent());
		var working = Path.of(request.working());
		try(var gate = CacheGate.acquire(cache, true)){
			Files.createDirectories(working);
			Files.writeString(working.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
			Files.write(working.resolve(".artifacts"), request.artifacts(), StandardCharsets.UTF_8);
			Files.createDirectories(cache.resolve(".sessions"));
			Files.writeString(cache.resolve(".sessions").resolve(working.getFileName()),
			                  working.toAbsolutePath().normalize().toString());
			// Leave the .preparing reservation in place: RemoteWorkspace.open deletes it only after it has
			// taken the session lease, so a concurrent sweep cannot evict this workspace in the window
			// between registration here and lease acquisition in the helper main.
		}
		try{
			var main = Class.forName(request.main()).getMethod("main", String[].class);
			main.invoke(null, (Object)helperArgs);
		}catch(InvocationTargetException e){
			switch(e.getCause()){
				case Exception exception -> throw exception;
				case Error error -> throw error;
				default -> throw e;
			}
		}finally{
			RemoteWorkspace.cleanup(cache, parent);
		}
	}
	
	static void install(HelperProtocol.Install request, InputStream in, PrintStream out) throws IOException{
		var probe = prepare(request);
		
		out.println(GSON.toJson(probe));
		out.flush();
		
		extractArtifacts(Path.of(probe.cache()), request.session(), in);
	}
	
	private static String requiredLine(InputStream in) throws IOException{
		var line = readLine(in);
		if(line == null){ throw new IOException("Missing helper request"); }
		return line;
	}
	
	private static String readLine(InputStream in) throws IOException{
		var out = new ByteArrayOutputStream();
		int b;
		while((b = in.read()) != -1){
			if(b == '\n'){ return out.toString(StandardCharsets.UTF_8); }
			out.write(b);
		}
		return out.size() == 0? null : out.toString(StandardCharsets.UTF_8);
	}
	
	/**
	 * Artifact names are content-addressed (sha-256 hex prefix), so received bytes are verified against the name before publication.
	 */
	private static void verifyChecksum(Path file, String name) throws IOException{
		var separator = name.lastIndexOf('/');
		if(separator != 64){ throw new IOException("Artifact name without a sha-256 prefix: " + name); }
		var digest = digest();
		try(var in = Files.newInputStream(file)){
			var buffer = new byte[1<<16];
			int n;
			while((n = in.read(buffer))>=0) digest.update(buffer, 0, n);
		}
		var actual = HexFormat.of().formatHex(digest.digest());
		if(!actual.equals(name.substring(0, separator))){
			throw new IOException("Artifact checksum mismatch for " + name + ": received " + actual);
		}
	}
	
	private static MessageDigest digest(){
		try{
			return MessageDigest.getInstance("SHA-256");
		}catch(NoSuchAlgorithmException e){
			throw new AssertionError(e);
		}
	}
	
	private static void requireManagedDirectory(Path directory, boolean create) throws IOException{
		if(Files.exists(directory, LinkOption.NOFOLLOW_LINKS)){
			var marker = directory.resolve(".fuzzer-managed");
			if(!isRealDirectory(directory) || !isRealRegularFile(marker) ||
			   !Files.readString(marker).strip().equals(RemoteWorkspace.MARKER)){
				throw new IOException("Unmanaged fuzzer artifact directory: " + directory);
			}
		}else if(create){
			Files.createDirectories(directory);
			Files.writeString(directory.resolve(".fuzzer-managed"), RemoteWorkspace.MARKER);
		}
	}
	
	private static boolean isRealDirectory(Path path){
		return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
	}
	private static boolean isRealRegularFile(Path path){
		return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
	}
}
