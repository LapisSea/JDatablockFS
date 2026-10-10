package com.lapissea.fuzz.distributed.bootstrap;

import com.lapissea.fuzz.distributed.FileCleanup;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

final class SnapshotLease implements AutoCloseable{
	private static final Set<Path> active = new HashSet<>();
	
	final         Path        directory;
	private final FileChannel channel;
	private final FileLock    lock;
	
	private SnapshotLease(Path directory, FileChannel channel, FileLock lock){
		this.directory = directory;
		this.channel = channel;
		this.lock = lock;
	}
	
	static synchronized SnapshotLease open(Path root) throws IOException{
		root = root.toAbsolutePath().normalize();
		
		Files.createDirectories(root);
		if(Files.isSymbolicLink(root)){
			throw new IOException("Snapshot root must not be a symbolic link");
		}
		
		try(var guard = FileChannel.open(root.resolve("cleanup.lock"), CREATE, WRITE);
		    var ignored = guard.lock()){
			
			try(var entries = Files.list(root)){
				for(var entry : entries.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()){
					if(active.contains(entry)){
						continue;
					}
					try{
						UUID.fromString(entry.getFileName().toString());
					}catch(IllegalArgumentException ignoredName){
						continue;
					}
					
					try(var owner = FileChannel.open(entry.resolve("lease"), CREATE, WRITE)){
						FileLock lease;
						try{
							lease = owner.tryLock();
						}catch(OverlappingFileLockException held){
							continue;
						}
						if(lease == null) continue;
						lease.release();
					}
					// Keep the root guard held, but close the lease file before deleting it on Windows
					FileCleanup.deleteTree(entry);
				}
			}
			
			var directory = Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
			var channel   = FileChannel.open(directory.resolve("lease"), CREATE, WRITE);
			
			try{
				var lease = new SnapshotLease(directory, channel, channel.lock());
				active.add(directory);
				return lease;
			}catch(Throwable e){
				channel.close();
				FileCleanup.deleteTree(directory);
				throw e;
			}
		}
	}
	
	@Override
	public synchronized void close() throws IOException{
		synchronized(SnapshotLease.class){
			if(!channel.isOpen()){
				return;
			}
			try(var guard = FileChannel.open(directory.getParent().resolve("cleanup.lock"), CREATE, WRITE);
			    var ignored = guard.lock()){
				try{
					lock.release();
				}finally{
					channel.close();
					active.remove(directory);
				}
				FileCleanup.deleteTree(directory);
			}catch(IOException|RuntimeException guardFailure){
				// If the guard cannot be taken the lease must still be dropped, or this snapshot stays pinned as active forever.
				releaseQuietly();
				throw guardFailure;
			}
		}
	}
	
	private void releaseQuietly(){
		try{
			lock.release();
		}catch(IOException ignored){ }
		try{
			channel.close();
		}catch(IOException ignored){ }
		active.remove(directory);
	}
}
