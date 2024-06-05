package com.lapissea.fuzz;

import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface FuzzSequenceSource{
	Stream<FuzzSequence> all();
	
	record LenSeed(long seed, long totalIterations, int sequenceLength) implements FuzzSequenceSource{
		public LenSeed{
			if(totalIterations<=0){
				throw new IllegalArgumentException("Total iterations must be greater than zero");
			}
			if(sequenceLength<=0){
				throw new IllegalArgumentException("Sequence length must be greater than zero");
			}
		}
		@Override
		public Stream<FuzzSequence> all(){
			var sequenceEntropy   = new SimpleRandom(seed);
			var numberOfSequences = Math.toIntExact(Math.ceilDiv(totalIterations, sequenceLength));
			return IntStream.range(0, numberOfSequences).mapToObj(idx -> {
				var from = idx*sequenceLength;
				var to   = Math.min((idx + 1L)*sequenceLength, totalIterations);
				return new FuzzSequence(from, idx, sequenceEntropy.nextLong(), (int)(to - from));
			});
		}
	}
}
