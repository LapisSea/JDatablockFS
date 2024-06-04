package com.lapissea.fuzz;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class Plan<TState, TAction>{
	
	private record RunInfo(FuzzConfig config, FuzzSequenceSource seed){ }
	
	private record State<TState, TAction>(Fails<TState, TAction> fails, boolean reported, RunMark mark, Optional<File> saveFile){
		State<TState, TAction> withFails(List<FuzzFail<TState, TAction>> data){
			return new State<>(new Fails.FailList<>(data), false, data.isEmpty()? RunMark.NONE : data.getFirst().mark(), saveFile);
		}
		
		State<TState, TAction> withFail(FuzzFail<TState, TAction> fail, FuzzingRunner.Stability stability){
			return new State<>(new Fails.StableFail<>(fail, stability), false, fail.mark(), saveFile);
		}
		
		State<TState, TAction> withFile(File saveFile){
			return new State<>(fails, false, mark, Optional.of(saveFile));
		}
	}
	
	private sealed interface Fails<S, A>{
		record FailList<S, A>(List<FuzzFail<S, A>> data) implements Fails<S, A>{
			public FailList{ data = List.copyOf(data); }
			@Override
			public Stream<FuzzFail<S, A>> fails(){ return data.stream(); }
		}
		
		record StableFail<S, A>(FuzzFail<S, A> fail, FuzzingRunner.Stability stability) implements Fails<S, A>{
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
	
	public static <S, a> Plan<S, a> start(FuzzingRunner<S, a, ?> runner, long seed, long totalIterations, int sequenceLength){ return start(runner, null, seed, totalIterations, sequenceLength); }
	public static <S, A> Plan<S, A> start(FuzzingRunner<S, A, ?> runner, FuzzConfig config, long seed, long totalIterations, int sequenceLength){
		return start(runner, config, new FuzzSequenceSource.LenSeed(seed, totalIterations, sequenceLength));
	}
	public static <S, A> Plan<S, A> start(FuzzingRunner<S, A, ?> runner, FuzzConfig config, FuzzSequenceSource source){
		return new Plan<>(
			runner,
			new RunInfo(config, source),
			new State<>(new Fails.FailList<>(List.of()), false, RunMark.NONE, Optional.empty())
		);
	}
	
	private final RunInfo                           runInfo;
	private final FuzzingRunner<TState, TAction, ?> runner;
	private final State<TState, TAction>            state;
	
	private Plan(FuzzingRunner<TState, TAction, ?> runner, RunInfo runInfo, State<TState, TAction> state){
		this.runner = runner;
		this.runInfo = runInfo;
		this.state = state;
	}
	
	private Plan<TState, TAction> withState(State<TState, TAction> state){
		return new Plan<>(runner, runInfo, state);
	}
	
	public Plan<TState, TAction> requireStableAction(){
		for(var sequence : runInfo.seed.all().limit(5).toList()){
			runner.ensureActionEquality(sequence);
		}
		return this;
	}
	public Plan<TState, TAction> requireSerializable(){
		for(var sequence : runInfo.seed.all().limit(5).toList()){
			runner.ensureSerializability(sequence);
		}
		return this;
	}
	
	public Plan<TState, TAction> runAll(){
		var fails = runner.run(runInfo.config, RunMark.NONE, runInfo.seed);
		return withState(state.withFails(fails));
	}
	
	public Plan<TState, TAction> runMark(){
		if(!state.mark.hasSequence() || state.fails instanceof Fails.StableFail(
			var f, var stability
		) && !(stability instanceof FuzzingRunner.Stability.Ok)){
			return this;
		}
		
		var failO = state.fails.fails().filter(f -> state.mark.sequence(f.sequence())).findAny();
		
		var sequence = failO.map(FuzzFail::sequence).orElseGet(() -> {
			return runInfo.seed.all().filter(state.mark::sequence).findAny()
			                   .orElseThrow(() -> new IllegalStateException("Marked a non existent sequence"));
		});
		
		System.out.println(
			"Running sequence " + sequence +
			(state.mark.hasAction()?
			 " at " + state.mark.action() + " (" + (state.mark.action() - sequence.startIndex()) + ") action" :
			 "")
		);
		
		var fail = runner.runSequence(state.mark, sequence);
		
		return withState(state.withFails(fail.stream().toList()));
	}
	
	public Plan<TState, TAction> ifHasFail(Function<Plan<TState, TAction>, Plan<TState, TAction>> branch){
		if(!state.fails.hasAny()) return this;
		return branch.apply(this);
	}
	
	public Plan<TState, TAction> report(){
		switch(state.fails){
			case Fails.FailList<TState, TAction>(var fails) -> {
				if(fails.isEmpty()){
					System.out.println("There were no errors");
				}else{
					System.err.println(FuzzFail.report(fails));
				}
			}
			case Fails.StableFail<TState, TAction>(var fail, var stability) -> {
				if(stability instanceof FuzzingRunner.Stability.Ok){
					System.out.println(stability.makeReport());
					System.err.println(fail.trace());
				}else{
					System.err.println(stability.makeReport());
				}
			}
		}
		if(state.reported) return this;
		return withState(new State<>(state.fails, true, state.mark, state.saveFile));
	}
	public Plan<TState, TAction> clearUnstable(){
		return switch(state.fails){
			case Fails.StableFail(var fail, var stability) when !(stability instanceof FuzzingRunner.Stability.Ok) -> {
				yield withState(new State<>(new Fails.FailList<>(List.of()), false, RunMark.NONE, state.saveFile));
			}
			default -> this;
		};
	}
	public Plan<TState, TAction> failWhere(Predicate<FuzzFail<TState, TAction>> filter){
		var newFails = state.fails.fails().filter(filter).toList();
		var mark     = state.mark;
		if(mark.hasSequence()){
			mark = newFails.isEmpty()? RunMark.NONE : newFails.getFirst().mark();
		}
		return withState(new State<>(new Fails.FailList<>(newFails), false, mark, state.saveFile));
	}
	
	public Plan<TState, TAction> stableFail(int reruns){
		return switch(state.fails){
			case Fails.FailList(var fails) -> {
				if(fails.isEmpty()) yield this;
				var fail      = fails.getFirst();
				var stability = runner.establishFailStability(fail, reruns);
				yield withState(state.withFail(fail, stability));
			}
			case Fails.StableFail(var fail, var stability) -> {
				if(!(stability instanceof FuzzingRunner.Stability.Ok)){
					throw new AssertionError(stability.makeReport());
				}
				var stab = runner.establishFailStability(fail, reruns);
				yield withState(state.withFail(fail, stab));
			}
		};
	}
	
	public Plan<TState, TAction> assertFail(){
		if(state.fails.hasAny()){
			String report;
			if(state.reported) report = "There are fails";
			else{
				report = "\n" + switch(state.fails){
					case Fails.FailList<TState, TAction>(var fails) -> FuzzFail.report(fails);
					case Fails.StableFail<TState, TAction>(var fail, var stability) -> {
						if(stability instanceof FuzzingRunner.Stability.FailsNotSame){
							yield stability.makeReport();
						}else{
							yield stability.makeReport() + "\n" + fail.trace();
						}
					}
				} + "\nAssertion stack trace:";
			}
			throw new AssertionError(report);
		}
		return this;
	}
	
	public Plan<TState, TAction> loadFail(File file){
		var fails = state.fails;
		var mark  = state.mark;
		if(file.exists() && file.isFile() && file.canRead()){
			try{
				FuzzFail<TState, TAction> fail;
				try(var in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))){
					//noinspection unchecked
					fail = (FuzzFail<TState, TAction>)in.readObject();
				}
				if(state.mark.hasSequence() && runInfo.seed.all().noneMatch(state.mark::sequence)){
					throw new IllegalStateException("Saved a non existent sequence");
				}
				
				fails = new Fails.FailList<>(List.of(fail));
				mark = fail.mark();
				System.out.println("Loaded fail: " + fail);
			}catch(Throwable e){
				System.err.println("Failed to load data: " + e);
			}
		}
		
		return withState(new State<>(fails, false, mark, Optional.of(file)));
	}
	
	public Plan<TState, TAction> configMod(Function<FuzzConfig, FuzzConfig> mod){
		var cfg = runInfo.config;
		if(cfg == null) cfg = new FuzzConfig();
		cfg = mod.apply(cfg);
		return new Plan<>(runner, new RunInfo(cfg, runInfo.seed), state);
	}
	
	public Plan<TState, TAction> saveFail(){ return saveFail(null); }
	public Plan<TState, TAction> saveFail(File file){
		var fileInf = file != null? file : state.saveFile.orElseThrow(
			() -> new IllegalStateException("File can not be inferred from context"));
		
		FuzzFail<TState, TAction> fail = switch(state.fails){
			case Fails.FailList(var f) -> f.isEmpty()? null : f.getFirst();
			case Fails.StableFail(var f, var stability) -> stability instanceof FuzzingRunner.Stability.Ok? f : null;
		};
		if(fail != null){
			System.out.println("Saving fail");
			var buff = new ByteArrayOutputStream();
			try(var out = new ObjectOutputStream(buff)){
				out.writeObject(fail);
			}catch(NotSerializableException e){
				new RuntimeException("Fail not saved because it is not serializable", e).printStackTrace();
			}catch(Throwable e){
				System.err.println("Failed to save data: " + e);
			}
			
			var parent = fileInf.getParentFile();
			if(parent != null) parent.mkdir();
			try(var out = new FileOutputStream(fileInf)){
				buff.writeTo(out);
			}catch(Throwable e){
				System.err.println("Failed to save data: " + e);
				fileInf.delete();
			}
		}else{
			fileInf.delete();
		}
		
		return withState(state.withFile(fileInf));
	}
}
