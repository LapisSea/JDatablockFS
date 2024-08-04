package com.lapissea.dfs.logging;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.config.ConfigTools;
import com.lapissea.dfs.config.ConfigUtils;
import com.lapissea.dfs.exceptions.IllegalConfiguration;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.lapissea.dfs.Utils.getFrame;

public class Log{
	
	public enum LogLevel{
		NONE(Integer.MIN_VALUE),
		MIN(0),
		WARN(1),
		INFO(2),
		DEBUG(3),
		TRACE(4),
		SMALL_TRACE(5),
		ALL(Integer.MAX_VALUE);
		private final int val;
		LogLevel(int val){ this.val = val; }
		
		public boolean isWithin(LogLevel other){
			return val>=other.val;
		}
	}
	
	private record Tag(String name, String cmd){ }
	
	private static final List<Tag> COLORS = List.of(
		new Tag("BLACK", ConsoleColors.BLACK),
		new Tag("BLACKBRIGHT", ConsoleColors.BLACK_BRIGHT),
		new Tag("RED", ConsoleColors.RED),
		new Tag("REDBRIGHT", ConsoleColors.RED_BRIGHT),
		new Tag("GREEN", ConsoleColors.GREEN),
		new Tag("GREENBRIGHT", ConsoleColors.GREEN_BRIGHT),
		new Tag("YELLOW", ConsoleColors.YELLOW),
		new Tag("YELLOWBRIGHT", ConsoleColors.YELLOW_BRIGHT),
		new Tag("BLUE", ConsoleColors.BLUE),
		new Tag("BLUEBRIGHT", ConsoleColors.BLUE_BRIGHT),
		new Tag("PURPLE", ConsoleColors.PURPLE),
		new Tag("PURPLEBRIGHT", ConsoleColors.PURPLE_BRIGHT),
		new Tag("CYAN", ConsoleColors.CYAN),
		new Tag("CYANBRIGHT", ConsoleColors.CYAN_BRIGHT),
		new Tag("WHITE", ConsoleColors.WHITE),
		new Tag("WHITEBRIGHT", ConsoleColors.WHITE_BRIGHT)
	);
	
	public static final boolean MIN, WARN, INFO, DEBUG, TRACE, SMALL_TRACE;
	
	static{
		var level = ConfigDefs.LOG_LEVEL.resolveLocking();
		MIN = level.isWithin(LogLevel.MIN);
		WARN = level.isWithin(LogLevel.WARN);
		INFO = level.isWithin(LogLevel.INFO);
		DEBUG = level.isWithin(LogLevel.DEBUG);
		TRACE = level.isWithin(LogLevel.TRACE);
		SMALL_TRACE = level.isWithin(LogLevel.SMALL_TRACE);
		
		var printed = ConfigUtils.configBoolean(Log.class.getName() + "#printed", false);
		if(!printed && ConfigDefs.PRINT_FLAGS.resolveVal()){
			System.setProperty(Log.class.getName() + "#printed", "true");
			var values = ConfigTools.collectConfigFlags();
			info(
				"{#yellowBrightRunning with debugging:#}\n" +
				ConfigTools.configFlagsToTable(values, 4, true)
			);
			
			var existingNames = Iters.from(values).map(ConfigTools.ConfEntry::name).toModSet();
			if(scanBadFlags(existingNames)){
				Thread.startVirtualThread(() -> {
					UtilL.sleep(1000);
					scanBadFlags(existingNames);
				});
			}
		}
		
		LogUtil.registerSkipClass(Log.class);
	}
	
	private static boolean scanBadFlags(Set<String> existingNames){
		var badValues = Iters.keys(System.getProperties()).instancesOf(String.class)
		                     .filter(key -> key.startsWith(ConfigDefs.CONFIG_PROPERTY_PREFIX))
		                     .filter(key -> !existingNames.contains(key))
		                     .map(key -> new ConfigTools.ConfEntry(key, Objects.toString(System.getProperty(key))))
		                     .asCollection();
		
		if(!badValues.isEmpty()){
			var msg = fmt(
				"{#redBrightUnrecognised flags:#}\n{}",
				ConfigTools.configFlagsToTable(badValues, 4, false)
			).toString();
			if(ConfigDefs.STRICT_FLAGS.resolveVal()){
				throw new IllegalConfiguration("\n" + msg);
			}
			Log.info(msg);
		}
		return badValues.isEmpty();
	}
	
	public static void log(String msg){
		if(MIN) LogUtil.println(msg);
	}
	
