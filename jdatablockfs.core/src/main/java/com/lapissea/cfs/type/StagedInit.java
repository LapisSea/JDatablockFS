package com.lapissea.cfs.type;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.logging.Log;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class StagedInit{
	
	private static final boolean DO_ASYNC            = GlobalConfig.configFlag("loading.async", true);
	private static final int     LONG_WAIT_THRESHOLD = GlobalConfig.configInt("loading.longWaitThreshold", 300);
	
	public static void runBaseStageTask(Runnable task){
		if(DO_ASYNC){
			Runner.run(task);
		}else{
			task.run();
		}
	}
	
	public static final int STATE_ERROR = -2, STATE_NOT_STARTED = -1, STATE_START = 0, STATE_DONE = Integer.MAX_VALUE;
	
	private       int       state = STATE_NOT_STARTED;
	private       Throwable e;
	private final Object    lock  = new Object();
	private       Thread    initThread;
	
	protected void init(boolean runNow, Runnable init){
		init(runNow, init, null);
	}
	protected void init(boolean runNow, Runnable init, Runnable postValidate){
		if(runNow){
			if(DEBUG_VALIDATION) validateStates();
			initThread = Thread.currentThread();
			try{
				setInitState(STATE_START);
				init.run();
				setInitState(STATE_DONE);
				if(postValidate != null){
					postValidate.run();
				}
			}finally{
				initThread = null;
			}
			
			return;
		}
		runBaseStageTask(() -> {
			if(DEBUG_VALIDATION) validateStates();
			try{
				initThread = Thread.currentThread();
				setInitState(STATE_START);
				init.run();
				setInitState(STATE_DONE);
				if(postValidate != null){
					postValidate.run();
				}
			}catch(Throwable e){
				state = STATE_ERROR;
				this.e = e;
				synchronized(lock){
					lock.notifyAll();
				}
			}finally{
				initThread = null;
			}
		});
	}
	
	protected final void setInitState(int state){
		synchronized(lock){
			if(DEBUG_VALIDATION) validateNewState(state);
			this.state = state;
			lock.notifyAll();
		}
	}
	
	private void validateNewState(int state){
		if(listStates().mapToInt(StateInfo::id).noneMatch(id -> id == state)){
			throw new IllegalArgumentException("Unknown state: " + state);
		}
		if(state<=this.state){
			throw new IllegalArgumentException("State must advance! Current state: " + stateToString(this.state) + ", Requested state: " + stateToString(state));
		}
	}
	
	public record Timing(StagedInit owner, double ms, int from, int to){
		@Override
		public String toString(){
			return "{" + owner.stateToString(from) + "..." + owner.stateToString(to) + " - " + ms + "}";
		}
	}
	
	public final Timing waitForStateTimed(int state){
		if(this.state>=state) return null;
		var t1   = System.nanoTime();
		var from = getEstimatedState();
		actuallyWaitForState(state);
		var t2 = System.nanoTime();
		var to = getEstimatedState();
		return new Timing(this, (t2 - t1)/1000000D, from, to);
	}
	
	public final void waitForStateDone(){
		if(this.state == STATE_DONE) return;
		actuallyWaitForState(STATE_DONE);
	}
	
	public final void waitForState(int state){
		if(this.state>=state) return;
		actuallyWaitForState(state);
	}
	
	public final void runOnStateDone(Runnable onEvent, Consumer<Throwable> onFail){
		runOnState(STATE_DONE, onEvent, onFail);
	}
	
	public final void runOnState(int state, Runnable onEvent, Consumer<Throwable> onFail){
		if(this.state>=state){
			onEvent.run();
		}else{
			Runner.run(() -> {
				try{
					waitForState(state);
				}catch(Throwable e){
					if(onFail != null) onFail.accept(e);
					return;
				}
				onEvent.run();
			});
		}
	}
	
	protected int getEstimatedState(){
		return state;
	}
	
	public static class WaitException extends RuntimeException{
		public static Throwable unwait(Throwable e){
			if(e instanceof WaitException w){
				return w.getWaitedCause();
			}
			return e;
		}
		public WaitException(String message, Throwable cause){
			super(message, Objects.requireNonNull(cause));
		}
		public Throwable getWaitedCause(){
			var c = getCause();
			if(c instanceof WaitException w){
				return w.getWaitedCause();
			}
			return c;
		}
	}
	
	protected Throwable getErr(){
		synchronized(lock){
			return e;
		}
	}
	
	private void checkErr(){
		synchronized(lock){
			if(e == null) return;
			throw new WaitException("Exception occurred while initializing: " + this, e);
		}
	}
	
	public record StateInfo(int id, String name){
		private static final List<StateInfo> BASE_STATES = List.of(
			new StateInfo(STATE_ERROR, "ERROR"),
			new StateInfo(STATE_NOT_STARTED, "NOT_STARTED"),
			new StateInfo(STATE_START, "START"),
			new StateInfo(STATE_DONE, "DONE")
		);
		
		private static final int MIN_ID = BASE_STATES.stream().mapToInt(StateInfo::id).min().orElseThrow();
	}
	
	protected String stateToString(int state){
		return listStates().filter(i -> i.id == state).map(StateInfo::name).findAny().orElseGet(() -> "UNKNOWN_STATE_" + state);
	}
	
	/***
	 * This method lists information about all states that this object can be in. This method is only
	 * called when debugging or calling toString so performance is not of great concern.
	 */
	protected Stream<StateInfo> listStates(){
		return StateInfo.BASE_STATES.stream();
	}
	
	protected final void validateStates(){
		Set<Integer>   ids      = new HashSet<>();
		Set<String>    names    = new HashSet<>();
		List<String>   problems = new ArrayList<>();
		Set<StateInfo> base     = new HashSet<>(StateInfo.BASE_STATES);
		
		listStates().forEach(state -> {
			if(state.id<StateInfo.MIN_ID) problems.add("\t" + state + ": id is too small!");
			if(!ids.add(state.id)) problems.add("\t" + state + ": has a duplicate id");
			if(!names.add(state.name)) problems.add("\t" + state + ": has a duplicate name");
			base.remove(state);
		});
		for(StateInfo stateInfo : base){
			problems.add("\t" + stateInfo + " is a base state but is not listed!");
		}
		
		if(!problems.isEmpty()){
			StringJoiner str = new StringJoiner("\n");
			str.add("Issues with state IDs for " + this.getClass().getSimpleName() + ":");
			problems.forEach(str::add);
			str.add(TextUtil.toTable(listStates().sorted(Comparator.comparingInt(StateInfo::id))));
			throw new ShouldNeverHappenError(str.toString());
		}
		
	}
	
	
	private void actuallyWaitForState(int state){
		if(initThread == Thread.currentThread()){
			throw new RuntimeException("Self deadlock");
		}
		
		long start = System.nanoTime();
		while(true){
			synchronized(lock){
				checkErr();
				if(this.state>=state){
					checkErr();
					var delta = System.nanoTime() - start;
					if(delta>LONG_WAIT_THRESHOLD*1_000_000L){
						Log.debug("Long wait on {}#yellow in {}#yellow for {#red{}ms#}", (Supplier<Object>)() -> stateToString(state), this, delta/1000_000);
					}
					return;
				}
				try{
					lock.wait();
				}catch(InterruptedException ignored){ }
			}
		}
	}
}
