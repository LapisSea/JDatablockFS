package com.lapissea.fuzz;

import java.util.Objects;

public sealed interface SequenceOutcome<S, A>{
	record Ok<S, A>() implements SequenceOutcome<S, A>{ }
	
	record Failed<S, A>(FuzzFail<S, A> failure) implements SequenceOutcome<S, A>{
		public Failed{ Objects.requireNonNull(failure); }
	}
	
	record Cancelled<S, A>() implements SequenceOutcome<S, A>{ }
}