	public static void warn(String msg)                                                    { if(WARN) w(fmt(msg)); }
	public static void warn(String msg, Object arg1)                                       { if(WARN) w(fmt(msg, arg1)); }
	public static void warn(String msg, Object arg1, Object arg2)                          { if(WARN) w(fmt(msg, arg1, arg2)); }
	public static void warn(String msg, Object arg1, Object arg2, Object arg3)             { if(WARN) w(fmt(msg, arg1, arg2, arg3)); }
	public static void warn(String msg, Object arg1, Object arg2, Object arg3, Object arg4){ if(WARN) w(fmt(msg, arg1, arg2, arg3, arg4)); }
	public static void warn(String msg, Supplier<List<?>> lazyArgs)                        { if(WARN) w(fmt(msg, lazyArgs)); }
	public static void warn(String msg, Object... args)                                    { if(WARN) w(fmt(msg, args)); }
	private static void w(String msg){
		LogUtil.printlnEr(msg);
	}
	
	public static void info(String msg)                                                    { if(INFO) i(fmt(msg)); }
	public static void info(String msg, Object arg1)                                       { if(INFO) i(fmt(msg, arg1)); }
	public static void info(String msg, Object arg1, Object arg2)                          { if(INFO) i(fmt(msg, arg1, arg2)); }
	public static void info(String msg, Object arg1, Object arg2, Object arg3)             { if(INFO) i(fmt(msg, arg1, arg2, arg3)); }
	public static void info(String msg, Object arg1, Object arg2, Object arg3, Object arg4){ if(INFO) i(fmt(msg, arg1, arg2, arg3, arg4)); }
	public static void info(String msg, Supplier<List<?>> lazyArgs)                        { if(INFO) i(fmt(msg, lazyArgs)); }
	public static void info(String msg, Object... args)                                    { if(INFO) i(fmt(msg, args)); }
	private static void i(String msg){
		LogUtil.println(msg);
	}
	
	public static void debug(String msg)                                                    { if(DEBUG) d(fmt(msg)); }
	public static void debug(String msg, Object arg1)                                       { if(DEBUG) d(fmt(msg, arg1)); }
	public static void debug(String msg, Object arg1, Object arg2)                          { if(DEBUG) d(fmt(msg, arg1, arg2)); }
	public static void debug(String msg, Object arg1, Object arg2, Object arg3)             { if(DEBUG) d(fmt(msg, arg1, arg2, arg3)); }
	public static void debug(String msg, Object arg1, Object arg2, Object arg3, Object arg4){ if(DEBUG) d(fmt(msg, arg1, arg2, arg3, arg4)); }
	public static void debug(String msg, Supplier<List<?>> lazyArgs)                        { if(DEBUG) d(fmt(msg, lazyArgs)); }
	public static void debug(String msg, Object... args)                                    { if(DEBUG) d(fmt(msg, args)); }
	private static void d(String msg){
		LogUtil.println(msg);
	}
	
	public static void traceCall()                                                              { if(TRACE) tc(""); }
	public static void traceCall(String msg)                                                    { if(TRACE) tc(msg); }
	public static void traceCall(String msg, Object arg1)                                       { if(TRACE) tc(fmt(msg, arg1)); }
	public static void traceCall(String msg, Object arg1, Object arg2)                          { if(TRACE) tc(fmt(msg, arg1, arg2)); }
	public static void traceCall(String msg, Object arg1, Object arg2, Object arg3)             { if(TRACE) tc(fmt(msg, arg1, arg2, arg3)); }
	public static void traceCall(String msg, Object arg1, Object arg2, Object arg3, Object arg4){ if(TRACE) tc(fmt(msg, arg1, arg2, arg3, arg4)); }
	public static void traceCall(String msg, Supplier<List<?>> lazyArgs)                        { if(TRACE) tc(fmt(msg, lazyArgs)); }
	public static void traceCall(String msg, Object... args)                                    { if(TRACE) tc(fmt(msg, args)); }
	private static void tc(String msg){
		var frame = getFrame(2);
		var sb    = new StringBuilder();
		if(!msg.isEmpty()) sb.append(ConsoleColors.BLACK_BRIGHT);
		Utils.frameToStr(sb, frame);
		if(!msg.isEmpty()) sb.append(" ->\t").append(ConsoleColors.RESET).append(msg);
		trace(sb.toString());
	}
	
