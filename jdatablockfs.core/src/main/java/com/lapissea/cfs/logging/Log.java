package com.lapissea.cfs.logging;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.cfs.Utils.getFrame;

public class Log{
	
	private static final int NONE_LEVEL       =Integer.MIN_VALUE;
	private static final int MIN_LEVEL        =0;
	private static final int WARN_LEVEL       =1;
	private static final int INFO_LEVEL       =2;
	private static final int DEBUG_LEVEL      =3;
	private static final int TRACE_LEVEL      =4;
	private static final int SMALL_TRACE_LEVEL=5;
	private static final int ALL_LEVEL        =Integer.MAX_VALUE;
	
	private static final int LEVEL=GlobalConfig.configProp("log.level").map(String::toUpperCase).map(level->switch(level){
		case "NONE" -> NONE_LEVEL;
		case "MIN" -> MIN_LEVEL;
		case "WARN" -> WARN_LEVEL;
		case "INFO" -> INFO_LEVEL;
		case "DEBUG" -> DEBUG_LEVEL;
		case "TRACE" -> TRACE_LEVEL;
		case "SMALL_TRACE" -> SMALL_TRACE_LEVEL;
		case "ALL" -> ALL_LEVEL;
		default -> throw new IllegalStateException(level+" is not a recognised logging level");
	}).orElse(GlobalConfig.RELEASE_MODE?WARN_LEVEL:INFO_LEVEL);
	
	public static final boolean MIN        =MIN_LEVEL>=LEVEL;
	public static final boolean WARN       =WARN_LEVEL>=LEVEL;
	public static final boolean INFO       =INFO_LEVEL>=LEVEL;
	public static final boolean DEBUG      =DEBUG_LEVEL>=LEVEL;
	public static final boolean TRACE      =TRACE_LEVEL>=LEVEL;
	public static final boolean SMALL_TRACE=SMALL_TRACE_LEVEL>=LEVEL;
	public static final boolean ALL        =true;
	
	static{
		if(GlobalConfig.DEBUG_VALIDATION){
			info(
				"""
					Running with debugging:
						RELEASE_MODE: {}
						TYPE_VALIDATION: {}
						PRINT_COMPILATION: {}
					""",
				GlobalConfig.RELEASE_MODE, GlobalConfig.TYPE_VALIDATION, GlobalConfig.PRINT_COMPILATION
			);
		}
	}
	
	public static final class Channel{
		
		public static Function<StringBuilder, StringBuilder> colored(String color){
			return msg->{
				msg.insert(0, color);
				msg.append(ConsoleColors.RESET);
				return msg;
			};
		}
		
		private static final Channel NOOP=new Channel(null, false);
		
		private final Function<StringBuilder, StringBuilder> receiver;
		private final boolean                                enabled;
		
		private Channel(Function<StringBuilder, StringBuilder> receiver){
			this(receiver, true);
		}
		private Channel(Function<StringBuilder, StringBuilder> receiver, boolean enabled){
			this.receiver=receiver;
			this.enabled=enabled;
		}
		
		public void on(Runnable task){
			if(isEnabled()){
				task.run();
			}
		}
		
		public boolean isEnabled(){
			return enabled;
		}
		
		private synchronized StringBuilder map(StringBuilder message){
			return receiver.apply(message);
		}
		
		public void log(String message)                                                    {if(enabled) log(resolveArgs(message));}
		public void log(String message, Object arg1)                                       {if(enabled) log(resolveArgs(message, arg1));}
		public void log(String message, Object arg1, Object arg2)                          {if(enabled) log(resolveArgs(message, arg1, arg2));}
		public void log(String message, Object arg1, Object arg2, Object arg3)             {if(enabled) log(resolveArgs(message, arg1, arg2, arg3));}
		public void log(String message, Object arg1, Object arg2, Object arg3, Object arg4){if(enabled) log(resolveArgs(message, arg1, arg2, arg3, arg4));}
		public void log(String message, Supplier<List<?>> lazyArgs)                        {if(enabled) log(resolveArgs(message, lazyArgs));}
		public void log(String message, Object... args)                                    {if(enabled) log(resolveArgs(message, args));}
		private void log(StringBuilder message){
			log0(receiver.apply(message));
		}
	}
	
