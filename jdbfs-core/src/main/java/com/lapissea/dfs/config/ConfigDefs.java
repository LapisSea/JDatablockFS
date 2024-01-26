package com.lapissea.dfs.config;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.compilation.FieldCompiler.AccessType;
import com.lapissea.dfs.type.compilation.JorthLogger.CodeLog;
import com.lapissea.util.LogUtil;

import java.net.URL;
import java.util.Objects;

import static com.lapissea.dfs.config.ConfigTools.*;
import static com.lapissea.dfs.config.FreedMemoryPurgeType.ONLY_HEADER_BYTES;
import static com.lapissea.dfs.config.FreedMemoryPurgeType.ZERO_OUT;
import static com.lapissea.dfs.logging.Log.LogLevel.INFO;
import static com.lapissea.dfs.logging.Log.LogLevel.SMALL_TRACE;
import static com.lapissea.dfs.logging.Log.LogLevel.WARN;
import static com.lapissea.dfs.type.compilation.FieldCompiler.AccessType.UNSAFE;
import static com.lapissea.dfs.type.compilation.FieldCompiler.AccessType.VAR_HANDLE;
import static com.lapissea.dfs.type.compilation.JorthLogger.CodeLog.FALSE;
import static com.lapissea.dfs.type.compilation.JorthLogger.CodeLog.TRUE;

public sealed interface ConfigDefs permits ConfigTools.Dummy{
	
	String CONFIG_PROPERTY_PREFIX = "dfs.";
	
	Flag.FBool STRICT_FLAGS    = flagB("strictFlags", false);
	Flag.FBool RELEASE_MODE    = flagB("releaseMode", () -> !deb() && isInJar());
	Flag.FBool TYPE_VALIDATION = flagB("typeValidation", deb());
	Flag.FInt  BATCH_BYTES     = flagI("batchBytes", 8192).natural();
	
	Flag.FEnum<Log.LogLevel> LOG_LEVEL         = flagE("log.level", RELEASE_MODE.boolMap(WARN, INFO));
	Flag.FBool               PRINT_FLAGS       = flagB("log.printFlags", () -> deb() && LOG_LEVEL.resolve().isWithin(INFO));
	Flag.FBool               PRINT_COMPILATION = flagB("log.printCompilation", LOG_LEVEL.map(l -> l.isWithin(SMALL_TRACE)));
	
	Flag.FBool             LOAD_TYPES_ASYNCHRONOUSLY = flagB("loading.async", true);
	Flag.FInt              LONG_WAIT_THRESHOLD       = flagI("loading.longWaitThreshold", RELEASE_MODE.boolMap(-1, 10000/cores())).positiveOptional();
	Flag.FBool             TEXT_DISABLE_BLOCK_CODING = flagB("tweaks.disableTextBlockCoding", false);
	Flag.FEnum<AccessType> FIELD_ACCESS_TYPE         = flagE("tweaks.fieldAccess", () -> jVersion()<=21? UNSAFE : VAR_HANDLE);
	Flag.FBool             COSTLY_STACK_TRACE        = flagB("tweaks.costlyStackTrace", deb());
	
	Flag.FBool OPTIMIZED_PIPE               = flagB("optimizedPipe", true);
	Flag.FBool OPTIMIZED_PIPE_USE_CHUNK     = flagB("optimizedPipe.chunk", OPTIMIZED_PIPE);
	Flag.FBool OPTIMIZED_PIPE_USE_REFERENCE = flagB("optimizedPipe.reference", OPTIMIZED_PIPE);
	
	Flag.FEnum<FreedMemoryPurgeType> PURGE_ACCIDENTAL_CHUNK_HEADERS = flagE(
		"purgeAccidentalChunkHeaders", () -> deb()? ONLY_HEADER_BYTES : ZERO_OUT);
	
	Flag.FBool USE_UNSAFE_LOOKUP = flagB("useUnsafeForAccess", true);
	
	Flag.FBool DISABLE_TRANSACTIONS = flagB("io.disableTransactions", false);
	Flag.FBool SYNCHRONOUS_FILE_IO  = flagB("io.synchronousFileIO", false);
	
	Flag.FStr  RUNNER_BASE_TASK_NAME       = flagS("runner.baseTaskName", "Task");
	Flag.FBool RUNNER_ONLY_VIRTUAL_WORKERS = flagB("runner.onlyVirtual", false);
	Flag.FBool RUNNER_MUTE_CHOKE_WARNING   = flagB("runner.muteWarning", false);
	Flag.FInt  RUNNER_TASK_CHOKE_TIME_MS   = flagI("runner.taskChokeTime", () -> 2000/cores()).natural();
	Flag.FInt  RUNNER_WATCHER_TIMEOUT_MS   = flagI("runner.watcherTimeout", 1000).natural();
	
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
