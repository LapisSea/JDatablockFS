package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOTransactionBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public abstract class FileIOData extends ClosableIOData{
	private static final Set<Path> OPEN_FILES = new HashSet<>();
	
	private final Path    ownedFile;
	private       boolean ownershipReleased;
	
	private final long              checkpointBytes = ConfigDefs.WAL_CHECKPOINT_BYTES.resolveVal();
	private final boolean           durable;
	private       FileWriteAheadLog journal;
	private       boolean           applying;
	
	protected FileIOData(IOHook hook, File file, boolean readOnly, boolean durable) throws IOException{
		super(hook, readOnly);
		
		this.durable = durable && !readOnly;
		
		var path = file.toPath().toAbsolutePath().normalize();
		ownedFile = Files.exists(path)? path.toRealPath() : path.getParent().toRealPath().resolve(path.getFileName());
		
		synchronized(OPEN_FILES){
			if(!OPEN_FILES.add(ownedFile)){
				throw new IOException("Unable to acquire file access! Already open: " + ownedFile);
			}
		}
	}
	
	protected final void releaseFileOwnership(){
		synchronized(OPEN_FILES){
			if(ownershipReleased){
				return;
			}
			ownershipReleased = true;
			OPEN_FILES.remove(ownedFile);
		}
	}
	
	protected final void initializeJournal(File file) throws IOException{
		initializeJournal(FileWriteAheadLog.open(file.toPath(), isReadOnly(), durable));
	}
	
	final void initializeJournal(FileWriteAheadLog opened) throws IOException{
		journal = opened;
		if(journal != null){
			try{
				journal.recover(this::applyLogged);
				checkpointDirect();
			}catch(Throwable e){
				journal.close();
				throw e;
			}
			if(!durable){
				journal.close();
				journal = null;
			}
		}
	}
	
	@Override
	public final boolean isDurable(){ return durable; }
	
	@Override
	protected final boolean needsImplicitTransaction(){
		if(!durable){
			return false;
		}
		try(var ignored = lockIO()){
			return !applying && !hasActiveTransaction();
		}
	}
	
	@Override
	public final void applyTransaction(IOTransactionBuffer.TransactionExport changes) throws IOException{
		try(var ignored = lockIO()){
			if(hasActiveTransaction()){
				throw new IllegalStateException("Cannot apply a batch inside an active transaction");
			}
			if(isReadOnly()){
				throw new UnsupportedOperationException("Read-only source");
			}
			
			try{
				if(!durable){
					applyDirect(changes);
					return;
				}
				if(changes.writes().isEmpty() && changes.setCapacity().isEmpty()){
					return;
				}
				
				long size;
				if(changes.setCapacity().isPresent()){
					size = changes.setCapacity().get();
				}else{
					size = used;
					for(var write : changes.writes()){
						size = Math.max(size, write.ioEnd());
					}
				}
				
				journal.append(changes, size);
				applyLogged(changes, size);
				
				if(journal.size()>=checkpointBytes){
					checkpointDirect();
				}
			}catch(IOException|RuntimeException|Error e){
				physicalCommitFailed(e);
				throw e;
			}
		}
	}
	
	private void applyLogged(IOTransactionBuffer.TransactionExport changes, long size) throws IOException{
		long previousSize = used;
		applying = true;
		try{
			applyDirect(changes);
			if(getLength()>size && previousSize>size){
				resize(size);
			}
			if(getLength()<size){
				resize(size);
			}
			used = size;
		}finally{ applying = false; }
	}
	
	/**
	 * Forces the main file before retiring covered redo records
	 */
	public final void checkpoint() throws IOException{
		try(var ignored = lockIO()){
			if(hasActiveTransaction()){
				throw new IllegalStateException("Cannot checkpoint an active transaction");
			}
			try{
				checkpointDirect();
			}catch(IOException|RuntimeException|Error e){
				physicalCommitFailed(e);
				throw e;
			}
		}
	}
	private void checkpointDirect() throws IOException{
		if(isReadOnly()){
			return;
		}
		resize(used);
		forceFile();
		if(journal != null){
			journal.clear();
		}
	}
	
	/**
	 * A failed physical commit retains its WAL: close must not discard recovery information.
	 */
	protected final void closeJournal() throws IOException{
		if(journal == null){
			return;
		}
		try{
			checkpoint();
		}catch(IllegalStateException e){
			// A poisoned source is recovered on the next open.
		}finally{
			journal.close();
		}
	}
	protected abstract void forceFile() throws IOException;
}
