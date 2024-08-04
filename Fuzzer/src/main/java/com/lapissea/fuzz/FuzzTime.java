package com.lapissea.fuzz;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

final class FuzzTime{
	
	private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1000_000_000L);
	static BigInteger durationToBigDecimal(Duration duration){
		var seconds = duration.getSeconds();
		var nanos   = duration.getNano();
		return BigInteger.valueOf(seconds).multiply(NANOS_PER_SECOND).add(BigInteger.valueOf(nanos));
	}
	static Duration bigToDuration(BigDecimal val){
		return val == null? null : bigToDuration(val.toBigInteger());
	}
	static Duration bigToDuration(BigInteger val){
		if(val == null) return null;
		var seconds = val.divide(NANOS_PER_SECOND);
		var nanos   = val.subtract(seconds.multiply(NANOS_PER_SECOND));
		return Duration.ofSeconds(seconds.longValueExact(), nanos.longValueExact());
	}
}
