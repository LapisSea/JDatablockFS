package com.lapissea.cfs.logging;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.cfs.Utils.getFrame;

public class Log{
	
	private static final int NONE       =Integer.MIN_VALUE;
	private static final int MIN        =0;
	private static final int WARN       =1;
	private static final int INFO       =2;
	private static final int DEBUG      =3;
	private static final int TRACE      =4;
	private static final int SMALL_TRACE=5;
	private static final int ALL        =Integer.MAX_VALUE;
	
	private static final int LOG_LEVEL=GlobalConfig.configProp("log.level").map(String::toUpperCase).map(level->switch(level){
		case "NONE" -> NONE;
		case "MIN" -> MIN;
		case "WARN" -> WARN;
		case "INFO" -> INFO;
		case "DEBUG" -> DEBUG;
		case "TRACE" -> TRACE;
		case "SMALL_TRACE" -> SMALL_TRACE;
		case "ALL" -> ALL;
		default -> throw new IllegalStateException(level+" is not a recognised logging level");
	}).orElse(GlobalConfig.RELEASE_MODE?WARN:INFO);
	
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
		
		public void log(String message)                          {if(enabled) log(new StringBuilder(message));}
		public void log(String message, Object arg1)             {if(enabled) log(resolveArgs(message, arg1));}
		public void log(String message, Object arg1, Object arg2){if(enabled) log(resolveArgs(message, arg1, arg2));}
		public void log(String message, Object... args)          {if(enabled) log(resolveArgs(message, args));}
		private void log(StringBuilder message){
			log0(receiver.apply(message));
		}
	}
	
	public static Channel channel(boolean enabled, Function<StringBuilder, StringBuilder> out){
		if(!enabled&&LOG_LEVEL>=MIN) return Channel.NOOP;
		return new Channel(out);
	}
	
	public static void log(String message){
		if(LOG_LEVEL>=MIN) LogUtil.println(message);
	}
	public static void log0(CharSequence message){
		LogUtil.println(message);
	}
	
	
	public static void warn(String message)                          {if(LOG_LEVEL>=WARN) warn0(message);}
	public static void warn(String message, Object arg1)             {if(LOG_LEVEL>=WARN) warn0(resolveArgs(message, arg1));}
	public static void warn(String message, Object arg1, Object arg2){if(LOG_LEVEL>=WARN) warn0(resolveArgs(message, arg1, arg2));}
	public static void warn(String message, Object... args)          {if(LOG_LEVEL>=WARN) warn0(resolveArgs(message, args));}
	private static void warn0(CharSequence message){
		LogUtil.printlnEr(message);
	}
	
	public static void info(String message)                          {if(LOG_LEVEL>=INFO) info0(message);}
	public static void info(String message, Object arg1)             {if(LOG_LEVEL>=INFO) info0(resolveArgs(message, arg1));}
	public static void info(String message, Object arg1, Object arg2){if(LOG_LEVEL>=INFO) info0(resolveArgs(message, arg1, arg2));}
	public static void info(String message, Object... args)          {if(LOG_LEVEL>=INFO) info0(resolveArgs(message, args));}
	private static void info0(CharSequence message){
		LogUtil.println(message);
	}
	
	public static void debug(String message)                          {if(LOG_LEVEL>=DEBUG) debug0(message);}
	public static void debug(String message, Object arg1)             {if(LOG_LEVEL>=DEBUG) debug0(resolveArgs(message, arg1));}
	public static void debug(String message, Object arg1, Object arg2){if(LOG_LEVEL>=DEBUG) debug0(resolveArgs(message, arg1, arg2));}
	public static void debug(String message, Object... args)          {if(LOG_LEVEL>=DEBUG) debug0(resolveArgs(message, args));}
	private static void debug0(CharSequence message){
		LogUtil.println(message);
	}
	
	public static void traceCall()                                        {if(LOG_LEVEL>=TRACE) traceCall0("", getFrame(1));}
	public static void traceCall(String message)                          {if(LOG_LEVEL>=TRACE) traceCall0(message, getFrame(1));}
	public static void traceCall(String message, Object arg1)             {if(LOG_LEVEL>=TRACE) traceCall0(resolveArgs(message, arg1), getFrame(1));}
	public static void traceCall(String message, Object arg1, Object arg2){if(LOG_LEVEL>=TRACE) traceCall0(resolveArgs(message, arg1, arg2), getFrame(1));}
	public static void traceCall(String message, Object... args)          {if(LOG_LEVEL>=TRACE) traceCall0(resolveArgs(message, args), getFrame(1));}
	private static void traceCall0(CharSequence message, StackWalker.StackFrame frame){
		var sb=new StringBuilder();
		Utils.frameToStr(sb, frame);
		if(!message.isEmpty()) sb.append(" ->\t").append(message);
		trace(sb.toString());
	}
	
	public static void trace(String message, Object arg1)             {if(LOG_LEVEL>=TRACE) trace0(resolveArgs(message, arg1));}
	public static void trace(String message, Object arg1, Object arg2){if(LOG_LEVEL>=TRACE) trace0(resolveArgs(message, arg1, arg2));}
	public static void trace(String message, Object... args)          {if(LOG_LEVEL>=TRACE) trace0(resolveArgs(message, args));}
	public static void trace(String message)                          {if(LOG_LEVEL>=TRACE) trace0(message);}
	private static void trace0(CharSequence message){
		if(LOG_LEVEL>=TRACE) LogUtil.println(message);
	}
	
	public static void smallTrace(String message, Object arg1)             {if(LOG_LEVEL>=SMALL_TRACE) smallTrace0(resolveArgs(message, arg1));}
	public static void smallTrace(String message, Object arg1, Object arg2){if(LOG_LEVEL>=SMALL_TRACE) smallTrace0(resolveArgs(message, arg1, arg2));}
	public static void smallTrace(String message, Object... args)          {if(LOG_LEVEL>=SMALL_TRACE) smallTrace0(resolveArgs(message, args));}
	public static void smallTrace(String message)                          {if(LOG_LEVEL>=SMALL_TRACE) smallTrace0(message);}
	private static void smallTrace0(CharSequence message){
		if(LOG_LEVEL>=SMALL_TRACE) LogUtil.println(message);
	}
	
	public static void nonFatal(Throwable error, String message){
		if(LOG_LEVEL>=WARN) nonFatal0(error, message);
	}
	public static void nonFatal(Throwable error, String message, Object... args){
		if(LOG_LEVEL>=WARN) nonFatal0(error, resolveArgs(Objects.requireNonNull(message), args));
	}
	
	private static StringBuilder resolveArgs(String message, Object arg1, Object arg2){
		var formatted=new StringBuilder(message.length()+32);
		formatted.append(message);
		resolveArg(formatted, arg1);
		resolveArg(formatted, arg2);
		return formatted;
	}
	private static StringBuilder resolveArgs(String message, Object arg1){
		var formatted=new StringBuilder(message.length()+32);
		formatted.append(message);
		resolveArg(formatted, arg1);
		return formatted;
	}
	
	private static StringBuilder resolveArgs(String message, Object[] args){
		var formatted=new StringBuilder(message.length()+32);
		formatted.append(message);
		for(Object arg : args){
			resolveArg(formatted, arg);
		}
		return formatted;
	}
	
	
	private static void resolveArg(StringBuilder formatted, Object arg){
		var index=formatted.indexOf("{}");
		if(index==-1) throw new IllegalArgumentException();
		if(arg instanceof Supplier<?> supplier) arg=supplier.get();
		formatted.replace(index, index+2, TextUtil.toString(arg));
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
