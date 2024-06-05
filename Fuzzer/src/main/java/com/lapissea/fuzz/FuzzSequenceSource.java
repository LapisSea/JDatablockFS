package com.lapissea.fuzz;

import java.math.BigInteger;
import java.util.stream.LongStream;
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
		
		private static long mixMurmur64(long z){
			z = (z^(z >>> 33))*0xff51afd7ed558ccdL;
			z = (z^(z >>> 33))*0xc4ceb9fe1a85ec53L;
			return z^(z >>> 33);
		}
		
		@Override
		public Stream<FuzzSequence> all(){
			long numberOfSequences = Math.ceilDiv(totalIterations, sequenceLength);
			
			long maxFastIDX       = Long.MAX_VALUE/sequenceLength - 2;
			var  sequenceLengthL  = BigInteger.valueOf(sequenceLength);
			var  totalIterationsL = BigInteger.valueOf(totalIterations);
			
			var seedBase = mixMurmur64(seed);
			
			return LongStream.range(0, numberOfSequences).mapToObj(idx -> {
				long from = idx*(long)sequenceLength;
				long to;
				if(idx>=maxFastIDX){
					var unbound = BigInteger.valueOf(idx).add(BigInteger.ONE).multiply(sequenceLengthL);
					to = unbound.min(totalIterationsL).longValueExact();
				}else{
					var unbound = (idx + 1L)*sequenceLength;
					to = Math.min(totalIterations, unbound);
				}
				
				return new FuzzSequence(from, idx, seedBase^mixMurmur64(idx), (int)(to - from));
			}).parallel();
		}
	}
}
