package com.lapissea.dfs.type;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.RecursiveSelfCompilation;
import com.lapissea.dfs.internal.Runner;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.dfs.utils.IntHashSet;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.config.GlobalConfig.RELEASE_MODE;

public abstract class StagedInit{
	
	private static final boolean DO_ASYNC            = ConfigDefs.LOAD_TYPES_ASYNCHRONOUSLY.resolveVal();
	private static final int     LONG_WAIT_THRESHOLD = ConfigDefs.LONG_WAIT_THRESHOLD.resolveVal();
	private static final boolean DO_TIMESTAMPS       = LONG_WAIT_THRESHOLD>0 && Log.DEBUG;
	
	
	public static void runBaseStageTask(Runnable task){
		if(DO_ASYNC){
			Runner.run(task);
		}else{
			task.run();
		}
	}
	
	public static final int STATE_ERROR = -2, STATE_NOT_STARTED = -1, STATE_START = 0, STATE_DONE = Integer.MAX_VALUE;
	
	private int       state = STATE_NOT_STARTED;
	private Throwable e;
	
	private final Map<Integer, Instant> logCompletion = DO_TIMESTAMPS? new ConcurrentHashMap<>() : null;
	
	private       Thread       initThread;
	private final ClosableLock rLock     = ClosableLock.reentrant();
	private final Condition    condition = rLock.newCondition();
	
	protected void init(boolean runNow, Runnable init){
		init(runNow, init, null);
	}
	protected void init(boolean runNow, Runnable init, Runnable postValidate){
		if(runNow){
			if(DEBUG_VALIDATION) validateStates();
			try{
				initThread = Thread.currentThread();
				setInitState(STATE_START);
				init.run();
				setInitState(STATE_DONE);
				if(postValidate != null){
					try{
						postValidate.run();
					}catch(RecursiveSelfCompilation e){
						Log.trace("{}#red has attempted to validate but it requires itself. Giving up.", this);//TODO: a better way?
					}
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
				try(var ignored = rLock.open()){
					state = STATE_ERROR;
					this.e = e;
					condition.signalAll();
				}
			}finally{
				initThread = null;
			}
		});
	}
	
	protected final void setInitState(int state){
		try(var ignored = rLock.open()){
			if(DEBUG_VALIDATION) validateNewState(state);
			if(DO_TIMESTAMPS) logCompletion.put(state, Instant.now());
			this.state = state;
			condition.signalAll();
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
	
	private static final class WaitException extends RuntimeException{
		public WaitException(String message){
			super(message);
		}
	}
	
	protected Throwable getErr(){
		return e;
	}
	
	private void checkErr(){
		if(e == null) return;
		var eCopy = cloneE(e);
		eCopy.addSuppressed(new WaitException("Exception occurred while initializing: " + this));
		throw UtilL.uncheckedThrow(eCopy);
	}
	
	private static Throwable cloneE(Throwable e){
		try{
			var out = new ByteArrayOutputStream();
			try(var ay = new ObjectOutputStream(out)){
				ay.writeObject(e);
			}
			return (Throwable)new ObjectInputStream(new ByteArrayInputStream(out.toByteArray())).readObject();
		}catch(IOException|ClassNotFoundException e1){
			throw new RuntimeException("Failed to clone exception", e1);
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
		IntHashSet     ids      = new IntHashSet();
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
		threadCheck();
		var start = DO_TIMESTAMPS? Instant.now() : Instant.EPOCH;
		
		var threadCheck = false;
		while(true){
			if(!threadCheck && this.state>=STATE_START){
				threadCheck = true;
				threadCheck();
			}
			
			try(var ignored = rLock.open()){
				checkErr();
				if(this.state>=state) break;
				else if(RELEASE_MODE) reportPossibleLock(start);
				try{
					condition.await(1, TimeUnit.SECONDS);
				}catch(InterruptedException ignored1){ }
			}
		}
		
		checkErr();
		
		if(DO_TIMESTAMPS) logLongWait(state, start);
	}
	
	private void reportPossibleLock(Instant start){
		var t = initThread;
		if(t == null || !Log.WARN) return;
		if(Duration.between(start, Instant.now()).toMillis()<LONG_WAIT_THRESHOLD) return;
		Log.warn(
			"possible lock at {}#yellow on thread {}#yellow\n{}",
			this, t.getName(), Arrays.stream(t.getStackTrace()).map(s -> "\t" + s).collect(Collectors.joining("\n"))
		);
	}
	
	private void logLongWait(int state, Instant start){
		var end = Instant.now();
		if(logCompletion != null){
			var completionTime = logCompletion.get(state);
			if(end.isAfter(completionTime) && start.isBefore(completionTime)){
				end = completionTime;
			}
		}
		var waitTime = Duration.between(start, end);
		if(waitTime.toMillis()>LONG_WAIT_THRESHOLD){
			Log.debug("Long wait on {}#yellow in {}#yellow for {#red{}ms#}", stateToString(state), this, waitTime.toMillis());
		}
	}
	
	private void threadCheck(){
		if(initThread == Thread.currentThread()){
			throw new RuntimeException("Self deadlock: " + this);
		}
	}
}
