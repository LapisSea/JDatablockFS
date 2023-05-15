package com.lapissea.cfs.config;

import com.lapissea.cfs.io.compress.Lz4Packer;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.compilation.FieldCompiler.AccessType;
import com.lapissea.cfs.type.compilation.JorthLogger.CodeLog;
import com.lapissea.util.LogUtil;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;

import static com.lapissea.cfs.config.ConfigTools.*;
import static com.lapissea.cfs.logging.Log.LogLevel.INFO;
import static com.lapissea.cfs.logging.Log.LogLevel.SMALL_TRACE;
import static com.lapissea.cfs.logging.Log.LogLevel.WARN;
import static com.lapissea.cfs.type.compilation.FieldCompiler.AccessType.UNSAFE;
import static com.lapissea.cfs.type.compilation.FieldCompiler.AccessType.VAR_HANDLE;
import static com.lapissea.cfs.type.compilation.JorthLogger.CodeLog.FALSE;
import static com.lapissea.cfs.type.compilation.JorthLogger.CodeLog.TRUE;

public sealed interface ConfigDefs permits ConfigTools.Dummy{
	
	String CONFIG_PROPERTY_PREFIX = "dfs.";
	
	Flag.Bool RELEASE_MODE    = flagB("releaseMode", () -> !deb() && isInJar());
	Flag.Bool TYPE_VALIDATION = flagB("typeValidation", deb());
	Flag.Int  BATCH_BYTES     = flagI("batchBytes", 1<<12).natural();
	
	Flag.Abc<Log.LogLevel> LOG_LEVEL         = flagEDyn("log.level", RELEASE_MODE.boolMap(WARN, INFO));
	Flag.Bool              PRINT_COMPILATION = flagB("printCompilation", LOG_LEVEL.map(l -> l.isWithin(SMALL_TRACE)));
	
	Flag.Bool            LOAD_TYPES_ASYNCHRONOUSLY = flagB("loading.async", true);
	Flag.Int             LONG_WAIT_THRESHOLD       = flagI("loading.longWaitThreshold", RELEASE_MODE.boolMap(-1, 10000/cores())).positiveOptional();
	Flag.Bool            TEXT_DISABLE_BLOCK_CODING = flagB("tweaks.disableTextBlockCoding", true);
	Flag.Abc<AccessType> FIELD_ACCESS_TYPE         = flagEDyn("tweaks.fieldAccess", () -> jVersion()<=20? UNSAFE : VAR_HANDLE);
	
	Flag.Bool OPTIMIZED_PIPE               = flagB("optimizedPipe", true);
	Flag.Bool OPTIMIZED_PIPE_USE_CHUNK     = flagB("optimizedPipe.chunk", OPTIMIZED_PIPE);
	Flag.Bool OPTIMIZED_PIPE_USE_REFERENCE = flagB("optimizedPipe.reference", OPTIMIZED_PIPE);
	
	Flag.Bool PURGE_ACCIDENTAL_CHUNK_HEADERS = flagB("purgeAccidentalChunkHeaders", deb());
	
	Flag.Bool USE_UNSAFE_LOOKUP = flagB("useUnsafeForAccess", true);
	
	Flag.Bool DISABLE_TRANSACTIONS = flagB("io.disableTransactions", false);
	Flag.Bool SYNCHRONOUS_FILE_IO  = flagB("io.synchronousFileIO", false);
	
	Flag.Str  RUNNER_BASE_TASK_NAME       = flagS("runner.baseTaskName", "Task");
	Flag.Bool RUNNER_ONLY_VIRTUAL_WORKERS = flagB("runner.onlyVirtual", false);
	Flag.Bool RUNNER_MUTE_CHOKE_WARNING   = flagB("runner.muteWarning", false);
	Flag.Int  RUNNER_TASK_CHOKE_TIME_MS   = flagI("runner.taskChokeTime", () -> 2000/cores()).natural();
	Flag.Int  RUNNER_WATCHER_TIMEOUT_MS   = flagI("runner.watcherTimeout", 1000).natural();
	
	Flag.Bool         CLASSGEN_DEBUG                 = flagB("classGen.debug", false);
	Flag.Bool         CLASSGEN_EXIT_ON_FAIL          = flagB("classGen.exitOnFail", false);
	Flag.Bool         CLASSGEN_PRINT_GENERATING_INFO = flagB("classGen.printGeneratingInfo", CLASSGEN_DEBUG);
	Flag.Abc<CodeLog> CLASSGEN_PRINT_BYTECODE        = flagE("classGen.printBytecode", CLASSGEN_DEBUG.boolMap(TRUE, FALSE));
	Optional<String>  CLASSGEN_DUMP_LOCATION         = ConfigUtils.optionalProperty("classGen.printBytecode");
	
	
	Flag.Abc<Lz4Packer.Provider> LZ4_COMPATIBILITY = flagE("lz4.compatibility", Lz4Packer.Provider.ANY);
	
	private static int cores(){
		return Math.min(10, Runtime.getRuntime().availableProcessors());
	}
	private static int jVersion(){
		return Runtime.version().feature();
	}
	private static boolean deb(){
		return ConfigDefs.class.desiredAssertionStatus();
	}
	private static boolean isInJar(){
		URL url = GlobalConfig.class.getResource(GlobalConfig.class.getSimpleName() + ".class");
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