	public static void trace(String msg, Object arg1)                                       { if(TRACE) t(fmt(msg, arg1)); }
	public static void trace(String msg, Object arg1, Object arg2)                          { if(TRACE) t(fmt(msg, arg1, arg2)); }
	public static void trace(String msg, Object arg1, Object arg2, Object arg3)             { if(TRACE) t(fmt(msg, arg1, arg2, arg3)); }
	public static void trace(String msg, Object arg1, Object arg2, Object arg3, Object arg4){ if(TRACE) t(fmt(msg, arg1, arg2, arg3, arg4)); }
	public static void trace(String msg, Supplier<List<?>> lazyArgs)                        { if(TRACE) t(fmt(msg, lazyArgs)); }
	public static void trace(String msg, Object... args)                                    { if(TRACE) t(fmt(msg, args)); }
	public static void trace(String msg)                                                    { if(TRACE) t(fmt(msg)); }
	private static void t(String msg){
		if(TRACE) LogUtil.println(msg);
	}
	
	public static void smallTrace(String msg, Object arg1)                                     { if(SMALL_TRACE) st(fmt(msg, arg1)); }
	public static void smallTrace(String msg, Object arg1, Object arg2)                        { if(SMALL_TRACE) st(fmt(msg, arg1, arg2)); }
	public static void smallTrace(String msg, Object arg1, Object arg2, Object arg3)           { if(SMALL_TRACE) st(fmt(msg, arg1, arg2, arg3)); }
	public static void smallTrace(String msg, Object arg1, Object arg2, Object arg3, Object o4){ if(SMALL_TRACE) st(fmt(msg, arg1, arg2, arg3, o4)); }
	public static void smallTrace(String msg, Supplier<List<?>> lazyArgs)                      { if(SMALL_TRACE) st(fmt(msg, lazyArgs)); }
	public static void smallTrace(String msg, Object... args)                                  { if(SMALL_TRACE) st(fmt(msg, args)); }
	public static void smallTrace(String msg)                                                  { if(SMALL_TRACE) st(fmt(msg)); }
	private static void st(String msg){
		if(SMALL_TRACE) LogUtil.println(msg);
	}
	
	public static void nonFatal(Throwable error, String msg){
		if(WARN) nonFatal0(error, msg);
	}
	public static void nonFatal(Throwable error, String msg, Object... args){
		if(WARN) nonFatal0(error, fmt(Objects.requireNonNull(msg), args));
	}
	
	public static String fmt(String msg, Object arg1, Object arg2, Object arg3, Object arg4){
		var formatted = new StringBuilder(msg.length() + 32);
		rawToMsg(msg, formatted);
		int start = 0;
		start = resolveArg(formatted, arg1, start);
		start = resolveArg(formatted, arg2, start);
		start = resolveArg(formatted, arg3, start);
		start = resolveArg(formatted, arg4, start);
		return formatted.toString();
	}
	public static String fmt(String msg, Object arg1, Object arg2, Object arg3){
		var formatted = new StringBuilder(msg.length() + 32);
		rawToMsg(msg, formatted);
		int start = 0;
		start = resolveArg(formatted, arg1, start);
		start = resolveArg(formatted, arg2, start);
		start = resolveArg(formatted, arg3, start);
		return formatted.toString();
	}
	public static String fmt(String msg, Object arg1, Object arg2){
		var formatted = new StringBuilder(msg.length() + 32);
		rawToMsg(msg, formatted);
		int start = 0;
		start = resolveArg(formatted, arg1, start);
		start = resolveArg(formatted, arg2, start);
		return formatted.toString();
	}
	public static String fmt(String msg, Object arg1){
		var formatted = new StringBuilder(msg.length() + 32);
		rawToMsg(msg, formatted);
		resolveArg(formatted, arg1, 0);
		return formatted.toString();
	}
	public static String fmt(String msg){
		var formatted = new StringBuilder(msg.length());
		rawToMsg(msg, formatted);
		return formatted.toString();
	}
	
	public static String fmt(String msg, Object... args){
		var formatted = new StringBuilder(msg.length() + 32);
		rawToMsg(msg, formatted);
		int start = 0;
		for(Object arg : args){
			start = resolveArg(formatted, arg, start);
		}
		return formatted.toString();
	}
	
	private static String fmt(String msg, Supplier<List<?>> args){
		var formatted = new StringBuilder(msg.length() + 32);
		rawToMsg(msg, formatted);
		int start = 0;
		for(Object arg : args.get()){
			start = resolveArg(formatted, arg, start);
		}
		return formatted.toString();
	}
	
