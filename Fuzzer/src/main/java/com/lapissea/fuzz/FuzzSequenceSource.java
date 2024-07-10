package com.lapissea.fuzz;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public interface FuzzSequenceSource{
	Stream<FuzzSequence> all();
	
	record LenSeed(long seed, long totalIterations, int sequenceLength) implements FuzzSequenceSource{
		public LenSeed(long seed, long totalIterations, int sequenceLength){
			if(totalIterations<=0){
				throw new IllegalArgumentException("Total iterations must be greater than zero");
			}
			if(sequenceLength<=0){
				throw new IllegalArgumentException("Sequence length must be greater than zero");
			}
			this.seed = modSeed(seed);
			this.totalIterations = modIterCount(totalIterations);
			this.sequenceLength = sequenceLength;
		}
		
		enum BaseSeedMod{
			NONE,
			DAILY,
			WEEKLY,
			ALWAYS
		}
		
		private static boolean modSeedWarned;
		private static long modSeed(long seed){
			var mode = System.getProperty("fuzz.seedMod");
			if(mode == null || mode.isBlank()) return seed;
			
			try{
				return seed^mixMurmur64(switch(BaseSeedMod.valueOf(mode.toUpperCase())){
					case NONE -> 0;
					case DAILY -> ChronoUnit.DAYS.between(LocalDate.of(1970, 1, 1), LocalDate.now());
					case WEEKLY -> ChronoUnit.WEEKS.between(LocalDate.of(1970, 1, 1), LocalDate.now());
					case ALWAYS -> System.nanoTime();
				});
			}catch(IllegalArgumentException ignored){ }
			
			try{
				var num = Long.parseLong(mode);
				return seed^mixMurmur64(num);
			}catch(NumberFormatException ignored){ }
			
			if(!modSeedWarned){
				modSeedWarned = true;
				System.out.println(
					"Warning: fuzz.seedMod system property is not a number or any of " +
					Arrays.stream(BaseSeedMod.values()).map(Objects::toString).collect(Collectors.joining(", ", "[", "]")) + ".\n" +
					"Assuming raw string value. To disable this message add STR_ to the start of the value"
				);
			}
			
			return seed^mixMurmur64(mode.hashCode());
		}
		
		private static long modIterCount(long iterCount){
			var mulS = System.getProperty("fuzz.iterationMultiplier");
			if(mulS == null || mulS.isBlank()) return iterCount;
			try{
				var mul = new BigDecimal(mulS);
				if(mul.compareTo(BigDecimal.ZERO)<=0){
					System.err.println("fuzz.iterationMultiplier system property must be greater than zero! Value: \"" + mulS + '"');
					return iterCount;
				}
				return mul.multiply(new BigDecimal(iterCount), MathContext.DECIMAL64).toBigInteger().longValueExact();
			}catch(NumberFormatException e){
				System.err.println("fuzz.iterationMultiplier system property is not a valid number! Value: \"" + mulS + '"');
				return iterCount;
			}catch(ArithmeticException e){
				return Long.MAX_VALUE;
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
				
				return new FuzzSequence(from, idx, seed^mixMurmur64(idx), (int)(to - from));
			}).parallel();
		}
	}
}
