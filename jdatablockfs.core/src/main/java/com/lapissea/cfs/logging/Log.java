package com.lapissea.cfs.logging;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.config.ConfigDefs;
import com.lapissea.cfs.config.ConfigTools;
import com.lapissea.cfs.exceptions.IllegalConfiguration;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lapissea.cfs.Utils.getFrame;

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
		var level = ConfigDefs.LOG_LEVEL.resolve();
		MIN = level.isWithin(LogLevel.MIN);
		WARN = level.isWithin(LogLevel.WARN);
		INFO = level.isWithin(LogLevel.INFO);
		DEBUG = level.isWithin(LogLevel.DEBUG);
		TRACE = level.isWithin(LogLevel.TRACE);
		SMALL_TRACE = level.isWithin(LogLevel.SMALL_TRACE);
		
		if(ConfigDefs.PRINT_FLAGS.resolveVal()){
			var values = ConfigTools.collectConfigFlags();
			info(
				"{#yellowBrightRunning with debugging:#}\n" +
				ConfigTools.configFlagsToTable(values, 4, true)
			);
			
			var existingNames = values.stream().map(ConfigTools.ConfEntry::name).collect(Collectors.toSet());
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
		var badValues = System.getProperties().keySet().stream().filter(String.class::isInstance).map(o -> (String)o)
		                      .filter(key -> key.startsWith(ConfigDefs.CONFIG_PROPERTY_PREFIX))
		                      .filter(key -> !existingNames.contains(key))
		                      .map(key -> new ConfigTools.ConfEntry(key, Objects.toString(System.getProperty(key))))
		                      .toList();
		
		if(!badValues.isEmpty()){
			var msg = resolveArgs(
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
	
	public static void log(String message){
		if(MIN) LogUtil.println(message);
	}
	
	public static void warn(String message)                                                    { if(WARN) warn0(resolveArgs(message)); }
	public static void warn(String message, Object arg1)                                       { if(WARN) warn0(resolveArgs(message, arg1)); }
	public static void warn(String message, Object arg1, Object arg2)                          { if(WARN) warn0(resolveArgs(message, arg1, arg2)); }
	public static void warn(String message, Object arg1, Object arg2, Object arg3)             { if(WARN) warn0(resolveArgs(message, arg1, arg2, arg3)); }
	public static void warn(String message, Object arg1, Object arg2, Object arg3, Object arg4){ if(WARN) warn0(resolveArgs(message, arg1, arg2, arg3, arg4)); }
	public static void warn(String message, Supplier<List<?>> lazyArgs)                        { if(WARN) warn0(resolveArgs(message, lazyArgs)); }
	public static void warn(String message, Object... args)                                    { if(WARN) warn0(resolveArgs(message, args)); }
	private static void warn0(CharSequence message){
		LogUtil.printlnEr(message);
	}
	
	public static void info(String message)                                                    { if(INFO) info0(resolveArgs(message)); }
	public static void info(String message, Object arg1)                                       { if(INFO) info0(resolveArgs(message, arg1)); }
	public static void info(String message, Object arg1, Object arg2)                          { if(INFO) info0(resolveArgs(message, arg1, arg2)); }
	public static void info(String message, Object arg1, Object arg2, Object arg3)             { if(INFO) info0(resolveArgs(message, arg1, arg2, arg3)); }
	public static void info(String message, Object arg1, Object arg2, Object arg3, Object arg4){ if(INFO) info0(resolveArgs(message, arg1, arg2, arg3, arg4)); }
	public static void info(String message, Supplier<List<?>> lazyArgs)                        { if(INFO) info0(resolveArgs(message, lazyArgs)); }
	public static void info(String message, Object... args)                                    { if(INFO) info0(resolveArgs(message, args)); }
	private static void info0(CharSequence message){
		LogUtil.println(message);
	}
	
	public static void debug(String message)                                                    { if(DEBUG) debug0(resolveArgs(message)); }
	public static void debug(String message, Object arg1)                                       { if(DEBUG) debug0(resolveArgs(message, arg1)); }
	public static void debug(String message, Object arg1, Object arg2)                          { if(DEBUG) debug0(resolveArgs(message, arg1, arg2)); }
	public static void debug(String message, Object arg1, Object arg2, Object arg3)             { if(DEBUG) debug0(resolveArgs(message, arg1, arg2, arg3)); }
	public static void debug(String message, Object arg1, Object arg2, Object arg3, Object arg4){ if(DEBUG) debug0(resolveArgs(message, arg1, arg2, arg3, arg4)); }
	public static void debug(String message, Supplier<List<?>> lazyArgs)                        { if(DEBUG) debug0(resolveArgs(message, lazyArgs)); }
	public static void debug(String message, Object... args)                                    { if(DEBUG) debug0(resolveArgs(message, args)); }
	private static void debug0(CharSequence message){
		LogUtil.println(message);
	}
	
	public static void traceCall()                                                                  { if(TRACE) traceCall0("", getFrame(1)); }
	public static void traceCall(String message)                                                    { if(TRACE) traceCall0(message, getFrame(1)); }
	public static void traceCall(String message, Object arg1)                                       { if(TRACE) traceCall0(resolveArgs(message, arg1), getFrame(1)); }
	public static void traceCall(String message, Object arg1, Object arg2)                          { if(TRACE) traceCall0(resolveArgs(message, arg1, arg2), getFrame(1)); }
	public static void traceCall(String message, Object arg1, Object arg2, Object arg3)             { if(TRACE) traceCall0(resolveArgs(message, arg1, arg2, arg3), getFrame(1)); }
	public static void traceCall(String message, Object arg1, Object arg2, Object arg3, Object arg4){ if(TRACE) traceCall0(resolveArgs(message, arg1, arg2, arg3, arg4), getFrame(1)); }
	public static void traceCall(String message, Supplier<List<?>> lazyArgs)                        { if(TRACE) traceCall0(resolveArgs(message, lazyArgs), getFrame(1)); }
	public static void traceCall(String message, Object... args)                                    { if(TRACE) traceCall0(resolveArgs(message, args), getFrame(1)); }
	private static void traceCall0(CharSequence message, StackWalker.StackFrame frame){
		var sb = new StringBuilder();
		if(!message.isEmpty()) sb.append(ConsoleColors.BLACK_BRIGHT);
		Utils.frameToStr(sb, frame);
		if(!message.isEmpty()) sb.append(" ->\t").append(ConsoleColors.RESET).append(message);
		trace(sb.toString());
	}
	
	public static void trace(String message, Object arg1)                                       { if(TRACE) trace0(resolveArgs(message, arg1)); }
	public static void trace(String message, Object arg1, Object arg2)                          { if(TRACE) trace0(resolveArgs(message, arg1, arg2)); }
	public static void trace(String message, Object arg1, Object arg2, Object arg3)             { if(TRACE) trace0(resolveArgs(message, arg1, arg2, arg3)); }
	public static void trace(String message, Object arg1, Object arg2, Object arg3, Object arg4){ if(TRACE) trace0(resolveArgs(message, arg1, arg2, arg3, arg4)); }
	public static void trace(String message, Supplier<List<?>> lazyArgs)                        { if(TRACE) trace0(resolveArgs(message, lazyArgs)); }
	public static void trace(String message, Object... args)                                    { if(TRACE) trace0(resolveArgs(message, args)); }
	public static void trace(String message)                                                    { if(TRACE) trace0(resolveArgs(message)); }
	private static void trace0(CharSequence message){
		if(TRACE) LogUtil.println(message);
	}
	
	public static void smallTrace(String message, Object arg1)                                       { if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1)); }
	public static void smallTrace(String message, Object arg1, Object arg2)                          { if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1, arg2)); }
	public static void smallTrace(String message, Object arg1, Object arg2, Object arg3)             { if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1, arg2, arg3)); }
	public static void smallTrace(String message, Object arg1, Object arg2, Object arg3, Object arg4){ if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1, arg2, arg3, arg4)); }
	public static void smallTrace(String message, Supplier<List<?>> lazyArgs)                        { if(SMALL_TRACE) smallTrace0(resolveArgs(message, lazyArgs)); }
	public static void smallTrace(String message, Object... args)                                    { if(SMALL_TRACE) smallTrace0(resolveArgs(message, args)); }
	public static void smallTrace(String message)                                                    { if(SMALL_TRACE) smallTrace0(resolveArgs(message)); }
	private static void smallTrace0(CharSequence message){
		if(SMALL_TRACE) LogUtil.println(message);
	}
	
	public static void nonFatal(Throwable error, String message){
		if(WARN) nonFatal0(error, message);
	}
	public static void nonFatal(Throwable error, String message, Object... args){
		if(WARN) nonFatal0(error, resolveArgs(Objects.requireNonNull(message), args));
	}
	
	private static StringBuilder resolveArgs(String message, Object arg1, Object arg2, Object arg3){
		var formatted = new StringBuilder(message.length() + 32);
		rawToMsg(message, formatted);
		int start = 0;
		start = resolveArg(formatted, arg1, start);
		start = resolveArg(formatted, arg2, start);
		start = resolveArg(formatted, arg3, start);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Object arg1, Object arg2, Object arg3, Object arg4){
		var formatted = new StringBuilder(message.length() + 32);
		rawToMsg(message, formatted);
		int start = 0;
		start = resolveArg(formatted, arg1, start);
		start = resolveArg(formatted, arg2, start);
		start = resolveArg(formatted, arg3, start);
		start = resolveArg(formatted, arg4, start);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Object arg1, Object arg2){
		var formatted = new StringBuilder(message.length() + 32);
		rawToMsg(message, formatted);
		int start = 0;
		start = resolveArg(formatted, arg1, start);
		start = resolveArg(formatted, arg2, start);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Object arg1){
		var formatted = new StringBuilder(message.length() + 32);
		rawToMsg(message, formatted);
		resolveArg(formatted, arg1, 0);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message){
		var formatted = new StringBuilder(message.length());
		rawToMsg(message, formatted);
		return formatted;
	}
	
	public static StringBuilder resolveArgs(String message, Object... args){
		var formatted = new StringBuilder(message.length() + 32);
		rawToMsg(message, formatted);
		int start = 0;
		for(Object arg : args){
			start = resolveArg(formatted, arg, start);
		}
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Supplier<List<?>> args){
		var formatted = new StringBuilder(message.length() + 32);
		rawToMsg(message, formatted);
		int start = 0;
		for(Object arg : args.get()){
			start = resolveArg(formatted, arg, start);
		}
		return formatted;
	}
	
	private static void rawToMsg(String message, StringBuilder formatted){
		String       last       = ConsoleColors.RESET;
		List<String> colorStack = new ArrayList<>();
		
		for(int i = 0; i<message.length(); i++){
			var c = message.charAt(i);
			
			if(i<message.length() - 1){
				if(c == '{' && message.charAt(i + 1) == '#'){
					var col = findColor(message, i + 2).orElseThrow(
						() -> new IllegalArgumentException("Illegal log format, opened format block with {#... could not find a valid color. Valid colors: " +
						                                   COLORS.stream().map(Tag::name).collect(Collectors.joining(", ", "[", "]"))));
					var cmd = col.cmd;
					formatted.append(cmd);
					colorStack.add(last);
					last = cmd;
					i += 1 + col.name.length();
					continue;
				}else if(c == '#' && message.charAt(i + 1) == '}'){
					var col = colorStack.remove(colorStack.size() - 1);
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
				if(formatted.length()>i + 2){
					var c3 = formatted.charAt(i + 2);
					if(c3 == '~'){
						var replace = arg instanceof Type typ? Utils.typeToHuman(typ, false) : Utils.toShortString(arg);
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
		
		throw new IllegalArgumentException();
	}
	
	private static Optional<Tag> findColor(CharSequence formatted, int hStart){
		int len = formatted.length() - hStart;
		return COLORS.stream()
		             .filter(e -> e.name.length()<=len)
		             .filter(e -> {
			             var s = e.name;
			             for(int j = 0; j<s.length(); j++){
				             var z1 = s.charAt(j);
				             var z2 = Character.toUpperCase(formatted.charAt(hStart + j));
				             if(z1 != z2) return false;
			             }
			             return true;
		             })
		             .limit(2)
		             .reduce((r, l) -> r.name.length()>
		                               l.name.length()?
		                               r : l);
	}
	
	public static void nonFatal0(Throwable error, CharSequence message){
		var sb = new StringBuilder();
		
		Utils.frameToStr(sb, getFrame(1));
		if(message != null) sb.append('\n').append("\nMessage: ").append(message);
		
		Throwable e = error;
		while(e != null){
			
			sb.append('\n');
			Utils.classToStr(sb, e.getClass());
			var msg = e.getLocalizedMessage();
			if(msg != null) sb.append(": ").append(msg);
			
			e = e.getCause();
		}
		
		warn(sb.toString());
	}
	
}
