package com.lapissea.dfs.config;

import com.lapissea.dfs.io.instancepipe.StructPipe.Special;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.logging.Log.LogLevel;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.compilation.FieldCompiler.AccessType;
import com.lapissea.dfs.type.compilation.JorthLogger.CodeLog;
import com.lapissea.util.LogUtil;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.lapissea.dfs.config.ConfigTools.*;
import static com.lapissea.dfs.config.FreedMemoryPurgeType.ONLY_HEADER_BYTES;
import static com.lapissea.dfs.config.FreedMemoryPurgeType.ZERO_OUT;
import static com.lapissea.dfs.logging.Log.LogLevel.INFO;
import static com.lapissea.dfs.logging.Log.LogLevel.SMALL_TRACE;
import static com.lapissea.dfs.logging.Log.LogLevel.TRACE;
import static com.lapissea.dfs.logging.Log.LogLevel.WARN;
import static com.lapissea.dfs.type.compilation.FieldCompiler.AccessType.UNSAFE;
import static com.lapissea.dfs.type.compilation.FieldCompiler.AccessType.VAR_HANDLE;
import static com.lapissea.dfs.type.compilation.JorthLogger.CodeLog.FALSE;
import static com.lapissea.dfs.type.compilation.JorthLogger.CodeLog.TRUE;

public sealed interface ConfigDefs permits ConfigTools.Dummy{
	
	enum CompLogLevel{
		NONE(0),
		JUST_START(1),
		SMALL(2),
		FULL(3);
		
		private final int val;
		CompLogLevel(int val){ this.val = val; }
		
		public boolean isWithin(CompLogLevel other){
			return val>=other.val;
		}
		public boolean isEnabled(){
			return PRINT_COMPILATION.resolve().isWithin(this);
		}
		
		public void log(String msg, Object arg1)                          { if(isEnabled()) Log.log(msg, arg1); }
		public void log(String msg, Object arg1, Object arg2)             { if(isEnabled()) Log.log(msg, arg1, arg2); }
		public void log(String msg, Object arg1, Object arg2, Object arg3){ if(isEnabled()) Log.log(msg, arg1, arg2, arg3); }
		public void log(String msg, Object... args)                       { if(isEnabled()) Log.log(msg, args); }
		public void log(String msg, Supplier<List<?>> lazyArgs)           { if(isEnabled()) Log.log(msg, lazyArgs); }
		public void log(String msg)                                       { if(isEnabled()) Log.log(msg); }
	}
	
	enum PipeOptimization{
		/**
		 * Every managed {@link IOInstance} will attempt to generate optimized implementations of pipes </br>
		 * <i>Note: When a type is unsupported, it will be ignored and fallback will be used.
		 * Set {@link ConfigDefs#LOG_LEVEL} to {@link LogLevel#INFO} or greater to see if or why a field will not be optimized.</i> </br>
		 * <b>Warning:</b> This flag is experimental and will cause additional synchronization when loading instances.
		 * If you are experiencing deadlocks, please do not use this.
		 */
		TRY_ALWAYS,
		/**
		 * Only {@link IOInstance}s with the {@link Special} annotation will generate optimized implementations of pipes.
		 * This annotation is to be given to types that are known to only have fields supported by the codegen.
		 * It is recommended to only add this annotation on types that will be read or written a lot as it may slow down startup time.
		 */
		SPECIAL_ONLY,
		/**
		 * Never generate optimized pipes. Should only be used if there is an issue
		 */
		NEVER
	}
	
	String CONFIG_PROPERTY_PREFIX = "dfs.";
	
	Flag.FBool STRICT_FLAGS       = flagB("strictFlags", false);
	Flag.FBool RELEASE_MODE       = flagB("releaseMode", () -> !deb() && isInJar());
	Flag.FBool TYPE_VALIDATION    = flagB("typeValidation", deb());
	Flag.FBool DO_INTEGRITY_CHECK = flagB("typeValidation.doIntegrityCheck", TYPE_VALIDATION);
	Flag.FInt  BATCH_BYTES        = flagI("batchBytes", 8192).natural();
	
