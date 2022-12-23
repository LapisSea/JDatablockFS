package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.GlobalConfig;

import java.util.StringJoiner;

public interface JorthLogger{
	
	static JorthLogger make(){
		return make(GlobalConfig.PRINT_BYTECODE);
	}
	static JorthLogger make(GlobalConfig.CodeLog level){
		return switch(level){
			case FALSE -> null;
			case TRUE -> new JorthLogger(){
				private final StringJoiner buffer = new StringJoiner(" ");
				@Override
				public void log(CharSequence fragment){
					buffer.add(fragment);
				}
				@Override
				public String output(){
					return buffer.toString();
				}
			};
			case LIVE -> new JorthLogger(){
				@Override
				public void log(CharSequence fragment){
					System.out.print(fragment + " ");
				}
				@Override
				public String output(){
					return "";
				}
			};
		};
	}
	
	
	void log(CharSequence fragment);
	
	String output();
	
}
