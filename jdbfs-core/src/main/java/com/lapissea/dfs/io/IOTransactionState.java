package com.lapissea.dfs.io;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

public final class IOTransactionState{
	public interface Access extends AutoCloseable{
		@Override
		void close();
	}
	
	private final class Scope implements IOTransaction{
		
		private final Scope   parent;
		private final Thread  thread = Thread.currentThread();
		private       boolean committed;
		private       boolean closed;
		
		private Scope(Scope parent){
			this.parent = parent;
		}
		
		private void checkThread(){
			if(thread != Thread.currentThread()) throw new IllegalStateException("Transaction scopes cannot move between threads");
		}
		
		private void checkOpen(){
			checkThread();
			if(closed) throw new IllegalStateException("Transaction scope is closed");
		}
		
		private void checkTop(){
			checkOpen();
			if(top != this) throw new IllegalStateException("Transaction scopes must complete in reverse opening order");
		}
		
		@Override
		public void commit(){
			checkTop();
			if(failed) throw new IllegalStateException("Transaction is rollback only");
			committed = true;
		}
		
		@Override
		public void close() throws IOException{
			checkThread();
			if(closed) return;
			
			checkTop();
			closed = true;
			
			if(!committed){
				fail();
			}
			
			top = parent;
			try{
				if(parent != null) return;
				owner = null;
				
				if(failed){
					buffer.reset();
					try{
						target.getUnmanagedRegistry().rollback();
					}catch(IOException|RuntimeException|Error e){
						physicalCommitFailed(e);
						throw e;
					}
					
					if(committed){
						throw new IllegalStateException("Transaction is rollback only");
					}
				}else{
					try{
						target.applyTransaction(buffer.export());
						target.getUnmanagedRegistry().committed();
					}catch(IOException|RuntimeException|Error e){
						physicalCommitFailed(e);
						throw e;
					}
				}
			}finally{
				if(parent == null){
					if(commitFailure != null) target.getUnmanagedRegistry().committed();
					owner = null;
					failed = false;
				}
				gate.unlock();
			}
		}
		
		@Override
		public int getChunkCount(){
			checkOpen();
			return buffer.getChunkCount();
		}
		
		@Override
		public long getTotalBytes(){
			checkOpen();
			return buffer.getTotalBytes();
		}
	}
	
	public static final Access NOOP_ACCESS = () -> { };
	
	private final IOInterface target;
	
	private final ReentrantLock gate   = new ReentrantLock();
	private final Access        unlock = gate::unlock;
	
	private final IOTransactionBuffer buffer = new IOTransactionBuffer(false);
	
	private volatile Thread    owner;
	private volatile long      version;
	private          Scope     top;
	private          boolean   failed;
	private          Throwable commitFailure;
	
	public IOTransactionState(IOInterface target){ this.target = target; }
	
	public Access access(){
		// The root already holds the gate for its entire lifetime. Its hot read/write path needs no further cheks
		if(owner == Thread.currentThread()) return NOOP_ACCESS;
		gate.lock();
		if(commitFailure != null){
			gate.unlock();
			throw new IllegalStateException("Backing IO unusable after failed commit! Close and reopen it", commitFailure);
		}
		return unlock;
	}
	
	public Access writeAccess(){
		var access = access();
		if(failed || top != null && top.committed){
			access.close();
			throw new IllegalStateException(failed? "Transaction is rollback only" : "Transaction scope is already committed");
		}
		return access;
	}
	
	public Access closeAccess(){
		gate.lock();
		if(top != null){
			gate.unlock();
			throw new IllegalStateException("Close transaction scopes before closing backing IO");
		}
		return unlock;
	}
	
	public boolean inTransaction()     { return owner == Thread.currentThread(); }
	public IOTransactionBuffer buffer(){ return buffer; }
	public long version()              { return version; }
	
	public void checkVersion(long expected){
		// Validity checks also run under cache monitors. Do not wait on the IO gate here: its owner may need the
		// same cache while another thread is checking validity.
		if(version != expected){
			throw new IllegalStateException("Data provider invalidated by transaction rollback! Reopen the provider and discard attached objects");
		}
	}
	
	public IOTransaction open(){
		gate.lock();
		try{
			if(commitFailure != null) throw new IllegalStateException("Backing IO unusable after failed commit", commitFailure);
			if(failed) throw new IllegalStateException("Transaction is rollback only");
			if(top != null && top.committed) throw new IllegalStateException("Transaction scope is already committed");
			var scope = new Scope(top);
			if(top == null){
				owner = Thread.currentThread();
				target.getUnmanagedRegistry().begin();
			}
			return top = scope;
		}catch(Throwable e){
			gate.unlock();
			throw e;
		}
	}
	/**
	 * A backend failure prevents subsequent appends after an uncertain journal/application result.
	 */
	public void physicalCommitFailed(Throwable failure){
		gate.lock();
		try{
			if(commitFailure == null){
				fail();
				version++;
				commitFailure = failure;
			}
		}finally{
			gate.unlock();
		}
	}
	private void fail(){
		if(failed) return;
		failed = true;
	}
}