	public static Channel channel(boolean enabled, Function<StringBuilder, StringBuilder> out){
		if(!enabled&&MIN) return Channel.NOOP;
		return new Channel(out);
	}
	
	public static void log(String message){
		if(LEVEL>=MIN_LEVEL) LogUtil.println(message);
	}
	public static void log0(CharSequence message){
		LogUtil.println(message);
	}
	
	
	public static void warn(String message)                                                    {if(WARN) warn0(resolveArgs(message));}
	public static void warn(String message, Object arg1)                                       {if(WARN) warn0(resolveArgs(message, arg1));}
	public static void warn(String message, Object arg1, Object arg2)                          {if(WARN) warn0(resolveArgs(message, arg1, arg2));}
	public static void warn(String message, Object arg1, Object arg2, Object arg3)             {if(WARN) warn0(resolveArgs(message, arg1, arg2, arg3));}
	public static void warn(String message, Object arg1, Object arg2, Object arg3, Object arg4){if(WARN) warn0(resolveArgs(message, arg1, arg2, arg3, arg4));}
	public static void warn(String message, Supplier<List<?>> lazyArgs)                        {if(WARN) warn0(resolveArgs(message, lazyArgs));}
	public static void warn(String message, Object... args)                                    {if(WARN) warn0(resolveArgs(message, args));}
	private static void warn0(CharSequence message){
		LogUtil.printlnEr(message);
	}
	
	public static void info(String message)                                                    {if(INFO) info0(resolveArgs(message));}
	public static void info(String message, Object arg1)                                       {if(INFO) info0(resolveArgs(message, arg1));}
	public static void info(String message, Object arg1, Object arg2)                          {if(INFO) info0(resolveArgs(message, arg1, arg2));}
	public static void info(String message, Object arg1, Object arg2, Object arg3)             {if(INFO) info0(resolveArgs(message, arg1, arg2, arg3));}
	public static void info(String message, Object arg1, Object arg2, Object arg3, Object arg4){if(INFO) info0(resolveArgs(message, arg1, arg2, arg3, arg4));}
	public static void info(String message, Supplier<List<?>> lazyArgs)                        {if(INFO) info0(resolveArgs(message, lazyArgs));}
	public static void info(String message, Object... args)                                    {if(INFO) info0(resolveArgs(message, args));}
	private static void info0(CharSequence message){
		LogUtil.println(message);
	}
	
	public static void debug(String message)                                                    {if(DEBUG) debug0(resolveArgs(message));}
	public static void debug(String message, Object arg1)                                       {if(DEBUG) debug0(resolveArgs(message, arg1));}
	public static void debug(String message, Object arg1, Object arg2)                          {if(DEBUG) debug0(resolveArgs(message, arg1, arg2));}
	public static void debug(String message, Object arg1, Object arg2, Object arg3)             {if(DEBUG) debug0(resolveArgs(message, arg1, arg2, arg3));}
	public static void debug(String message, Object arg1, Object arg2, Object arg3, Object arg4){if(DEBUG) debug0(resolveArgs(message, arg1, arg2, arg3, arg4));}
	public static void debug(String message, Supplier<List<?>> lazyArgs)                        {if(DEBUG) debug0(resolveArgs(message, lazyArgs));}
	public static void debug(String message, Object... args)                                    {if(DEBUG) debug0(resolveArgs(message, args));}
	private static void debug0(CharSequence message){
		LogUtil.println(message);
	}
	
