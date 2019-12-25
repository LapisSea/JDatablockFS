package com.lapissea.fsf;

public enum Version{
	V01(0, 1);
	
	public final byte major;
	public final byte minor;
	
	Version(int major, int minor){
		this.major=(byte)major;
		this.minor=(byte)minor;
	}
	
	public boolean is(byte[] versionBytes){
		return versionBytes[0]==major&&versionBytes[1]==minor;
	}
}
