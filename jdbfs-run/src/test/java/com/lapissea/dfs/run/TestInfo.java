package com.lapissea.dfs.run;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.TextUtil;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

public record TestInfo(String methodName, String name){
	
	public static TestInfo of(Object... args){
		return ofDepth(2, args);
	}
	public static TestInfo ofDepth(int stackDepth, Object... args){
		var f = Utils.getFrame(stackDepth);
		
		var method = Iters.from(f.getDeclaringClass().getDeclaredMethods()).firstMatching(m -> m.getName().equals(f.getMethodName())).orElseThrow();
		if(!method.isAnnotationPresent(Test.class)){
			throw new IllegalCallerException("Method " + method.getName() + " is not a Test");
		}
		
		StringBuilder sb = new StringBuilder();
		Utils.frameToStr(sb, f, false);
		if(args.length>0){
			sb.append('(');
			for(int i = 0; i<args.length; i++){
				if(args[i] instanceof Class<?> c) args[i] = c.getSimpleName() + ".class";
			}
			sb.append(Arrays.stream(args).map(TextUtil::toString).collect(Collectors.joining(", ")));
			sb.append(')');
		}
		if(sb.length()>100){
			sb.setLength(97);
			sb.append("...");
		}
		return new TestInfo(f.getMethodName(), sb.toString());
	}
}
