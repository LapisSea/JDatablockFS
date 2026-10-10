package com.lapissea.fuzz.distributed.bootstrap;

import java.io.File;

public enum HostOs{
	LINUX(":", "/"),
	WINDOWS(";", "\\");
	
	private final String pathSeparator;
	private final String separator;
	
	HostOs(String pathSeparator, String separator){
		this.pathSeparator = pathSeparator;
		this.separator = separator;
	}
	
	public String pathSeparator(){ return pathSeparator; }
	
	public String join(String parent, String child){
		return parent.endsWith(separator)? parent + child : parent + separator + child;
	}
	
	public boolean isAbsolute(String path){
		return switch(this){
			case LINUX -> path.startsWith("/");
			case WINDOWS -> path.matches("[A-Za-z]:[\\\\/].*") || path.startsWith("\\\\");
		};
	}
	
	HostBootstrap bootstrap(){
		return switch(this){
			case LINUX -> PosixBootstrap.INSTANCE;
			case WINDOWS -> WindowsBootstrap.INSTANCE;
		};
	}
	
	public static HostOs local(){
		return File.separatorChar == '\\'? WINDOWS : LINUX;
	}
	
	public static HostOs parse(String value){
		if(value == null || value.isBlank()){ throw new IllegalArgumentException("Missing helper OS"); }
		return switch(value.trim().toLowerCase()){
			case "linux" -> LINUX;
			case "windows" -> WINDOWS;
			default -> throw new IllegalArgumentException("Unsupported helper OS: " + value + " (supported: linux, windows)");
		};
	}
	
	public HostOs other(){ return this == LINUX? WINDOWS : LINUX; }
}
