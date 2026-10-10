package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.FileCleanup;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * The helper cache/workspace layout this class participates in (shared with {@link HelperOps} and the
 * shell bootstrap). This class owns the workspace, the {@code .sessions} registry, {@code .lease}
 * and {@code .ended}, and runs the sweep:
 *
 * <pre>
 * &lt;cache&gt;/
 *   .cleanup-lock         CacheGate lock file (the shell bootstrap uses the same name)
 *   .preparing/&lt;session&gt;  mid-preparation reservation (HelperOps creates; open deletes)
 *   .sessions/&lt;session&gt;   registry: file containing the absolute session path
 *   .upload-&lt;session&gt;/    upload staging (transient)
 *   &lt;sha256&gt;/&lt;basename&gt;   artifacts, one .fuzzer-managed marker per hash dir
 * &lt;parent&gt;/               host.workDirectory(), or &lt;cache&gt;/runs when unset
 *   &lt;session&gt;/            the workspace, named by session id
 *     .fuzzer-managed     ownership marker (RemoteWorkspace.MARKER)
 *     .lease              empty file whose FileLock (or a live thread here) means "active"
 *     .artifacts          one artifact name per line: the sweep's keep-set
 *     .ended              millis on close. The sweep's recency signal, falling back to the marker
 * </pre>
 * <p>
 * The sweep keeps the 3 most recent inactive sessions and only evicts past a 1-hour grace once no
 * {@code .preparing} reservation remains.
 */
public final class RemoteWorkspace implements AutoCloseable{
	public static void main(String[] args) throws IOException{
		cleanup(Path.of(args[0]), Path.of(args[1]));
	}
	
	private static final Set<Path> LOCAL_ACTIVE = new HashSet<>();
	public static final  String    MARKER       = "jdatablockfs-fuzzer-v1";
	
	private final Path cache;
	private final Path parent;
	private final Path session;
	
	private final FileChannel channel;
	private final FileLock    lease;
	private       boolean     closed;
	private       Thread      shutdownHook;
	
	private RemoteWorkspace(Path cache, Path parent, Path session, FileChannel channel, FileLock lease){
		this.cache = cache;
		this.parent = parent;
		this.session = session;
		this.channel = channel;
		this.lease = lease;
	}
	
	public static synchronized RemoteWorkspace open(Path cache, Path parent, Path session) throws IOException{
		Files.createDirectories(cache);
		RemoteWorkspace result;
		try(var gate = CacheGate.acquire(cache, true)){
			result = openLocked(cache, parent, session);
		}
		try{
			cleanup(cache, parent);
		}catch(IOException|RuntimeException cleanupFailure){
			// An opportunistic sweep failure must not turn a workspace that opened successfully into a failure.
			System.err.println("Fuzzer workspace sweep failed: " + cleanupFailure);
		}
		return result;
	}
	
	private static RemoteWorkspace openLocked(Path cache, Path parent, Path session) throws IOException{
		cache = cache.toAbsolutePath().normalize();
		parent = parent.toAbsolutePath().normalize();
		session = session.toAbsolutePath().normalize();
		
		if(LOCAL_ACTIVE.contains(session)){
			throw new IOException("Helper session already active");
		}
		if(!managed(session)){
			throw new IOException("Unmanaged helper session: " + session);
		}
		
		var channel = FileChannel.open(session.resolve(".lease"), CREATE, WRITE);
		
		FileLock lease;
		try{
			lease = channel.tryLock();
			if(lease == null){
				throw new IOException("Helper session already active");
			}
		}catch(Throwable e){
			channel.close();
			throw e;
		}
		
		LOCAL_ACTIVE.add(session);
		
		var result = new RemoteWorkspace(cache, parent, session, channel, lease);
		try{
			Files.createDirectories(cache.resolve(".sessions"));
			Files.writeString(cache.resolve(".sessions").resolve(session.getFileName()), session.toAbsolutePath().normalize().toString());
			Files.deleteIfExists(cache.resolve(".preparing").resolve(session.getFileName()));
			
			result.shutdownHook = Thread.ofPlatform().name("fuzzer-workspace-cleanup").unstarted(() -> {
				try{
					result.close();
				}catch(IOException e){
					System.err.println("Fuzzer workspace cleanup failed: " + e);
				}
			});
			Runtime.getRuntime().addShutdownHook(result.shutdownHook);
			
			return result;
		}catch(IOException|RuntimeException e){
			LOCAL_ACTIVE.remove(session);
			lease.release();
			channel.close();
			throw e;
		}
	}
	
	@Override
	public synchronized void close() throws IOException{
		if(closed) return;
		closed = true;
		if(shutdownHook != null && Thread.currentThread() != shutdownHook){
			try{
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			}catch(IllegalStateException ignored){ }
		}
		synchronized(RemoteWorkspace.class){
			try{
				Files.writeString(session.resolve(".ended"), Long.toString(System.currentTimeMillis()));
			}catch(IOException endedFailure){
				// The .ended marker is advisory; a vanished session directory must not skip the sweep below.
			}finally{
				try{
					lease.release();
					channel.close();
				}finally{
					LOCAL_ACTIVE.remove(session);
				}
			}
		}
		cleanup(cache, parent);
	}
	
