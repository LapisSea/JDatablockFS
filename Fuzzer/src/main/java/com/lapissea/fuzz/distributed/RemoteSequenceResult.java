package com.lapissea.fuzz.distributed;

import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.SequenceOutcome;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.Objects;

public record RemoteSequenceResult(
	Wire.Assignment assignment, FuzzSequence sequence,
	Status status, CapturedOutput output, ExecutionStats stats
) implements Serializable{
	
	public RemoteSequenceResult(Wire.Assignment assignment, FuzzSequence sequence, Status status, CapturedOutput output){
		this(assignment, sequence, status, output, ExecutionStats.EMPTY);
	}
	
	public RemoteSequenceResult{
		Objects.requireNonNull(assignment);
		Objects.requireNonNull(sequence);
		Objects.requireNonNull(status);
		Objects.requireNonNull(output);
		Objects.requireNonNull(stats);
	}
	
	public static RemoteSequenceResult of(Wire.Assignment assignment, FuzzSequence sequence, SequenceOutcome<?, ?> outcome, ExecutionStats stats){
		return new RemoteSequenceResult(assignment, sequence, statusOf(outcome), CapturedOutput.EMPTY, stats);
	}
	
	public record ExecutionStats(long completedOperations, long executionNanos) implements Serializable{
		public static final ExecutionStats EMPTY = new ExecutionStats(0, 0);
		public ExecutionStats{
			if(completedOperations<0 || executionNanos<0){ throw new IllegalArgumentException("Negative execution statistics"); }
		}
		public static ExecutionStats of(FuzzingRunner.SequenceProgress progress){
			return new ExecutionStats(progress.completedOperations(), progress.executionNanos());
		}
	}
	
	public static Status statusOf(SequenceOutcome<?, ?> outcome){
		return switch(outcome){
			case SequenceOutcome.Ok<?, ?> ignored -> new Ok();
			case SequenceOutcome.Cancelled<?, ?> ignored -> new Cancelled("Job cancelled");
			case SequenceOutcome.Failed<?, ?> failed -> {
				var failure = failed.failure();
				var trace   = new StringWriter();
				failure.e().printStackTrace(new PrintWriter(trace));
				
				yield new Failed(new RemoteFailure(
					failure.mark().action(), failure.e().getClass().getName(),
					String.valueOf(failure.e().getMessage()), trace.toString())
				);
			}
		};
	}
	
	public sealed interface Status extends Serializable permits Ok, Failed, Cancelled, InfrastructureError{ }
	
	public record Ok() implements Status{ }
	
	public record Failed(RemoteFailure failure) implements Status{
		public Failed{ Objects.requireNonNull(failure); }
	}
	
	public record Cancelled(String reason) implements Status{ }
	
	public record InfrastructureError(String message) implements Status{ }
	
	public record CapturedOutput(String stdout, String stderr) implements Serializable{
		public static final CapturedOutput EMPTY = new CapturedOutput("", "");
		public CapturedOutput{
			Objects.requireNonNull(stdout);
			Objects.requireNonNull(stderr);
		}
	}
	
	public record RemoteFailure(long actionIndex, String exceptionType, String message, String stackTrace) implements Serializable{
		public RemoteFailure{
			Objects.requireNonNull(exceptionType);
			Objects.requireNonNull(stackTrace);
		}
	}
}
