package com.lapissea.dfs.type;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.RecursiveSelfCompilation;
import com.lapissea.dfs.internal.Runner;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.dfs.utils.IntHashSet;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.function.Consumer;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.config.GlobalConfig.RELEASE_MODE;

public abstract class StagedInit implements Stringify{
	
	private static final Duration LONG_WAIT_THRESHOLD = ConfigDefs.LONG_WAIT_THRESHOLD.resolveLocking();
	private static final boolean  DO_TIMESTAMPS       = LONG_WAIT_THRESHOLD != null && Log.DEBUG;
	
	private static class InitInfo{
		private Throwable error;
		private Thread    ctxThread;
		
		private final Map<Integer, Instant> logCompletion   = DO_TIMESTAMPS? new ConcurrentHashMap<>() : null;
		private final ClosableLock          stateLock       = ClosableLock.reentrant();
		private final Condition             conditionChange = stateLock.newCondition();
		
		@Override
		public String toString(){
			if(error != null) return "Error: " + error;
			var t = ctxThread;
			if(t == null) return "Done";
			return "Running in " + t;
		}
	}
	
	public static final int STATE_ERROR = -2, STATE_NOT_STARTED = -1, STATE_START = 0, STATE_DONE = Integer.MAX_VALUE;
	
	private int      state    = STATE_NOT_STARTED;
	private InitInfo initInfo = new InitInfo();
	
	private Map<Integer, String> statesCache;
	
	protected interface Init{
		void init(int state);
	}
	protected void init(int runTillState, Init multiInit, Runnable postValidate){
		
		var stages = new ArrayDeque<>(
			listStates().mapToInt(StateInfo::id)
			            .removing(STATE_ERROR, STATE_NOT_STARTED, STATE_START)
			            .sorted()
			            .box().asCollection()
		);
		boolean syncStart = runTillState>STATE_NOT_STARTED;
		if(syncStart){
			if(DEBUG_VALIDATION) validateStates();
			
			initInfo.ctxThread = Thread.currentThread();
			
			setInitState(STATE_START);
			
			Integer stage;
			while((stage = stages.peekFirst()) != null){
				if(stage>runTillState){
					break;
				}
				stages.removeFirst();
				
				multiInit.init(stage);
				setInitState(stage);
			}
			
			if(stages.isEmpty()){
				if(postValidate != null){
					try{
						postValidate.run();
					}catch(RecursiveSelfCompilation e){
						Log.trace("{}#red has attempted to validate but it requires itself. Giving up.", this);//TODO: a better way?
					}
				}
				initInfo = null;
				return;
			}
			initInfo.ctxThread = null;
		}
		
		Runner.run(() -> {
			if(DEBUG_VALIDATION) validateStates();
			try{
				initInfo.ctxThread = Thread.currentThread();
				
				if(!syncStart) setInitState(STATE_START);
				
				for(var stage : stages){
					multiInit.init(stage);
					setInitState(stage);
				}
				
				if(postValidate != null){
					postValidate.run();
				}
				initInfo = null;
			}catch(Throwable e){
				try(var ignored = initInfo.stateLock.open()){
					state = STATE_ERROR;
					initInfo.error = e;
					initInfo.conditionChange.signalAll();
				}
			}
		}, "Init->" + this.toShortString());
	}
	
	protected final void setInitState(int state){
		var i = initInfo;
		if(i == null || i.error != null) throw new IllegalCallerException("The state must be set only in the init block!");
		if(i.ctxThread != Thread.currentThread()) throw new IllegalCallerException("The state should be called only by the init thread");
		if(DEBUG_VALIDATION) validateNewState(state);
		try(var ignored = i.stateLock.open()){
			if(DO_TIMESTAMPS) i.logCompletion.put(state, Instant.now());
			this.state = state;
			i.conditionChange.signalAll();
		}
	}
	
