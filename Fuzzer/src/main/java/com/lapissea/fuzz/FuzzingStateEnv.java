package com.lapissea.fuzz;

import java.util.random.RandomGenerator;

public interface FuzzingStateEnv<State, Action, Err extends Throwable>{
	
	abstract class Marked<State, Action, Err extends Throwable> implements FuzzingStateEnv<State, Action, Err>{
		@Override
		public boolean shouldRun(FuzzSequence sequence, RunMark mark){
			if(!mark.hasSequence()) return true;
			return mark.sequence(sequence);
		}
	}
	
	abstract class JustRandom<Action, Err extends Throwable> extends Marked<RandomGenerator, Action, Err>{
		
		public interface RandomApply<Action, Err extends Throwable>{
			void applyAction(RandomGenerator rand, long actionIndex, RunMark mark) throws Err;
		}
		
		public static <Action, Err extends Throwable> JustRandom<Action, Err> of(JustRandom.RandomApply<Action, Err> rAction){
			return new JustRandom<>(){
				@Override
				public void applyAction(RandomGenerator rawRandom, long actionIndex, Action action, RunMark mark) throws Err{
					rAction.applyAction(rawRandom, actionIndex, mark);
				}
			};
		}
		
		@Override
		public RandomGenerator create(RandomGenerator random, long sequenceIndex, RunMark mark){
			return new SimpleRandom(random.nextLong());
		}
	}
	
	boolean shouldRun(FuzzSequence sequence, RunMark mark);
	void applyAction(State state, long actionIndex, Action action, RunMark mark) throws Err;
	State create(RandomGenerator random, long sequenceIndex, RunMark mark) throws Err;
}
