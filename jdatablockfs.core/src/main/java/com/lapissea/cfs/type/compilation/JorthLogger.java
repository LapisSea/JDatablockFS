package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.config.ConfigDefs;

import java.util.StringJoiner;

public interface JorthLogger{
	
	enum CodeLog{
		FALSE,
		TRUE,
		LIVE
	}
	
	static JorthLogger make(){
		return make(ConfigDefs.CLASSGEN_PRINT_BYTECODE.resolve());
	}
	
	static JorthLogger make(CodeLog level){
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