	Flag.FEnum<LogLevel>     LOG_LEVEL         = flagE("log.level", RELEASE_MODE.boolMap(WARN, INFO));
	Flag.FBool               PRINT_FLAGS       = flagB("log.printFlags", () -> deb() && LOG_LEVEL.resolve().isWithin(INFO));
	Flag.FEnum<CompLogLevel> PRINT_COMPILATION = flagE("log.printCompilation", LOG_LEVEL.<CompLogLevel>map(l -> {
		if(l.isWithin(SMALL_TRACE)) return CompLogLevel.FULL;
		if(l.isWithin(TRACE)) return CompLogLevel.SMALL;
		return CompLogLevel.NONE;
	}));
	
	Flag.FDur              LONG_WAIT_THRESHOLD = flagDur("loading.longWaitThreshold", RELEASE_MODE.boolMap(null, Duration.ofMillis(10000/cores()))).positive();
	Flag.FEnum<AccessType> FIELD_ACCESS_TYPE   = flagE("tweaks.fieldAccess", () -> jVersion()>23? VAR_HANDLE : UNSAFE);
	Flag.FBool             COSTLY_STACK_TRACE  = flagB("tweaks.costlyStackTrace", deb());
	Flag.FDur              DELAY_COMP_OBJ_GC   = flagDur("tweaks.delayCompilationObjGC", RELEASE_MODE.boolMap(Duration.ZERO, Duration.ofSeconds(5))).positive();
	
	Flag.FEnum<PipeOptimization> OPTIMIZED_PIPE = flagEV("optimizedPipe", PipeOptimization.SPECIAL_ONLY);
	
	Flag.FEnum<FreedMemoryPurgeType> PURGE_ACCIDENTAL_CHUNK_HEADERS = flagE("purgeAccidentalChunkHeaders", () -> deb()? ONLY_HEADER_BYTES : ZERO_OUT);
	
	Flag.FBool DISABLE_TRANSACTIONS = flagB("io.disableTransactions", false);
	Flag.FBool SYNCHRONOUS_FILE_IO  = flagB("io.synchronousFileIO", false);
	
	Flag.FStr  RUNNER_BASE_TASK_NAME       = flagS("runner.baseTaskName", "Task");
	Flag.FBool RUNNER_ONLY_VIRTUAL_WORKERS = flagB("runner.onlyVirtual", false);
	Flag.FBool RUNNER_MUTE_CHOKE_WARNING   = flagB("runner.muteWarning", false);
	Flag.FDur  RUNNER_TASK_CHOKE_TIME      = flagDur("runner.taskChokeTime", () -> Duration.ofMillis(2000/cores())).positive().limitMaxNs(Long.MAX_VALUE);
	Flag.FDur  RUNNER_WATCHER_TIMEOUT      = flagDur("runner.watcherTimeout", Duration.ofMillis(1000)).positive().limitMaxMs(Integer.MAX_VALUE);
	
	Flag.FBool          CLASSGEN_DEBUG                 = flagB("classGen.debug", false);
	Flag.FBool          CLASSGEN_EXIT_ON_FAIL          = flagB("classGen.exitOnFail", false);
	Flag.FBool          CLASSGEN_PRINT_GENERATING_INFO = flagB("classGen.printGeneratingInfo", CLASSGEN_DEBUG);
	Flag.FEnum<CodeLog> CLASSGEN_PRINT_BYTECODE        = flagE("classGen.printBytecode", CLASSGEN_DEBUG.boolMap(TRUE, FALSE));
	Flag.FStrOptional   CLASSGEN_DUMP_LOCATION         = flagS("classGen.dumpLocation");
	
	
	Flag.FEnum<LZ4Compatibility> LZ4_COMPATIBILITY = flagEV("lz4.compatibility", LZ4Compatibility.ANY);
	
	Flag.FInt ROOT_PROVIDER_WARMUP_COUNT = flagI("rootProviderWarmupCount", 20).positive();
	
	private static int cores(){
		return Math.min(10, Runtime.getRuntime().availableProcessors());
	}
	private static int jVersion(){
		return Runtime.version().feature();
	}
	static boolean deb(){
		return ConfigDefs.class.desiredAssertionStatus();
	}
	private static boolean isInJar(){
		URL url = ConfigDefs.class.getResource(ConfigDefs.class.getSimpleName() + ".class");
		Objects.requireNonNull(url);
		var proto = url.getProtocol();
		return switch(proto){
			case "jar", "war" -> true;
			case "file" -> false;
			default -> {
				LogUtil.printlnEr("Warning:", proto, " is an unknown source protocol");
				yield false;
			}
		};
	}
}
