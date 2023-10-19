package com.lapissea.fuzz;

import java.util.OptionalLong;

public record RunMark(long sequence, long action){
	public static final RunMark NONE = new RunMark(-1, -1);
	
	public OptionalLong optAction()               { return hasAction()? OptionalLong.of(action) : OptionalLong.empty(); }
	public boolean hasSequence()                  { return sequence != -1; }
	public boolean hasAction()                    { return action != -1; }
	public boolean sequence(FuzzSequence sequence){ return this.sequence == sequence.index(); }
	public boolean sequence(long sequenceIndex)   { return sequence == sequenceIndex; }
	public boolean action(long actionIndex)       { return action == actionIndex; }
}
