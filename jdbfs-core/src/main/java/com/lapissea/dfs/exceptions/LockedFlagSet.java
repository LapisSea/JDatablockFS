package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.config.ConfigTools;

public class LockedFlagSet extends Exception{
	public LockedFlagSet(ConfigTools.Flag<?> flag, Throwable cause){
		this("The flag " + flag.name() + " is locked", cause);
	}
	public LockedFlagSet(String message){
		super(message);
	}
	public LockedFlagSet(String message, Throwable cause){
		super(message, cause);
	}
}
