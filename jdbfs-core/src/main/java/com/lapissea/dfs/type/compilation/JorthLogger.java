package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.config.ConfigDefs;

import java.util.StringJoiner;
import java.util.function.Consumer;

public interface JorthLogger extends Consumer<CharSequence>{
	
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
	
	@Override
	default void accept(CharSequence charSequence){
		log(charSequence);
	}
	
	void log(CharSequence fragment);
	
	String output();
	
}
