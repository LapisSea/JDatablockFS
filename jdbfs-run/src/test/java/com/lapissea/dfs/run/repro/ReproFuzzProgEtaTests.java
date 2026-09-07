package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.FuzzProgress;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/*
 * BUG-04 (fuzz-prog-eta): FuzzProgress ETA computation overflows long when totalIterations == Long.MAX_VALUE.
 *
 * What the code is supposed to do:
 *   During a fuzz run FuzzProgress periodically logs progress plus an estimated total/remaining time (ETA).
 *   In the default multi-worker setup (FuzzConfig.java:39, maxWorkers = processors+1 > 1) FuzzingRunner runs
 *   in Async mode and constructs FuzzProgress with a CompletableFuture that resolves to the total iteration
 *   count (FuzzingRunner.java:359). That total can be Long.MAX_VALUE: FuzzSequenceSource.LenSeed.modIterCount
 *   falls back to Long.MAX_VALUE when the multiplied count does not fit a long
 *   (FuzzSequenceSource.java:77-83), and FuzzProgress.totalIterations() itself reports Long.MAX_VALUE while
 *   the future is unresolved (FuzzProgress.java:142-153).
 *   The ETA is derived as progress = count / totalIterations (FuzzProgress.java:170) and
 *   totalTimeEstimated = elapsedTimeNs / progress (FuzzProgress.java:189), i.e. elapsed * totalIterations / count.
 *
 * What it actually does:
 *   For count > 100 (estimate flag, FuzzProgress.java:192) the estimated durations are converted via
 *   FuzzTime.bigToDuration (FuzzProgress.java:195-196), whose
 *   Duration.ofSeconds(seconds.longValueExact(), ...) (FuzzTime.java:22) throws ArithmeticException
 *   ("BigInteger out of long range") once the estimated total exceeds Long.MAX_VALUE seconds. With
 *   totalIterations = Long.MAX_VALUE that happens whenever elapsed (in seconds) > count, e.g. elapsed ~ 2000 s
 *   with count = 500: ETA = 2000 * Long.MAX_VALUE / 500 ~ 3.69e19 s > Long.MAX_VALUE (~9.22e18 s).
 *   So the progress-report path crashes instead of reporting an absurd-but-finite ETA.
 *
 * Why the test fails:
 *   The test builds FuzzProgress in exactly that state (a completed future holding Long.MAX_VALUE - the
 *   resolved Async-mode total - plus 500 executed iterations and ~2000 s of elapsed time) and drives the
 *   production report path reportSteps -> logInc -> makeLogState. The "must not throw" assertion below fails
 *   with the ArithmeticException thrown from FuzzTime.bigToDuration (FuzzTime.java:22). A control run with a
 *   sane total (10_000) goes through the same code path and logs a finite ~40_000 s ETA, proving the harness
 *   reaches the ETA path and that only the Long.MAX_VALUE total crashes.
 *
 * Note on numbers: the catalog hint "count=500, elapsed~200s" would give ETA ~ 3.69e18 s, which still fits a
 * long (no exception) - the true overflow threshold is elapsed_seconds > count - so this test keeps count=500
 * and scales elapsed to 2000 s to cross the threshold.
 *
	 * Harness note: surefire runs the tests on the module path (this class is in module JDatablockFS.run) and the
	 * Fuzzer module does not "opens com.lapissea.fuzz", so the package-private reportSteps/reportStart and the
	 * private start/lastLog fields cannot be reflected directly (InaccessibleObjectException). The harness loads
	 * the Fuzzer class files into a URLClassLoader with a NULL parent, so class-loading delegation stops at the
	 * bootstrap loader (java.*) and every com.lapissea.fuzz.* class is defined by this loader in an unnamed
	 * module - never resolved to the Fuzzer module's copies - where deep reflection is always permitted.
	 * The classes involved (FuzzProgress, FuzzConfig, FuzzLogger, LogMessage, FuzzTime, NanoClock) depend only on
	 * java.* and com.lapissea.fuzz.*, so they load self-contained. A java.lang.reflect.Proxy implements the
	 * shadow FuzzLogger and records the messages. The reflection layer unwraps InvocationTargetException so a
	 * test failure shows the original production exception, not the reflection wrapper.
 */
