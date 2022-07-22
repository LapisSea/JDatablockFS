package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.logging.EventEnvironment;
import com.lapissea.util.UtilL;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static com.lapissea.cfs.GlobalConfig.PRINT_COMPILATION;

public abstract class StagedInit{
	
	private static final boolean DO_ASYNC=UtilL.sysPropertyByClass(StagedInit.class, "DO_ASYNC", true, Boolean::valueOf);
	
	private static final ThreadLocal<Deque<StagedInit>> INIT_STACK=ThreadLocal.withInitial(LinkedList::new);
	
	private record StageSnap(StagedInit val, int state, Thread thread, Instant time){
		
		static StageSnap of(StagedInit val){
			if(val==null) return null;
			var now=Instant.now();
			return new StageSnap(val, val.getEstimatedState(), Thread.currentThread(), now);
		}
		
		@Override
		public String toString(){
			return val+" at "+val.stateToStr(state);
		}
	}
	
	private record StageEvent(List<EventEnvironment.EventReference> references, StageSnap subject){}
	
	private static final Instant                                 START       =Instant.now();
	private static final EventEnvironment                        STAGE_EVENTS=new EventEnvironment("Stage events");
	private static final EventEnvironment.AdaptedLog<StageEvent> ELOG        =STAGE_EVENTS.addaptLog((log, e)->{

//		if(e.cause!=null){
//			var ref=new EventEnvironment.Event(Duration.between(START, e.cause.time).toNanos(), e.cause.thread.getName(), e.cause.toString(), List.of());
//			log.accept(ref);
//			refs=List.of(new EventEnvironment.EventReference(ref, e.note));
//		}
		
		log.accept(new EventEnvironment.Event(Duration.between(START, e.subject.time).toNanos(), e.subject.thread.getName(), e.subject.toString(), e.references));
	});
	
	static{
		if(PRINT_COMPILATION){
			Runtime.getRuntime().addShutdownHook(new Thread(()->{
				try{
					emit();
				}catch(IOException ex){
					ex.printStackTrace();
				}
			}));
		}
	}
	
	private static void emit() throws IOException{
		var mem=STAGE_EVENTS.emit();
		
		try(var out=new FileOutputStream("events.bin")){
			mem.transferTo(out);
		}
	}
	
	private static Supplier<List<EventEnvironment.Event>> log(List<EventEnvironment.EventReference> cause, StagedInit subject){
		return ELOG.log(new StageEvent(cause, StageSnap.of(subject)));
	}
	private static Optional<EventEnvironment.Event> makeEvent(StageSnap snap){
		if(snap==null) return Optional.empty();
		return Optional.of(new EventEnvironment.Event(Duration.between(START, snap.time).toNanos(), snap.thread.getName(), snap.toString(), List.of()));
	}
	
	
	public static void runBaseStageTask(Runnable task){
		if(DO_ASYNC){
			Runner.compileTask(task);
		}else{
			task.run();
		}
	}
	
	public static final int STATE_NOT_STARTED=-1, STATE_START=0, STATE_DONE=Integer.MAX_VALUE;
	
	private final Lock      lock     =new ReentrantLock();
	private final Condition condition=lock.newCondition();
	private       int       state    =STATE_NOT_STARTED;
	private       Throwable e;
	
	
	protected void init(boolean runNow, Runnable init){
		var cause=getCause();
		if(runNow){
			EventEnvironment.Event start=null;
			try{
				setInitState(STATE_START);
				start=logStart(cause);
				init.run();
				setInitState(STATE_DONE);
				logEnd(cause, start);
			}catch(Throwable e){
				logFail(cause, start);
				throw e;
			}
			return;
		}
		runBaseStageTask(()->{
			EventEnvironment.Event start=null;
			try{
				setInitState(STATE_START);
				start=logStart(cause);
				init.run();
				setInitState(STATE_DONE);
				logEnd(cause, start);
			}catch(Throwable e){
				logFail(cause, start);
				this.e=e;
				try{
					lock.lock();
					condition.signalAll();
				}finally{
					lock.unlock();
				}
			}
		});
	}
	