	public static void traceCall()                                                                  {if(TRACE) traceCall0("", getFrame(1));}
	public static void traceCall(String message)                                                    {if(TRACE) traceCall0(message, getFrame(1));}
	public static void traceCall(String message, Object arg1)                                       {if(TRACE) traceCall0(resolveArgs(message, arg1), getFrame(1));}
	public static void traceCall(String message, Object arg1, Object arg2)                          {if(TRACE) traceCall0(resolveArgs(message, arg1, arg2), getFrame(1));}
	public static void traceCall(String message, Object arg1, Object arg2, Object arg3)             {if(TRACE) traceCall0(resolveArgs(message, arg1, arg2, arg3), getFrame(1));}
	public static void traceCall(String message, Object arg1, Object arg2, Object arg3, Object arg4){if(TRACE) traceCall0(resolveArgs(message, arg1, arg2, arg3, arg4), getFrame(1));}
	public static void traceCall(String message, Supplier<List<?>> lazyArgs)                        {if(TRACE) traceCall0(resolveArgs(message, lazyArgs), getFrame(1));}
	public static void traceCall(String message, Object... args)                                    {if(TRACE) traceCall0(resolveArgs(message, args), getFrame(1));}
	private static void traceCall0(CharSequence message, StackWalker.StackFrame frame){
		var sb=new StringBuilder();
		if(!message.isEmpty()) sb.append(ConsoleColors.BLACK_BRIGHT);
		Utils.frameToStr(sb, frame);
		if(!message.isEmpty()) sb.append(" ->\t").append(ConsoleColors.RESET).append(message);
		trace(sb.toString());
	}
	
	public static void trace(String message, Object arg1)                                       {if(TRACE) trace0(resolveArgs(message, arg1));}
	public static void trace(String message, Object arg1, Object arg2)                          {if(TRACE) trace0(resolveArgs(message, arg1, arg2));}
	public static void trace(String message, Object arg1, Object arg2, Object arg3)             {if(TRACE) trace0(resolveArgs(message, arg1, arg2, arg3));}
	public static void trace(String message, Object arg1, Object arg2, Object arg3, Object arg4){if(TRACE) trace0(resolveArgs(message, arg1, arg2, arg3, arg4));}
	public static void trace(String message, Supplier<List<?>> lazyArgs)                        {if(TRACE) trace0(resolveArgs(message, lazyArgs));}
	public static void trace(String message, Object... args)                                    {if(TRACE) trace0(resolveArgs(message, args));}
	public static void trace(String message)                                                    {if(TRACE) trace0(resolveArgs(message));}
	private static void trace0(CharSequence message){
		if(TRACE) LogUtil.println(message);
	}
	
