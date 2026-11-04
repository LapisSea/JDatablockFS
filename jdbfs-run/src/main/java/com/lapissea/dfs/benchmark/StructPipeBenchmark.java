package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.VaryingSize;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Full pipe IO over a deterministic, shuffled batch of unrelated application structs.
 * Every invocation uses the same heterogeneous call sites, including nested/reference IO,
 * rather than warming a separate monomorphic loop for each type. Scores are ns per record.
 *
 * <p>STANDARD uses variable-length pipes throughout. MIXED additionally includes fixed and
 * two varying-fixed layouts for numeric structs. Fixed skip is intentionally included as a
 * baseline; variable-length skip still has to decode the metadata it needs.
 *
 * <p>Each mode runs in its own JVM: pipe caches and JIT profiles must never cross modes.
 * Setup generates/initializes pipes, allocates referenced payloads, exercises lazy bootstraps,
 * and checks round trips and stream alignment. These costs are excluded from measurement.
 * Write and round-trip include output allocation/copying; read includes object allocation.
 * All data and referenced payloads reside in memory, so this measures serialization and
 * in-memory storage IO rather than filesystem latency. References are rewritten in place;
 * this is not a benchmark of continually allocating a growing database.
 *
 * <p>Run main with JMH options (for example {@code -prof gc -rf json -rff result.json}).
 * A wiring-only smoke check is {@code -wi 0 -i 1 -r 50ms -f 1 -p layout=MIXED}.
 * Do not use {@code -f 0}: the per-mode JVM options would not apply.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class StructPipeBenchmark{

	public static final int RECORD_COUNT = 192;
	private static final int SENTINEL = 0x12345678;
	private static final String INTERPRETER = "-Ddfs.optimizedPipe=NEVER";
	private static final String CODEGEN = "-Ddfs.optimizedPipe=SPECIAL_ONLY";

	public static void main(String[] args) throws Exception{
		var cli = new CommandLineOptions(args);
		var options = new OptionsBuilder().parent(cli).shouldFailOnError(true);
		if(cli.getIncludes().isEmpty()) options.include(StructPipeBenchmark.class.getSimpleName() + ".*");
		new Runner(options.build()).run();
	}

	@State(Scope.Thread)
	public static class Workload{
		@Param({"STANDARD", "MIXED"})
		public String layout;

		private DataProvider provider;
		private IOInstance<?>[] values;
		private StructPipe<?>[] pipes;
		private byte[] encoded;

		@Setup(Level.Trial)
		public void setup() throws IOException{
			if(!layout.equals("STANDARD") && !layout.equals("MIXED")) throw new IllegalArgumentException("Unknown layout " + layout);
			if(GlobalConfig.DEBUG_VALIDATION || ConfigDefs.TYPE_VALIDATION.resolve()){
				throw new IllegalStateException("Use forked benchmarks with assertions/type validation disabled");
			}
			var mode = ConfigDefs.OPTIMIZED_PIPE.resolve();
			if(mode != ConfigDefs.PipeOptimization.NEVER && mode != ConfigDefs.PipeOptimization.SPECIAL_ONLY){
				throw new IllegalStateException("Expected NEVER or SPECIAL_ONLY, got " + mode);
			}
			provider = Cluster.init(MemoryData.empty());
			var samples = new ArrayList<>(StructPipeBenchmarkData.create(RECORD_COUNT));
			Collections.shuffle(samples, new Random(0x5EED));
			values = samples.toArray(IOInstance<?>[]::new);
			pipes = new StructPipe<?>[values.length];
			int fixedIndex = 0;
			for(int i = 0; i<values.length; i++){
				int variant = layout.equals("MIXED") && StructPipeBenchmarkData.supportsFixed(values[i])? fixedIndex++%4 : 0;
				pipes[i] = makePipe(values[i], variant);
				var raw = unwrap(pipes[i]);
				if((variant == 2 || variant == 3) && !(raw instanceof FixedVaryingStructPipe<?>)){
					throw new IllegalStateException("Expected a varying-fixed pipe for variant " + variant);
				}
				if((raw instanceof StructPipe.SpecializedImplementation) != (mode == ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
					throw new IllegalStateException("Wrong pipe mode for " + values[i].getClass() + ": " + raw.getClass());
				}
			}
			encoded = encode();
			// Reference companions have now been initialized. Repeat writes must remain stable.
			if(!java.util.Arrays.equals(encoded, encode())) throw new IllegalStateException("Unstable fixture encoding");
			var reader = new ContentInputStream.BA(encoded);
			for(int i = 0; i<pipes.length; i++){
				var decoded = read(pipes[i], provider, reader);
				if(!Objects.equals(values[i], decoded)) throw new IllegalStateException("Round trip failed for " + values[i].getClass());
			}
			verifyEnd(reader);
			reader = new ContentInputStream.BA(encoded);
			for(var pipe : pipes) pipe.skip(provider, reader, null);
			verifyEnd(reader);
		}

		@SuppressWarnings({"rawtypes", "unchecked"})
		private static StructPipe<?> makePipe(IOInstance<?> value, int variant){
			var type = Struct.of((Class)value.getClass(), StagedInit.STATE_DONE);
			return switch(variant){
				case 0 -> StandardStructPipe.of(type, StagedInit.STATE_DONE);
				case 1 -> FixedStructPipe.of(type, StagedInit.STATE_DONE);
				case 2, 3 -> FixedVaryingStructPipe.tryVarying(type,
					VaryingSize.Provider.constLimit(variant == 2? NumberSize.INT : NumberSize.BIG_INT, 0));
				default -> throw new IllegalArgumentException("Unknown layout variant " + variant);
			};
		}

		private byte[] encode() throws IOException{
			var dest = new ContentOutputBuilder(encoded == null? 8192 : encoded.length);
			for(int i = 0; i<pipes.length; i++) write(pipes[i], provider, dest, values[i]);
			dest.writeInt4(SENTINEL);
			return dest.toByteArray();
		}

		private static void verifyEnd(ContentInputStream.BA reader) throws IOException{
			if(reader.readInt4() != SENTINEL || reader.available() != 0) throw new IllegalStateException("Misaligned batch IO");
		}
	}

	private static StructPipe<?> unwrap(StructPipe<?> pipe){
		if(pipe instanceof CheckedPipe.Standard<?> checked) return checked.getUncheckedPipe();
		if(pipe instanceof CheckedPipe.Fixed<?> checked) return checked.getUncheckedPipe();
		return pipe;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void write(StructPipe pipe, DataProvider provider, ContentOutputBuilder dest, IOInstance<?> value) throws IOException{
		pipe.write(provider, dest, value);
	}

	private static IOInstance<?> read(StructPipe<?> pipe, DataProvider provider, ContentInputStream.BA reader) throws IOException{
		return pipe.readNew(provider, reader, null);
	}

	private static void readBatch(Workload state, byte[] bytes, Blackhole blackhole) throws IOException{
		var reader = new ContentInputStream.BA(bytes);
		for(var pipe : state.pipes) blackhole.consume(read(pipe, state.provider, reader));
	}

	private static int skipBatch(Workload state) throws IOException{
		var reader = new ContentInputStream.BA(state.encoded);
		for(var pipe : state.pipes) pipe.skip(state.provider, reader, null);
		return reader.readInt4();
	}

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", INTERPRETER})
	public void readInterpreter(Workload state, Blackhole blackhole) throws IOException{ readBatch(state, state.encoded, blackhole); }

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", CODEGEN})
	public void readCodegen(Workload state, Blackhole blackhole) throws IOException{ readBatch(state, state.encoded, blackhole); }

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", INTERPRETER})
	public byte[] writeInterpreter(Workload state) throws IOException{ return state.encode(); }

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", CODEGEN})
	public byte[] writeCodegen(Workload state) throws IOException{ return state.encode(); }

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", INTERPRETER})
	public void roundTripInterpreter(Workload state, Blackhole blackhole) throws IOException{ readBatch(state, state.encode(), blackhole); }

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", CODEGEN})
	public void roundTripCodegen(Workload state, Blackhole blackhole) throws IOException{ readBatch(state, state.encode(), blackhole); }

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", INTERPRETER})
	public int skipInterpreter(Workload state) throws IOException{ return skipBatch(state); }

	@Benchmark
	@OperationsPerInvocation(RECORD_COUNT)
	@Fork(value = 2, jvmArgsAppend = {"-da", "-Ddfs.typeValidation=false", CODEGEN})
	public int skipCodegen(Workload state) throws IOException{ return skipBatch(state); }
}
