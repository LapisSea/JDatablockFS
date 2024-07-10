package com.lapissea.fuzz;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

public sealed interface LogMessage{
	record State(
		double progress,
		Optional<Duration> estimatedTotalTime, Optional<Duration> estimatedTimeRemaining,
		Duration elapsed, OptionalDouble nanosecondsPerOp,
		boolean hasFail
	) implements LogMessage{
		public State{
			Objects.requireNonNull(estimatedTotalTime);
			Objects.requireNonNull(estimatedTimeRemaining);
			Objects.requireNonNull(elapsed);
			Objects.requireNonNull(nanosecondsPerOp);
		}
	}
	
	record Start(Optional<String> fuzzName) implements LogMessage{ }
	
	record End() implements LogMessage{ }
	
	record CustomMessage(String message) implements LogMessage{ }
}