	private void validateNewState(int state){
		if(getStateName(state).isEmpty()){
			throw new IllegalArgumentException(Log.fmt("Unknown state: {}#red", state));
		}
		var ts = this.state;
		if(state<=ts){
			throw new IllegalArgumentException(
				Log.fmt("State must advance! Current state: {}#yellow, New state: {}#red", stateToString(ts), stateToString(state))
			);
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
		var from = getInitializationState();
		actuallyWaitForState(state);
		var t2 = System.nanoTime();
		var to = getInitializationState();
		return new Timing(this, (t2 - t1)/1000000D, from, to);
	}
	
	public final void waitForStateDone(){
		if(this.state == STATE_DONE) return;
		actuallyWaitForState(STATE_DONE);
	}
	
	public final void waitForState(int state){
		if(this.state>=state) return;
		// This is a separate method as it is called rarely and often does not need to be inlined or even compiled.
		// Rare path as function a day keeps the "Inlined: No, Too big" away
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
			}, this.toShortString() + "->" + stateToString(state));
		}
	}
	
	public int getInitializationState(){
		return state;
	}
	
	private static final class WaitException extends RuntimeException{
		public WaitException(String message){
			super(message);
		}
	}
	
	protected final Throwable getErr(){
		var i = initInfo;
		return i != null? i.error : null;
	}
	
	private void checkErr(){
		var e = getErr();
		if(e == null) return;
		throwWaitErr(e);
	}
	private void throwWaitErr(Throwable e){
		try{
			e = cloneE(e);
		}catch(Exception ignore){
			throw UtilL.uncheckedThrow(e);
		}
		var last = e;
		while(last.getCause() != null) last = last.getCause();
		last.addSuppressed(new WaitException("Exception occurred while initializing: " + this));
		throw UtilL.uncheckedThrow(e);
	}
	
	private static Throwable cloneE(Throwable e) throws Exception{
		var out = new ByteArrayOutputStream();
		try(var ay = new ObjectOutputStream(out)){
			ay.writeObject(e);
		}
		return (Throwable)new ObjectInputStream(new ByteArrayInputStream(out.toByteArray())).readObject();
	}
	
	public record StateInfo(int id, String name){
		private static final List<StateInfo> BASE_STATES = List.of(
			new StateInfo(STATE_ERROR, "ERROR"),
			new StateInfo(STATE_NOT_STARTED, "NOT_STARTED"),
			new StateInfo(STATE_START, "START"),
			new StateInfo(STATE_DONE, "DONE")
		);
		
		private static final int MIN_ID = Iters.from(BASE_STATES).mapToInt(StateInfo::id).min().orElseThrow();
	}
	
	protected String stateToString(int state){
		return getStateName(state).orElseGet(() -> "UNKNOWN_STATE_" + state);
	}
	
	protected Optional<String> getStateName(int stateId){
		return Optional.ofNullable(getStates().get(stateId));
	}
	
	private Map<Integer, String> getStates(){
		var s = statesCache;
		if(s == null) s = statesCache = listStates().toMap(StateInfo::id, StateInfo::name);
		return s;
	}
	
	/***
	 * This method lists information about all states that this object can be in. This method is only
	 * called when debugging or calling toString so performance is not of great concern.
	 */
	protected IterablePP<StateInfo> listStates(){
		return Iters.from(StateInfo.BASE_STATES);
	}
	
	protected final void validateStates(){
		IntHashSet     ids      = new IntHashSet();
		Set<String>    names    = new HashSet<>();
		List<String>   problems = new ArrayList<>();
		Set<StateInfo> base     = new HashSet<>(StateInfo.BASE_STATES);
		
		var states = listStates().toModList();
		states.forEach(state -> {
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
			str.add(TextUtil.toTable(Iters.from(states).sortedByI(StateInfo::id)));
			throw new ShouldNeverHappenError(str.toString());
		}
		
	}
	
	
	private void actuallyWaitForState(int state){
		var info = initInfo;
		if(info == null) return;//If no info, then the object is initialized
		
		threadCheck(info.ctxThread);
		var start = DO_TIMESTAMPS? Instant.now() : Instant.EPOCH;
		
		var threadCheck = false;
		while(true){
			if(!threadCheck && this.state>=STATE_START){
				threadCheck = true;
				threadCheck(info.ctxThread);
			}
			try(var ignored = info.stateLock.open()){
				checkErr();
				if(this.state>=state) break;
				else if(RELEASE_MODE) reportPossibleLock(start, info.ctxThread);
				try{
					info.conditionChange.await(1, TimeUnit.SECONDS);
				}catch(InterruptedException ignored1){ }
			}
		}
		
		checkErr();
		
		if(DO_TIMESTAMPS) logLongWait(state, start, info);
	}
	
	private void reportPossibleLock(Instant start, Thread initThread){
		if(initThread == null || !DO_TIMESTAMPS) return;
		if(Duration.between(start, Instant.now()).compareTo(LONG_WAIT_THRESHOLD)<0) return;
		Log.warn(
			"possible lock at {}#yellow on thread {}#yellow\n{}",
			this, initThread.getName(), Iters.from(initThread.getStackTrace()).joinAsStr("\n", s -> "\t" + s)
		);
	}
	
	private void logLongWait(int state, Instant start, InitInfo info){
		var end = Instant.now();
		if(info.logCompletion != null){
			var completionTime = info.logCompletion.get(state);
			if(end.isAfter(completionTime) && start.isBefore(completionTime)){
				end = completionTime;
			}
		}
		var waitTime = Duration.between(start, end);
		if(waitTime.compareTo(LONG_WAIT_THRESHOLD)>0){
			Log.debug("Long wait on {}#yellow in {}#yellow for {#red{}ms#}", stateToString(state), this, waitTime.toMillis());
		}
	}
	
	private void threadCheck(Thread initThread){
		if(initThread == Thread.currentThread()){
			throw new RuntimeException("Self deadlock: " + this);
		}
	}
}
