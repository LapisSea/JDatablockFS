package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.FuzzProgress;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

public final class FuzzProgressTests{
	private record State(
		double progress, Optional<Duration> total, Optional<Duration> remaining,
		Duration elapsed, OptionalDouble nanosPerOp, boolean hasFail
	){ }
	
	// An isolated unnamed module permits access to the package's internal state builder.
	private static State state(long count, BigDecimal progress) throws Exception{
		var location = FuzzProgress.class.getProtectionDomain().getCodeSource().getLocation();
		
		try(var loader = new URLClassLoader(new URL[]{location}, null)){
			var type       = loader.loadClass(FuzzProgress.class.getName());
			var configType = loader.loadClass("com.lapissea.fuzz.FuzzConfig");
			
			var instance = type.getConstructor(configType, CompletableFuture.class)
			                   .newInstance(configType.getConstructor().newInstance(), CompletableFuture.completedFuture(Long.MAX_VALUE));
			
			var report = type.getDeclaredMethod("reportSteps", int.class, long.class);
			report.setAccessible(true);
			
			report.invoke(instance, Math.toIntExact(count), 1_000_000_000L);
			
			var method = type.getDeclaredMethod("makeLogState", Instant.class, Instant.class,
			                                    boolean.class, long.class, BigDecimal.class, BigInteger.class);
			method.setAccessible(true);
			Object result;
			try{
				result = method.invoke(instance, Instant.EPOCH.plusSeconds(2000), Instant.EPOCH,
				                       true, count, progress, BigInteger.valueOf(1_000_000_000));
			}catch(InvocationTargetException e){
				if(e.getCause() instanceof Exception cause) throw cause;
				throw e;
			}
			
			var resultType = result.getClass();
			return new State(
				(double)resultType.getMethod("progress").invoke(result),
				(Optional<Duration>)resultType.getMethod("estimatedTotalTime").invoke(result),
				(Optional<Duration>)resultType.getMethod("estimatedTimeRemaining").invoke(result),
				(Duration)resultType.getMethod("elapsed").invoke(result),
				(OptionalDouble)resultType.getMethod("nanosecondsPerOp").invoke(result),
				(boolean)resultType.getMethod("hasFail").invoke(result)
			);
		}
	}
	
	@Test
	void representableEstimatesAreReported() throws Exception{
		var state = state(500, new BigDecimal("0.05"));
		assertThat(state.total()).contains(Duration.ofSeconds(40_000));
		assertThat(state.remaining()).contains(Duration.ofSeconds(38_000));
	}
	
	@Test
	void oversizedEstimatesAreUnavailable() throws Exception{
		var progress = BigDecimal.valueOf(500).divide(BigDecimal.valueOf(Long.MAX_VALUE), MathContext.DECIMAL128);
		var state    = state(500, progress);
		assertThat(state.total()).isEmpty();
		assertThat(state.remaining()).isEmpty();
		assertThat(state.progress()).isEqualTo(progress.doubleValue());
		assertThat(state.elapsed()).isEqualTo(Duration.ofSeconds(2000));
		assertThat(state.nanosPerOp()).hasValue(2_000_000);
		assertThat(state.hasFail()).isTrue();
	}
	
	@Test
	void unknownProgressStillReportsOperationTiming() throws Exception{
		var state = state(500, BigDecimal.ZERO);
		assertThat(state.total()).isEmpty();
		assertThat(state.remaining()).isEmpty();
		assertThat(state.nanosPerOp()).hasValue(2_000_000);
	}
}