public final class ReproFuzzProgEtaTests{
	
	private static final int  COUNT   = 500;   // > 100 so the estimate flag is set (FuzzProgress.java:192)
	private static final long ONE_S_NS = 1_000_000_000L;
	private static final int  ELAPSED_S = 2000; // > COUNT so ETA = ELAPSED_S * Long.MAX_VALUE / COUNT > Long.MAX_VALUE
	private static final int  LOG_AGE_S = 5;    // > default logTimeout (900 ms) so logInc's timeout gate passes
	
	@Test(timeOut = 30_000)
	public void etaReportPathWithMaxValueTotal(){
		var shadow = ShadowFuzz.load();
		
		// --- control: a sane total must survive the same code path ---
		var control = shadow.progress(10_000L);
		shadow.reportSteps(control, COUNT, ONE_S_NS); // no exception for a sane total
		
		var controlStates = shadow.states(control);
		// proves the harness actually reached the ETA log path (one State message after reportStart's Start)
		assertThat(controlStates).hasSize(1);
		// ETA = 2000 s * 10_000 / 500 = 40_000 s: finite, fits a long -> bigToDuration succeeds
		var controlEta = shadow.estimatedTotal(controlStates.get(0));
		assertThat(controlEta.getSeconds()).isBetween(40_000L, 40_500L);
		
		// --- repro: totalIterations = Long.MAX_VALUE (the resolved Async-mode total) ---
		var repro = shadow.progress(Long.MAX_VALUE);
		
		// key assertion (FAILS while the bug is present):
		// reportSteps -> logInc -> makeLogState computes progress = count / Long.MAX_VALUE (FuzzProgress.java:170)
		// and totalTimeEstimated = elapsedNs / progress = elapsed * Long.MAX_VALUE / count (FuzzProgress.java:189),
		// then converts it with Duration.ofSeconds(seconds.longValueExact()) (FuzzProgress.java:195, FuzzTime.java:22).
		// ETA ~ 3.69e19 s > Long.MAX_VALUE -> ArithmeticException "BigInteger out of long range".
		assertThatCode(() -> shadow.reportSteps(repro, COUNT, ONE_S_NS))
			.describedAs("ETA report with totalIterations=Long.MAX_VALUE must not overflow long (bug: ArithmeticException from FuzzTime.bigToDuration)")
			.doesNotThrowAnyException();
	}
	
	// ---- harness: shadow-loaded Fuzzer classes (unnamed module => deep reflection allowed) ----
	
	private static final class ShadowFuzz{
		private final ClassLoader loader;
		private final Class<?> progressCl;
		private final Class<?> configCl;
		private final Class<?> loggerCl;
		private final Class<?> stateCl;
		
		private ShadowFuzz(ClassLoader loader, Class<?> progressCl, Class<?> configCl, Class<?> loggerCl, Class<?> stateCl){
			this.loader     = loader;
			this.progressCl = progressCl;
			this.configCl   = configCl;
			this.loggerCl   = loggerCl;
			this.stateCl    = stateCl;
		}
		
		static ShadowFuzz load(){
			var src = FuzzProgress.class.getProtectionDomain().getCodeSource();
			if(src == null || src.getLocation() == null) throw new IllegalStateException("harness: no code source for FuzzProgress");
			// null parent: delegation stops at the bootstrap loader, so com.lapissea.fuzz.* are defined by
			// this loader (unnamed module) instead of resolving to the Fuzzer module's classes
			var loader = new URLClassLoader(new URL[]{src.getLocation()}, null);
			try{
				return new ShadowFuzz(
					loader,
					loader.loadClass("com.lapissea.fuzz.FuzzProgress"),
					loader.loadClass("com.lapissea.fuzz.FuzzConfig"),
					loader.loadClass("com.lapissea.fuzz.FuzzLogger"),
					loader.loadClass("com.lapissea.fuzz.LogMessage$State")
				);
			}catch(ReflectiveOperationException e){
				throw new IllegalStateException("harness: cannot load shadow Fuzzer classes", e);
			}
		}
		
		/** A FuzzProgress whose (proxy) logger records every LogMessage it is given. */
		record Run(Object progress, List<Object> messages){ }
		
