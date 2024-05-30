package com.lapissea.dfs.run;

import com.lapissea.dfs.Utils;
import com.lapissea.util.TextUtil;

import java.util.Arrays;
import java.util.stream.Collectors;

public class TestInfo{
	
	public static TestInfo of(Object... args){
		return ofDepth(2, args);
	}
	public static TestInfo ofDepth(int stackDepth, Object... args){
		var           f  = Utils.getFrame(stackDepth);
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
		return new TestInfo(sb.toString());
	}
	
	private final String name;
	
	public TestInfo(String name){
		this.name = name;
	}
	public String getName(){
		return name;
	}
}
