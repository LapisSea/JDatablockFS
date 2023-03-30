package com.lapissea.cfs.config;

import com.lapissea.cfs.io.compress.Lz4Packer;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.compilation.FieldCompiler.AccessImplType;
import com.lapissea.cfs.type.compilation.JorthLogger.CodeLog;
import com.lapissea.util.LogUtil;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.lapissea.cfs.config.ConfigTools.*;
import static com.lapissea.cfs.logging.Log.LogLevel.INFO;
import static com.lapissea.cfs.logging.Log.LogLevel.WARN;

public enum ConfigDefs{
	;
	static final String CONFIG_PROPERTY_PREFIX = "dfs.";
	
	public static final Flag.Bool RELEASE_MODE      = flagB("releaseMode", () -> !deb() && isInJar());
	public static final Flag.Bool TYPE_VALIDATION   = flagB("typeValidation", deb());
	public static final Flag.Bool PRINT_COMPILATION = flagB("printCompilation", false);
	public static final Flag.Int  BATCH_BYTES       = flagI("batchBytes", 1<<12);
	
	public static final Flag.EnumF<Log.LogLevel> LOG_LEVEL = flagE("log.level", RELEASE_MODE.<Log.LogLevel>map(v -> v? WARN : INFO));
	
	public static final Flag.Bool                  LOAD_TYPES_ASYNCHRONOUSLY = flagB("loading.async", true);
	public static final Flag.Int                   LONG_WAIT_THRESHOLD       = flagI(
		"loading.longWaitThreshold", RELEASE_MODE.map(v -> v? -1 : 10000/Math.min(10, Runtime.getRuntime().availableProcessors()))
	);
	public static final Flag.Bool                  TEXT_DISABLE_BLOCK_CODING = flagB("tweaks.disableTextBlockCoding", true);
	public static final Flag.EnumF<AccessImplType> FIELD_ACCESS_TYPE         = flagE(
		"tweaks.fieldAccess", (Supplier<AccessImplType>)() -> Runtime.version().feature()<=20? AccessImplType.UNSAFE : AccessImplType.VAR_HANDLE
	);
	
	public static final Flag.Bool OPTIMIZED_PIPE               = flagB("optimizedPipe", true);
	public static final Flag.Bool OPTIMIZED_PIPE_USE_CHUNK     = flagB("optimizedPipe.chunk", OPTIMIZED_PIPE);
	public static final Flag.Bool OPTIMIZED_PIPE_USE_REFERENCE = flagB("optimizedPipe.reference", OPTIMIZED_PIPE);
	
	public static final Flag.Bool PURGE_ACCIDENTAL_CHUNK_HEADERS = flagB("purgeAccidentalChunkHeaders", deb());
	
	public static final Flag.Bool USE_UNSAFE_LOOKUP = flagB("useUnsafeForAccess", true);
	
	public static final Flag.Bool DISABLE_TRANSACTIONS = flagB("io.disableTransactions", false);
	public static final Flag.Bool SYNCHRONOUS_FILE_IO  = flagB("io.synchronousFileIO", false);
	
	public static final Flag.Str  RUNNER_BASE_TASK_NAME       = flagS("runner.baseTaskName", "Task");
	public static final Flag.Bool RUNNER_ONLY_VIRTUAL_WORKERS = flagB("runner.onlyVirtual", false);
	public static final Flag.Bool RUNNER_MUTE_CHOKE_WARNING   = flagB("runner.muteWarning", false);
	public static final Flag.Int  RUNNER_TASK_CHOKE_TIME_MS   = flagI("runner.taskChokeTime", () -> 2000/Runtime.getRuntime().availableProcessors());
	public static final Flag.Int  RUNNER_WATCHER_TIMEOUT_MS   = flagI("runner.watcherTimeout", 1000);
	
	public static final Flag.Bool           CLASSGEN_EXIT_ON_FAIL          = flagB("classGen.exitOnFail", false);
	public static final Flag.Bool           CLASSGEN_PRINT_GENERATING_INFO = flagB("classGen.printGeneratingInfo", false);
	public static final Flag.EnumF<CodeLog> CLASSGEN_PRINT_BYTECODE        = flagE("classGen.printBytecode", CodeLog.FALSE);
	public static final Optional<String>    CLASSGEN_DUMP_LOCATION         = ConfigUtils.optionalProperty("classGen.printBytecode");
	
	
	public static final Flag.EnumF<Lz4Packer.Provider> LZ4_COMPATIBILITY = flagE("lz4.compatibility", Lz4Packer.Provider.ANY);
	
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