		Run progress(long totalIterations){
			try{
				var messages = new ArrayList<Object>();
				var logger = Proxy.newProxyInstance(loader, new Class[]{loggerCl}, (p, m, a) -> {
					switch(m.getName()){
						case "log"      -> { if(a != null && a.length == 1) messages.add(a[0]); return null; }
						case "toString" -> { return "shadow-fuzz-logger"; }
						case "hashCode" -> { return System.identityHashCode(p); }
						case "equals"   -> { return p == a[0]; }
						default         -> { return null; }
					}
				});
				// FuzzConfig is an immutable record: logWith returns a NEW config instance
				var config = (Object)configCl.getConstructor().newInstance();
				config = configCl.getMethod("logWith", loggerCl).invoke(config, logger);
				// resolved state of the Async-mode future FuzzingRunner hands to FuzzProgress (FuzzingRunner.java:359)
				var future = CompletableFuture.completedFuture(totalIterations);
				var progress = progressCl.getConstructor(configCl, CompletableFuture.class).newInstance(config, future);
				invoke(progress, "reportStart"); // same entry point FuzzingRunner uses (FuzzingRunner.java:347, :423)
				backdate(progress);
				return new Run(progress, messages);
			}catch(InvocationTargetException e){
				unwrap(e);
				throw new AssertionError("unreachable");
			}catch(ReflectiveOperationException e){
				throw new IllegalStateException("harness: cannot build shadow FuzzProgress", e);
			}
		}
		
		// backdates start so Duration.between(start, now) ~ ELAPSED_S, and lastLog so logInc's
		// "Duration.between(lastLog, now) <= logTimeout" gate (FuzzProgress.java:158) passes
		private void backdate(Object progress){
			var now = Instant.now();
			setField(progress, "start", now.minusSeconds(ELAPSED_S));
			setField(progress, "lastLog", now.minusSeconds(LOG_AGE_S));
		}
		
		void reportSteps(Run run, int num, long nsDuration){
			invoke(run.progress(), "reportSteps", num, nsDuration);
		}
		
		List<Object> states(Run run){
			return run.messages().stream().filter(stateCl::isInstance).toList();
		}
		
		Duration estimatedTotal(Object state){
			try{
				var opt = (Optional<Duration>)stateCl.getMethod("estimatedTotalTime").invoke(state);
				return opt.orElseThrow(() -> new IllegalStateException("control: no estimated total logged"));
			}catch(ReflectiveOperationException e){
				throw new IllegalStateException("harness: cannot read State.estimatedTotalTime", e);
			}
		}
		
		private void invoke(Object target, String name, Object... args){
			try{
				var m = progressCl.getDeclaredMethod(name, types(args));
				m.setAccessible(true);
				m.invoke(target, args);
			}catch(InvocationTargetException e){
				unwrap(e);
				throw new AssertionError("unreachable");
			}catch(ReflectiveOperationException e){
				throw new IllegalStateException("harness: cannot reflect into FuzzProgress." + name, e);
			}
		}
		
		private void setField(Object target, String name, Object value){
			try{
				var f = progressCl.getDeclaredField(name);
				f.setAccessible(true);
				f.set(target, value);
			}catch(ReflectiveOperationException e){
				throw new IllegalStateException("harness: cannot set FuzzProgress." + name, e);
			}
		}
		
		private static Class<?> typeOf(Object a){
			var c = a.getClass();
			if(c == Integer.class)   return int.class;
			if(c == Long.class)      return long.class;
			if(c == Boolean.class)   return boolean.class;
			if(c == Double.class)    return double.class;
			if(c == Float.class)     return float.class;
			if(c == Short.class)     return short.class;
			if(c == Byte.class)      return byte.class;
			if(c == Character.class) return char.class;
			return c;
		}
		
		private static Class<?>[] types(Object[] args){
			var types = new Class<?>[args.length];
			for(int i = 0; i<args.length; i++) types[i] = typeOf(args[i]);
			return types;
		}
		
		private static void unwrap(InvocationTargetException e){
			var c = e.getCause();
			if(c instanceof RuntimeException re) throw re;
			if(c instanceof Error err) throw err;
			throw new RuntimeException(c);
		}
	}
}
