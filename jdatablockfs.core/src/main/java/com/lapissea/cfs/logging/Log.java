package com.lapissea.cfs.logging;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.lapissea.cfs.internal.IUtils.getFrame;

public class Log{
	
	private static final int MIN  =0;
	private static final int WARN =1;
	private static final int INFO =2;
	private static final int DEBUG=3;
	private static final int TRACE=4;
	private static final int ALL  =Integer.MAX_VALUE;
	
	private static final int LOG_LEVEL=switch(UtilL.sysPropertyByClass(Log.class, "level").orElse("").toUpperCase()){
		case "WARN" -> WARN;
		case "DEBUG" -> DEBUG;
		case "TRACE" -> TRACE;
		case "MIN" -> MIN;
		case "ALL" -> ALL;
		default -> INFO;
	};
	
	
	public static final class Channel{
		
		public static Consumer<StringBuilder> colored(String color, Consumer<StringBuilder> dest){
			return msg->{
				msg.insert(0, color);
				msg.append(ConsoleColors.RESET);
				dest.accept(msg);
			};
		}
		
		private static final Channel NOOP=new Channel(m->{}, false);
		
		private final Consumer<StringBuilder> receiver;
		private final boolean                 enabled;
		
		private Channel(Consumer<StringBuilder> receiver){
			this(receiver, true);
		}
		private Channel(Consumer<StringBuilder> receiver, boolean enabled){
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
		
		private synchronized void log(StringBuilder message){
			receiver.accept(message);
		}
	}
	
	public static Channel channel(boolean enabled, Consumer<StringBuilder> out){
		if(!enabled) return Channel.NOOP;
		return new Channel(out);
	}
	
	public static void log(Channel channel, String message, Object... args){
		if(!channel.isEnabled()) return;
		channel.log(resolveArgs(message, args));
	}
	public static void log(Channel channel, String message){
		if(!channel.isEnabled()) return;
		channel.log(new StringBuilder(message));
	}
	
	public static void log(String message){
		LogUtil.println(message);
	}
	
	
	public static void warn(String message, Object... args){
		if(LOG_LEVEL<WARN) return;
		warn(resolveArgs(message, args).toString());
	}
	public static void warn(String message){
		if(LOG_LEVEL<WARN) return;
		LogUtil.printlnEr(message);
	}
	
	public static void trace(String message, Object... args){
		if(LOG_LEVEL<TRACE) return;
		trace(resolveArgs(message, args).toString());
	}
	public static void trace(String message){
		if(LOG_LEVEL<TRACE) return;
		LogUtil.println(message);
	}
	
	public static void info(String message, Object... args){
		if(LOG_LEVEL<INFO) return;
		info(resolveArgs(message, args).toString());
	}
	public static void info(String message){
		if(LOG_LEVEL<INFO) return;
		LogUtil.println(message);
	}
	
	public static void nonFatal(Throwable error, String message){
		if(LOG_LEVEL<WARN) return;
		nonFatal0(error, message);
	}
	public static void nonFatal(Throwable error, String message, Object... args){
		if(LOG_LEVEL<WARN) return;
		nonFatal0(error, resolveArgs(Objects.requireNonNull(message), args));
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
		
		var f=getFrame(1);
		classToStr(sb, f.getDeclaringClass());
		sb.append('.').append(f.getMethodName()).append('(').append(f.getLineNumber()).append(')');
		if(message!=null) sb.append('\n').append("\nMessage: ").append(message);
		
		Throwable e=error;
		while(e!=null){
			
			sb.append('\n');
			classToStr(sb, e.getClass());
			var msg=e.getLocalizedMessage();
			if(msg!=null) sb.append(": ").append(msg);
			
			e=e.getCause();
		}
		
		warn(sb.toString());
	}
	
	private static void classToStr(StringBuilder sb, Class<?> clazz){
		var enclosing=clazz.getEnclosingClass();
		if(enclosing!=null){
			classToStr(sb, enclosing);
			sb.append('.').append(clazz.getSimpleName());
			return;
		}
		
		var p=clazz.getPackageName();
		for(int i=0;i<p.length();i++){
			if(i==0){
				sb.append(p.charAt(i));
			}else if(p.charAt(i-1)=='.'){
				sb.append(p, i-1, i);
			}
		}
		sb.append('.').append(clazz.getSimpleName());
	}
}