	private static void rawToMsg(String msg, StringBuilder formatted){
		String       last       = ConsoleColors.RESET;
		List<String> colorStack = new ArrayList<>();
		
		for(int i = 0; i<msg.length(); i++){
			var c = msg.charAt(i);
			
			if(i<msg.length() - 1){
				if(c == '{' && msg.charAt(i + 1) == '#'){
					var col = findColor(msg, i + 2).orElseThrow(
						() -> new IllegalArgumentException("Illegal log format, opened format block with {#... could not find a valid color. Valid colors: " +
						                                   Iters.from(COLORS).joinAsStr(", ", "[", "]", Tag::name)));
					var cmd = col.cmd;
					formatted.append(cmd);
					colorStack.add(last);
					last = cmd;
					i += 1 + col.name.length();
					continue;
				}else if(c == '#' && msg.charAt(i + 1) == '}'){
					var col = colorStack.removeLast();
					formatted.append(col);
					last = col;
					i++;
					continue;
				}
			}
			
			formatted.append(c);
		}
		if(!colorStack.isEmpty()){
			throw new IllegalArgumentException("Illegal log format, opened format block with {#... but did not close it with #}");
		}
		formatted.append(ConsoleColors.RESET);
	}
	
	private static int resolveArg(StringBuilder formatted, Object arg, int start){
		if(arg instanceof Supplier<?> supplier) arg = supplier.get();
		for(int i = start; i<formatted.length() - 1; i++){
			var c1 = formatted.charAt(i);
			var c2 = formatted.charAt(i + 1);
			if(c1 == '{' && c2 == '}'){
				var c3 = formatted.length()>i + 2? formatted.charAt(i + 2) : 0;
				if(c3 == '~'){
					var replace = arg instanceof Type typ? Utils.typeToHuman(typ) : Utils.toShortString(arg);
					formatted.replace(i, i + 3, replace);
					return i + replace.length();
				}else if(c3 == '#'){
					int hStart = i + 3;
					var any    = findColor(formatted, hStart);
					if(any.isPresent()){
						var replace = any.get().cmd + TextUtil.toString(arg) + ConsoleColors.RESET;
						formatted.replace(i, hStart + any.get().name.length(), replace);
						return i + replace.length();
					}
				}
				
				String replace;
				if(arg instanceof Boolean bool){
					
					String        activeStyle = ConsoleColors.RESET;
					StringBuilder buff        = new StringBuilder();
					for(int j = 0; j<i; j++){
						if(formatted.charAt(i) == '\033'){
							for(int j1 = j; j1<i; j1++){
								var cp = formatted.charAt(i);
								buff.append(cp);
								if(cp == 'm'){
									activeStyle = buff.toString();
									buff.setLength(0);
									j = j1;
									break;
								}
							}
						}
					}
					if(!activeStyle.equals(ConsoleColors.RESET)) replace = bool + "";
					else{
						if(bool) replace = ConsoleColors.GREEN_BRIGHT + "true" + activeStyle;
						else replace = ConsoleColors.RED_BRIGHT + "false" + activeStyle;
					}
				}else{
					replace = TextUtil.toString(arg);
				}
				formatted.replace(i, i + 2, replace);
				return i + replace.length();
			}
		}
		
		throw new IllegalArgumentException(
			"Could not find {} / {}~ / {}#<color_name> in:\n" +
			formatted.substring(start) + (
				start == 0?
				"" :
				"\n" +
				"Full formatted string:\n" +
				formatted
			)
		);
	}
	
	private static OptionalPP<Tag> findColor(CharSequence formatted, int hStart){
		int len = formatted.length() - hStart;
		return Iters.from(COLORS)
		            .filter(e -> {
			            if(e.name.length()>len) return false;
			            var s = e.name;
			            for(int j = 0; j<s.length(); j++){
				            var z1 = s.charAt(j);
				            var z2 = Character.toUpperCase(formatted.charAt(hStart + j));
				            if(z1 != z2) return false;
			            }
			            return true;
		            })
		            .limit(2)
		            .maxByI(c -> c.name.length());
	}
	
	private static void nonFatal0(Throwable error, CharSequence msg){
		var sb = new StringBuilder();
		
		Utils.frameToStr(sb, getFrame(1));
		if(msg != null) sb.append('\n').append("\nMessage: ").append(msg);
		
		Throwable e = error;
		while(e != null){
			
			sb.append('\n');
			Utils.classToStr(sb, e.getClass());
			var lmsg = e.getLocalizedMessage();
			if(lmsg != null) sb.append(": ").append(lmsg);
			
			e = e.getCause();
		}
		
		warn(sb.toString());
	}
	
}
