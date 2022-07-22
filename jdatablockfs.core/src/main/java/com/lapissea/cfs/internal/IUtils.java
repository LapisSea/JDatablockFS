package com.lapissea.cfs.internal;

import java.util.function.Function;
import java.util.stream.Stream;

public class IUtils{
	
	public static Class<?> getCallee(int depth){
		return getCallee(s->s.skip(depth+2));
	}
	
	public static Class<?> getCallee(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow().getDeclaringClass());
	}
	
	public static StackWalker.StackFrame getFrame(int depth){
		return getFrame(s->s.skip(depth+2));
	}
	public static StackWalker.StackFrame getFrame(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow());
	}
}
