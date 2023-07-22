package com.lapissea.cfs.run.fuzzing;

import com.lapissea.cfs.run.fuzzing.FuzzingRunner.Mark;
import com.lapissea.cfs.run.fuzzing.FuzzingRunner.Stability;
import com.lapissea.cfs.run.fuzzing.FuzzingRunner.Stability.Ok;
import com.lapissea.util.LogUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class Plan<TState, TAction>{
	
	private record RunInfo(FuzzingRunner.Config config, long seed, long totalIterations, int sequenceLength){
		FuzzSequenceSource.LenSeed makeSeed(){ return new FuzzSequenceSource.LenSeed(seed, totalIterations, sequenceLength); }
	}
	
	private record State<TState, TAction>(Fails<TState, TAction> fails, Mark mark, Optional<File> saveFile){
		State<TState, TAction> withFails(List<FuzzFail<TState, TAction>> data){
			return new State<>(new Fails.FailList<>(data), data.isEmpty()? Mark.NONE : data.get(0).mark(), saveFile);
		}
		
		State<TState, TAction> withFail(FuzzFail<TState, TAction> fail, Stability stability){
			return new State<>(new Fails.StableFail<>(fail, stability), fail.mark(), saveFile);
		}
		
		State<TState, TAction> withFile(File saveFile){
			return new State<>(fails, mark, Optional.of(saveFile));
		}
	}
	
	private sealed interface Fails<S, A>{
		record FailList<S, A>(List<FuzzFail<S, A>> data) implements Fails<S, A>{
			public FailList{ data = List.copyOf(data); }
			@Override
			public Stream<FuzzFail<S, A>> fails(){ return data.stream(); }
		}
		
		record StableFail<S, A>(FuzzFail<S, A> fail, Stability stability) implements Fails<S, A>{
			public StableFail{
				Objects.requireNonNull(fail);
				Objects.requireNonNull(stability);
			}
			@Override
			public Stream<FuzzFail<S, A>> fails(){ return Stream.of(fail); }
		}
		Stream<FuzzFail<S, A>> fails();
		default boolean hasAny(){ return fails().findAny().isPresent(); }
	}
	
	private interface Step<TState, TAction>{
		State<TState, TAction> execute(State<TState, TAction> state, FuzzingRunner<TState, TAction, ?> runner);
	}
	
	public static <TState, TAction> Plan<TState, TAction> start(long seed, long totalIterations, int sequenceLength){ return start(null, seed, totalIterations, sequenceLength); }
	public static <TState, TAction> Plan<TState, TAction> start(FuzzingRunner.Config config, long seed, long totalIterations, int sequenceLength){
		return new Plan<>(List.of(), new RunInfo(config, seed, totalIterations, sequenceLength));
	}
	
	private final List<Step<TState, TAction>> members;
	private final RunInfo                     runInfo;
	private Plan(List<Step<TState, TAction>> members, RunInfo runInfo, Step<TState, TAction> next){
		this.runInfo = runInfo;
		var tmp = new ArrayList<Step<TState, TAction>>(members.size() + 1);
		tmp.addAll(members);
		tmp.add(next);
		this.members = List.copyOf(tmp);
	}
	private Plan(List<Step<TState, TAction>> members, RunInfo runInfo){
		this.members = List.copyOf(members);
		this.runInfo = runInfo;
	}
	
	public Plan<TState, TAction> runAll(){
		return new Plan<>(members, runInfo, (state, runner) -> {
			var fails = runner.run(runInfo.config, Mark.NONE, runInfo.makeSeed());
			return state.withFails(fails);
		});
	}
	
	public Plan<TState, TAction> runMark(){ return new Plan<>(members, runInfo, this::runMark); }
	private State<TState, TAction> runMark(State<TState, TAction> state, FuzzingRunner<TState, TAction, ?> runner){
		if(!state.mark.hasSequence() || state.fails instanceof Fails.StableFail(var f, var stability) && !(stability instanceof Ok)){
			return state;
		}
		
		var failO = state.fails.fails().filter(f -> state.mark.sequence(f.sequence())).findAny();
		
		var sequence = failO.map(FuzzFail::sequence).orElseGet(() -> {
			return runInfo.makeSeed().all().filter(state.mark::sequence).findAny()
			              .orElseThrow(() -> new IllegalStateException("Marked a non existent sequence"));
		});
		
		System.out.println(
			"Running sequence " + sequence +
			(state.mark.hasAction()?
			 " at " + state.mark.action() + " (" + (state.mark.action() - sequence.startIndex()) + ") action" :
			 "")
		);
		
		var fail = runner.runSequence(state.mark, sequence);
		
		return state.withFails(fail.stream().toList());
	}
	
	public Plan<TState, TAction> ifHasFail(Function<Plan<TState, TAction>, Plan<TState, TAction>> branch){
		var ifPlan = branch.apply(new Plan<>(List.of(), runInfo));
		return new Plan<>(members, runInfo, (state, runner) -> {
			if(!state.fails.hasAny()) return state;
			return ifPlan.execute(state, runner);
		});
	}
	public Plan<TState, TAction> report(){
		return new Plan<>(members, runInfo, (state, runner) -> {
			switch(state.fails){
				case Fails.FailList<TState, TAction>(var fails) -> {
					if(fails.isEmpty()){
						System.out.println("There were no errors");
					}else{
						System.err.println(FuzzFail.report(fails));
					}
				}
				case Fails.StableFail<TState, TAction>(var fail, var stability) -> {
					if(stability instanceof Ok){
						System.out.println(stability.makeReport());
						System.err.println(fail.trace());
					}else{
						System.err.println(stability.makeReport());
					}
				}
			}
			return state;
		});
	}
	public Plan<TState, TAction> clearUnstable(){
		return new Plan<>(members, runInfo, (state, runner) -> {
			return switch(state.fails){
				case Fails.StableFail(var fail, var stability) when !(stability instanceof Ok) -> {
					yield new State<>(new Fails.FailList<>(List.of()), Mark.NONE, state.saveFile);
				}
				default -> state;
			};
		});
	}
	public Plan<TState, TAction> failWhere(Predicate<FuzzFail<TState, TAction>> filter){
		return new Plan<>(members, runInfo, (state, runner) -> {
			var newFails = state.fails.fails().filter(filter).toList();
			var mark     = state.mark;
			if(mark.hasSequence()){
				mark = newFails.isEmpty()? Mark.NONE : newFails.get(0).mark();
			}
			return new State<>(new Fails.FailList<>(newFails), mark, state.saveFile);
		});
	}
	
	public Plan<TState, TAction> stableFail(int reruns){
		if(reruns<1) throw new IllegalArgumentException();
		return new Plan<>(members, runInfo, (state, runner) -> {
			return switch(state.fails){
				case Fails.FailList(var fails) -> {
					if(fails.isEmpty()) yield state;
					var fail      = fails.get(0);
					var stability = runner.establishFailStability(fail, reruns);
					yield state.withFail(fail, stability);
				}
				case Fails.StableFail(var fail, var stability) -> {
					if(!(stability instanceof Ok)){
						throw new AssertionError(stability.makeReport());
					}
					var stab = runner.establishFailStability(fail, reruns);
					yield state.withFail(fail, stab);
				}
			};
		});
	}
	
	public Plan<TState, TAction> assertFail(){
		return new Plan<>(members, runInfo, (state, runner) -> {
			if(state.fails.hasAny()){
				var ass = new AssertionError("There are fails");
				ass.setStackTrace(new StackTraceElement[0]);
				throw ass;
			}
			return state;
		});
	}
	
	public Plan<TState, TAction> loadFail(File file){
		return new Plan<>(members, runInfo, (state, runner) -> {
			var fails = state.fails;
			var mark  = state.mark;
			if(file.exists() && file.isFile() && file.canRead()){
				try{
					FuzzFail<TState, TAction> fail;
					try(var in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))){
						//noinspection unchecked
						fail = (FuzzFail<TState, TAction>)in.readObject();
					}
					if(state.mark.hasSequence() && runInfo.makeSeed().all().noneMatch(state.mark::sequence)){
						throw new IllegalStateException("Saved a non existent sequence");
					}
					
					fails = new Fails.FailList<>(List.of(fail));
					mark = fail.mark();
					System.out.println("Loaded fail: " + fail);
				}catch(Throwable e){
					System.err.println("Failed to load data: " + e);
				}
			}
			
			return new State<>(fails, mark, Optional.of(file));
		});
	}
	public Plan<TState, TAction> saveFail(){ return saveFail(null); }
	public Plan<TState, TAction> saveFail(File file){
		return new Plan<>(members, runInfo, (state, runner) -> {
			var fileInf = file != null? file : state.saveFile.orElseThrow(
				() -> new IllegalStateException("File can not be inferred from context"));
			
			FuzzFail<TState, TAction> fail = switch(state.fails){
				case Fails.FailList(var f) -> f.isEmpty()? null : f.get(0);
				case Fails.StableFail(var f, var stability) -> stability instanceof Ok? f : null;
			};
			if(fail != null){
				LogUtil.println("saving fail");
				try(var out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(fileInf)))){
					out.writeObject(fail);
				}catch(NotSerializableException e){
					throw new RuntimeException(e);
				}catch(Throwable e){
					System.err.println("Failed to save data: " + e);
				}
			}else{
				fileInf.delete();
			}
			
			return state.withFile(fileInf);
		});
	}
	
	public void execute(FuzzingRunner<TState, TAction, ?> runner){
		var state = new State<TState, TAction>(new Fails.FailList<>(List.of()), Mark.NONE, Optional.empty());
		execute(state, runner);
	}
	private State<TState, TAction> execute(State<TState, TAction> initialState, FuzzingRunner<TState, TAction, ?> runner){
		var state = initialState;
		for(var member : members){
			state = member.execute(state, runner);
		}
		return state;
	}
}