	public static void smallTrace(String message, Object arg1)                                       {if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1));}
	public static void smallTrace(String message, Object arg1, Object arg2)                          {if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1, arg2));}
	public static void smallTrace(String message, Object arg1, Object arg2, Object arg3)             {if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1, arg2, arg3));}
	public static void smallTrace(String message, Object arg1, Object arg2, Object arg3, Object arg4){if(SMALL_TRACE) smallTrace0(resolveArgs(message, arg1, arg2, arg3, arg4));}
	public static void smallTrace(String message, Supplier<List<?>> lazyArgs)                        {if(SMALL_TRACE) smallTrace0(resolveArgs(message, lazyArgs));}
	public static void smallTrace(String message, Object... args)                                    {if(SMALL_TRACE) smallTrace0(resolveArgs(message, args));}
	public static void smallTrace(String message)                                                    {if(SMALL_TRACE) smallTrace0(resolveArgs(message));}
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
		var formatted=new StringBuilder(message.length()+32);
		rawToMsg(message, formatted);
		int start=0;
		start=resolveArg(formatted, arg1, start);
		start=resolveArg(formatted, arg2, start);
		start=resolveArg(formatted, arg3, start);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Object arg1, Object arg2, Object arg3, Object arg4){
		var formatted=new StringBuilder(message.length()+32);
		rawToMsg(message, formatted);
		int start=0;
		start=resolveArg(formatted, arg1, start);
		start=resolveArg(formatted, arg2, start);
		start=resolveArg(formatted, arg3, start);
		start=resolveArg(formatted, arg4, start);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Object arg1, Object arg2){
		var formatted=new StringBuilder(message.length()+32);
		rawToMsg(message, formatted);
		int start=0;
		start=resolveArg(formatted, arg1, start);
		start=resolveArg(formatted, arg2, start);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Object arg1){
		var formatted=new StringBuilder(message.length()+32);
		rawToMsg(message, formatted);
		resolveArg(formatted, arg1, 0);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message){
		var formatted=new StringBuilder(message.length());
		rawToMsg(message, formatted);
		return formatted;
	}
	
	private static StringBuilder resolveArgs(String message, Object[] args){
		var formatted=new StringBuilder(message.length()+32);
		rawToMsg(message, formatted);
		int start=0;
		for(Object arg : args){
			start=resolveArg(formatted, arg, start);
		}
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Supplier<List<?>> args){
		var formatted=new StringBuilder(message.length()+32);
		rawToMsg(message, formatted);
		int start=0;
		for(Object arg : args.get()){
			start=resolveArg(formatted, arg, start);
		}
		return formatted;
	}
	
	private static void rawToMsg(String message, StringBuilder formatted){
		String       last      =ConsoleColors.RESET;
		List<String> colorStack=new ArrayList<>();
		
		for(int i=0;i<message.length();i++){
			var c=message.charAt(i);
			
			if(i<message.length()-1){
				if(c=='{'&&message.charAt(i+1)=='#'){
					var colO=findColor(message, i+2);
					if(colO.isPresent()){
						var col=colO.get();
						var cmd=col.cmd;
						formatted.append(cmd);
						colorStack.add(last);
						last=cmd;
						i+=1+col.name.length();
						continue;
					}
				}else if(c=='#'&&message.charAt(i+1)=='}'){
					formatted.append(colorStack.remove(colorStack.size()-1));
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
	
	private record Tag(String name, String cmd){}
	
	private static final List<Tag> COLORS=List.of(
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
	
	private static int resolveArg(StringBuilder formatted, Object arg, int start){
		if(arg instanceof Supplier<?> supplier) arg=supplier.get();
		for(int i=start;i<formatted.length()-1;i++){
			var c1=formatted.charAt(i);
			var c2=formatted.charAt(i+1);
			if(c1=='{'&&c2=='}'){
				if(formatted.length()>i+2){
					var c3=formatted.charAt(i+2);
					if(c3=='#'){
						int hStart=i+3;
						var any   =findColor(formatted, hStart);
						if(any.isPresent()){
							var replace=any.get().cmd+TextUtil.toString(arg)+ConsoleColors.RESET;
							formatted.replace(i, hStart+any.get().name.length(), replace);
							return i+replace.length();
						}
					}
				}
				
				String replace;
				if(arg instanceof Boolean bool){
					
					String        activeStyle=ConsoleColors.RESET;
					StringBuilder buff       =new StringBuilder();
					for(int j=0;j<i;j++){
						if(formatted.charAt(i)=='\033'){
							for(int j1=j;j1<i;j1++){
								var cp=formatted.charAt(i);
								buff.append(cp);
								if(cp=='m'){
									activeStyle=buff.toString();
									buff.setLength(0);
									j=j1;
									break;
								}
							}
						}
					}
					if(!activeStyle.equals(ConsoleColors.RESET)) replace=bool+"";
					else{
						if(bool) replace=ConsoleColors.GREEN_BRIGHT+"true"+activeStyle;
						else replace=ConsoleColors.RED_BRIGHT+"false"+activeStyle;
					}
				}else{
					replace=TextUtil.toString(arg);
				}
				formatted.replace(i, i+2, replace);
				return i+replace.length();
			}
		}
		
		throw new IllegalArgumentException();
	}
	
	private static Optional<Tag> findColor(CharSequence formatted, int hStart){
		int len=formatted.length()-hStart;
		return COLORS.stream()
		             .filter(e->e.name.length()<=len)
		             .filter(e->{
			             var s=e.name;
			             for(int j=0;j<s.length();j++){
				             var z1=s.charAt(j);
				             var z2=Character.toUpperCase(formatted.charAt(hStart+j));
				             if(z1!=z2) return false;
			             }
			             return true;
		             })
		             .limit(2)
		             .reduce((r, l)->r.name.length()>
		                             l.name.length()?
		                             r:l);
	}
	
	public static void nonFatal0(Throwable error, CharSequence message){
		var sb=new StringBuilder();
		
		Utils.frameToStr(sb, getFrame(1));
		if(message!=null) sb.append('\n').append("\nMessage: ").append(message);
		
		Throwable e=error;
		while(e!=null){
			
			sb.append('\n');
			Utils.classToStr(sb, e.getClass());
			var msg=e.getLocalizedMessage();
			if(msg!=null) sb.append(": ").append(msg);
			
			e=e.getCause();
		}
		
		warn(sb.toString());
	}
	
}