	protected final void setInitState(int state){
		try{
			lock.lock();
			this.state=state;
			condition.signalAll();
		}finally{
			lock.unlock();
		}
	}
	
	
	private static StageSnap getCause(){
		if(!PRINT_COMPILATION) return null;
		var s=INIT_STACK.get();
		return s.isEmpty()?null:StageSnap.of(s.getLast());
	}
	
	private void logFail(StageSnap cause, EventEnvironment.Event start){
		if(!PRINT_COMPILATION) return;
		
		var ref     =makeEvent(cause).map(e->new EventEnvironment.EventReference(e, "FAIL"));
		var startRef=new EventEnvironment.EventReference(start, "Start");
		
		log(ref.isEmpty()?List.of(startRef):List.of(ref.get(), startRef), this);
		
		var s=INIT_STACK.get();
		if(!s.remove(this)) throw new RuntimeException();
	}
	
	private void logEnd(StageSnap cause, EventEnvironment.Event start){
		if(!PRINT_COMPILATION) return;
		
		var ref     =makeEvent(cause).map(e->new EventEnvironment.EventReference(e, "Done"));
		var startRef=new EventEnvironment.EventReference(start, "Start");
		
		log(ref.isEmpty()?List.of(startRef):List.of(ref.get(), startRef), this);
		
		var s=INIT_STACK.get();
		if(!s.remove(this)) throw new RuntimeException();
	}
	private EventEnvironment.Event logStart(StageSnap cause){
		if(!PRINT_COMPILATION) return null;
		
		var ref=makeEvent(cause).map(e->new EventEnvironment.EventReference(e, "Cause"));
		
		var es=log(ref.map(List::of).orElse(List.of()), this);
		
		var s=INIT_STACK.get();
		s.addLast(this);
		
		var all=es.get();
		return all.get(all.size()-1);
	}
	
	public final void waitForState(int state){
		if(this.state<state){
			waitForState0(state);
			assert this.state>=state;
		}else{
//			log(getCause(), this, "No wait");
		}
		
		checkErr();
	}
	
	protected final int getEstimatedState(){
		return state;
	}
	
	public static class WaitException extends RuntimeException{
		public WaitException(String message, Throwable cause){
			super(message, cause);
		}
	}
	
	protected final Throwable getErr(){
		try{
			lock.lock();
			return e;
		}finally{
			lock.unlock();
		}
	}
	
	private void checkErr(){
		try{
			lock.lock();
			
			if(e==null) return;
			throw new WaitException("Exception occurred while initializing: "+this, e);
		}finally{
			lock.unlock();
		}
	}
	
	protected String stateToStr(int state){
		return switch(state){
			case STATE_NOT_STARTED -> "NOT_STARTED";
			case STATE_START -> "START";
			case STATE_DONE -> "DONE";
			default -> "<"+state+">";
		};
	}
	
	private void waitForState0(int state){
		
		var                                    cause=getCause();
		Supplier<List<EventEnvironment.Event>> es   =null;
		if(PRINT_COMPILATION){
			var ref=makeEvent(cause).map(e->new EventEnvironment.EventReference(e, "Wait for "+stateToStr(state)));
			es=log(ref.map(List::of).orElse(List.of()), this);
		}
		
		while(true){
			try{
				lock.lock();
				checkErr();
				if(this.state>=state){
					if(PRINT_COMPILATION){
						List<EventEnvironment.EventReference> refs;
						if(es==null) refs=List.of();
						else{
							var ls  =es.get();
							var last=ls.get(ls.size()-1);
							refs=List.of(new EventEnvironment.EventReference(last, "Waited for "+stateToStr(state)));
						}
						log(refs, this);
					}
					return;
				}
				try{
					condition.await();
				}catch(InterruptedException ignored){}
			}finally{
				lock.unlock();
			}
		}
	}
}