	private static boolean managed(Path dir) throws IOException{
		return !Files.isSymbolicLink(dir) && isRealDir(dir) &&
		       isRealRegularFile(dir.resolve(".fuzzer-managed")) &&
		       Files.readString(dir.resolve(".fuzzer-managed")).strip().equals(MARKER);
	}
	public static synchronized void cleanup(Path cache, Path parent) throws IOException{
		Files.createDirectories(cache);
		try(var gate = CacheGate.acquire(cache, false)){
			if(gate != null){
				sweep(cache, parent);
			}
		}
	}
	
	private static void sweep(Path cache, Path parent) throws IOException{
		long now       = System.currentTimeMillis();
		long grace     = Duration.ofHours(1).toMillis();
		var  preparing = new HashSet<String>();
		
		Path reservations = cache.resolve(".preparing");
		
		if(isRealDir(reservations)){
			try(var entries = Files.list(reservations)){
				for(Path p : entries.toList()){
					if(isRealRegularFile(p)){
						if(now - Files.getLastModifiedTime(p).toMillis()<grace){
							preparing.add(p.getFileName().toString());
						}else{
							Files.delete(p);
						}
					}
				}
			}
		}
		
		var inactive   = new ArrayList<Path>();
		var retained   = new ArrayList<Path>();
		var candidates = new LinkedHashSet<Path>();
		
		Path registry = cache.resolve(".sessions");
		
		if(isRealDir(registry)){
			try(var entries = Files.list(registry)){
				for(Path record : entries.toList()){
					if(!isRealRegularFile(record)) continue;
					
					Path path;
					try{
						path = Path.of(Files.readString(record).strip());
					}catch(InvalidPathException ignored){
						continue;
					}
					if(!path.isAbsolute()) continue;
					
					if(isReal(path)){
						candidates.add(path.normalize());
					}else{
						Files.delete(record);
					}
				}
			}
		}
		
		if(isRealDir(parent)){
			try(var entries = Files.list(parent)){
				candidates.addAll(entries.map(p -> p.toAbsolutePath().normalize()).toList());
			}
		}
		
		var channels = new ArrayList<FileChannel>();
		var leases   = new ArrayList<FileLock>();
		try{
			for(Path p : candidates){
				if(!managed(p)){ continue; }
				// POSIX locks are dropped when any descriptor for this inode closes. Never reopen our own lease
				if(LOCAL_ACTIVE.contains(p)){
					retained.add(p);
					continue;
				}
				if(preparing.contains(p.getFileName().toString())){
					retained.add(p);
					continue;
				}
				
				var ch = FileChannel.open(p.resolve(".lease"), CREATE, WRITE);
				channels.add(ch);
				
				FileLock candidateLease = null;
				try{
					candidateLease = ch.tryLock();
				}catch(OverlappingFileLockException ignored){ }
				
				if(candidateLease == null){
					retained.add(p);
					continue;
				}
				
				leases.add(candidateLease);
				inactive.add(p);
			}
			
			inactive.sort(Comparator.comparingLong(RemoteWorkspace::modified).reversed());
			for(int i = 0; i<inactive.size(); i++){
				Path p = inactive.get(i);
				if(i<3){
					retained.add(p);
				}else{
					FileCleanup.deleteTree(p);
					Files.deleteIfExists(registry.resolve(p.getFileName()));
				}
			}
		}finally{
			for(FileLock candidateLease : leases){
				candidateLease.release();
			}
			for(FileChannel ch : channels){
				ch.close();
			}
		}
		// Reservations may still be transferring their manifest, so defer artifact collection.
		if(!preparing.isEmpty()) return;
		var references = new HashSet<String>();
		for(Path p : retained){
			Path manifest = p.resolve(".artifacts");
			if(!isRealRegularFile(manifest)){
				return;
			}
			references.addAll(Files.readAllLines(manifest));
		}
		try(var dirs = Files.list(cache)){
			for(Path dir : dirs.toList()){
				if(dir.getFileName().toString().startsWith(".upload-") && managed(dir) && now - modified(dir)>grace){
					FileCleanup.deleteTree(dir);
					continue;
				}
				if(!dir.getFileName().toString().matches("[0-9a-f]{64}") || !managed(dir)){
					continue;
				}
				
				try(var files = Files.list(dir)){
					for(Path file : files.toList()){
						if(file.getFileName().toString().equals(".fuzzer-managed")){
							continue;
						}
						if(!isRealRegularFile(file)){
							continue;
						}
						
						String name = dir.getFileName() + "/" + file.getFileName();
						if(!references.contains(name) && now - Files.getLastModifiedTime(file).toMillis()>grace){
							Files.delete(file);
						}
					}
				}
				try(var files = Files.list(dir)){
					if(files.allMatch(p -> p.getFileName().toString().equals(".fuzzer-managed"))){
						Files.delete(dir.resolve(".fuzzer-managed"));
						Files.delete(dir);
					}
				}
			}
		}
	}
	
	private static boolean isRealRegularFile(Path file){ return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS); }
	private static boolean isRealDir(Path file)        { return Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS); }
	private static boolean isReal(Path path)           { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
	
	private static long modified(Path p){
		try{
			Path ended = p.resolve(".ended");
			return Files.getLastModifiedTime(Files.exists(ended)? ended : p.resolve(".fuzzer-managed")).toMillis();
		}catch(IOException e){
			// An unreadable timestamp must not bias retention toward deletion.
			return System.currentTimeMillis();
		}
	}
}
